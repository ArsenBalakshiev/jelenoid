package com.balakshievas;

import io.nats.client.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
public class NatsConsumer implements Runnable {

    private static final String SUBJECT_PATTERN = "sessions.*";
    private static final String DURABLE_NAME    = "service-metrics";

    private final Connection  nats;
    private final JetStream   js;
    private final SessionDao  dao;

    public NatsConsumer(SessionDao dao, Options natsOpts) throws Exception {
        this.dao  = dao;
        this.nats = Nats.connect(natsOpts);
        this.js   = nats.jetStream();
    }

    @Override
    public void run() {
        try {
            PushSubscribeOptions opts = PushSubscribeOptions.builder()
                    .stream("SESSIONS")
                    .durable(DURABLE_NAME)
                    .build();

            JetStreamSubscription sub = js.subscribe(SUBJECT_PATTERN, opts);
            log.info("Subscribed to '{}', durable '{}'", SUBJECT_PATTERN, DURABLE_NAME);

            while (!Thread.currentThread().isInterrupted()) {
                Message msg = sub.nextMessage(Duration.ofSeconds(5));
                if (msg == null) continue;

                try {
                    dao.save(msg.getData());
                    msg.ack();
                } catch (Exception ex) {
                    log.error("Failed to persist message – NAK", ex);
                    msg.nakWithDelay(30_000);
                }
            }
        } catch (Exception e) {
            log.error("Fatal error inside NatsConsumer", e);
        } finally {
            try { nats.close(); } catch (Exception ignore) {}
        }
    }
}
