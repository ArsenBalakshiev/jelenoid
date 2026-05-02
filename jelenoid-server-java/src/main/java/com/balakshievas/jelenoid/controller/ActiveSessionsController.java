package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.dto.PendingRequest;
import com.balakshievas.jelenoid.dto.SeleniumSession;
import com.balakshievas.jelenoid.service.ActiveSessionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/limit")
public class ActiveSessionsController {

    @Autowired
    private ActiveSessionsService activeSessionsService;

    @GetMapping("/sessions")
    public Map<String, SeleniumSession> getAllSessions() {
        return activeSessionsService.getSeleniumActiveSessions();
    }

    @GetMapping("/request")
    public List<PendingRequest> getAllPendingRequests() {
        return new ArrayList<>(activeSessionsService.getSeleniumPendingRequests());
    }

    @GetMapping("/sessions/size")
    public Integer getAllSessionsSize() {
        return activeSessionsService.getActiveSessionsCount();
    }

    @GetMapping("/request/size")
    public Integer getAllPendingRequestsSize() {
        return activeSessionsService.getPendingRequestsCount();
    }
}
