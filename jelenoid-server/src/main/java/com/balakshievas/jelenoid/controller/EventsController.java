package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.service.EmitterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class EventsController {

    @Autowired
    private EmitterService emitterService;

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // Создаем эмиттер с длинным таймаутом
        emitterService.addEmitter(emitter);
        return emitter;
    }
}
