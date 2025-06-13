package com.balakshievas.jelenoid.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;


@Service
public class EmitterService {
    private static final Logger log = LoggerFactory.getLogger(EmitterService.class);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // Метод для добавления нового подписчика (UI)
    public void addEmitter(SseEmitter emitter) {
        emitter.onCompletion(() -> {
            log.info("Emitter completed, removing from list.");
            this.emitters.remove(emitter);
        });
        emitter.onError(e -> {
            log.error("Emitter error", e);
            this.emitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.warn("Emitter timed out, removing from list.");
            this.emitters.remove(emitter);
        });
        this.emitters.add(emitter);
    }

    // Метод для отправки события всем подписчикам
    public void dispatch(Object event) {
        for (SseEmitter emitter : emitters) {
            try {
                SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                        .name("state-update") // Имя события, на которое подпишется UI
                        .data(event);
                emitter.send(eventBuilder);
            } catch (IOException e) {
                log.warn("Failed to send event to an emitter, removing it.", e);
                emitters.remove(emitter);
            }
        }
    }
}
