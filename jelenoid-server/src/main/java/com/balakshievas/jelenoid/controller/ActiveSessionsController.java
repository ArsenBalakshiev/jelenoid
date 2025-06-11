package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.dto.PendingRequest;
import com.balakshievas.jelenoid.dto.Session;
import com.balakshievas.jelenoid.service.ActiveSessionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.BlockingQueue;

@RestController
@RequestMapping("/api/limit")
public class ActiveSessionsController {

    @Autowired
    private ActiveSessionsService activeSessionsService;

    @GetMapping("/sessions")
    public Map<String, Session> getAllSessions() {
        return activeSessionsService.getActiveSessions();
    }

    @GetMapping("/request")
    public BlockingQueue<PendingRequest> getAllPendingRequests() {
        return activeSessionsService.getPendingRequests();
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
