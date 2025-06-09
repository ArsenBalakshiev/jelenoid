package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.dto.Session;
import com.balakshievas.jelenoid.service.ActiveSessionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class ActiveSessionsController {

    @Autowired
    private ActiveSessionsService activeSessionsService;

    @GetMapping
    public Map<String, Session> getAllSessions() {
        return activeSessionsService.getActiveSessions();
    }
}
