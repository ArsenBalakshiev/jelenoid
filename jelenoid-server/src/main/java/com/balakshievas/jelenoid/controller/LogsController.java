package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.service.SessionService;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.Closeable;
import java.io.IOException;

@RestController
@RequestMapping("/logs")
public class LogsController {

    private static final Logger log = LoggerFactory.getLogger(LogsController.class);

    @Autowired
    private SessionService sessionService;

    @GetMapping(path = "/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable String sessionId) {
        log.info("Starting log stream for session: {}", sessionId);
        // Создаем SseEmitter с очень большим таймаутом
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        // Создаем callback, который будет пересылать логи в SseEmitter
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                try {
                    // Отправляем данные клиенту в формате SSE
                    emitter.send(SseEmitter.event().data(frame.toString()));
                } catch (IOException e) {
                    log.warn("Failed to send log event for session {}, closing stream.", sessionId, e);
                    // Если клиент отключился, завершаем эмиттер
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onComplete() {
                log.info("Log stream completed for session: {}", sessionId);
                emitter.complete();
            }

            @Override
            public void onError(Throwable throwable) {
                log.error("Error in log stream for session: {}", sessionId, throwable);
                emitter.completeWithError(throwable);
            }
        };

        // Запускаем стриминг и получаем объект для его закрытия
        Closeable logStream = sessionService.streamLogsForSession(sessionId, callback);

        // Регистрируем обработчики на случай, если клиент закроет соединение или будет таймаут
        emitter.onCompletion(() -> closeStream(logStream, "completion"));
        emitter.onTimeout(() -> closeStream(logStream, "timeout"));
        emitter.onError(e -> closeStream(logStream, "error"));

        return emitter;
    }

    private void closeStream(Closeable stream, String reason) {
        log.info("Closing log stream due to: {}", reason);
        try {
            stream.close();
        } catch (IOException e) {
            log.warn("Error while closing log stream: {}", e.getMessage());
        }
    }
}