package com.balakshievas.jelenoid.config;

import com.balakshievas.jelenoid.exception.BrowserVersionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class WebDriverErrorHandler {

    @ExceptionHandler(BrowserVersionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handle(BrowserVersionNotFoundException e) {

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("error", "session not created");
        value.put("message", e.getMessage());
        value.put("stacktrace", "");

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("value", value));
    }

}
