package com.balakshievas.containermanager.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoImageException.class)
    public ResponseEntity<ErrorResponse> handleNoImageException(NoImageException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage()));
    }

    public record ErrorResponse(String message) {}

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        Map<String, Object> errorBody = Map.of(
                "value", Map.of(
                        "error", getWebDriverErrorCode(ex.getStatusCode().value()),
                        "message", ex.getReason() != null ? ex.getReason() : "Unknown error",
                        "stacktrace", ""
                )
        );

        return ResponseEntity.status(ex.getStatusCode()).body(errorBody);
    }

    private String getWebDriverErrorCode(int httpStatus) {
        if (httpStatus == 401 || httpStatus == 403) return "session not created";
        if (httpStatus == 404) return "invalid session id";
        return "unknown error";
    }

}
