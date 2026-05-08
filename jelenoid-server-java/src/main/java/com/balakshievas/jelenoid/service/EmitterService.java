package com.balakshievas.jelenoid.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class EmitterService {
    private static final Logger log = LoggerFactory.getLogger(EmitterService.class);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public void addEmitter(SseEmitter emitter) {
        emitter.onCompletion(() -> {
            log.debug("Emitter completed, removing from list.");
            this.emitters.remove(emitter);
        });
        emitter.onError(e -> {
            if (e instanceof IOException) {
                log.debug("Client disconnected (IOException). Removing emitter.");
            } else {
                log.error("Emitter error", e);
            }
            this.emitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.debug("Emitter timed out, removing from list.");
            this.emitters.remove(emitter);
        });
        this.emitters.add(emitter);
    }

    public void dispatch(Object event) {
        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                        .name("state-update")
                        .data(event);
                emitter.send(eventBuilder);
            } catch (Exception e) {
                log.debug("Failed to send event to an emitter (client likely disconnected). Marking for removal.");
                deadEmitters.add(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
            log.debug("Removed {} dead emitters.", deadEmitters.size());
        }
    }
}
