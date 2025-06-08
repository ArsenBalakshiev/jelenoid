package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.dto.SessionInfo;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ActiveSessionsService {

    private static final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    public Map.Entry<String, SessionInfo> putSession(String sessionId, SessionInfo sessionInfo) {
        Map.Entry<String, SessionInfo> entry = Map.entry(sessionId, sessionInfo);
        activeSessions.put(sessionId, sessionInfo);
        return entry;
    }

    public SessionInfo getSession(String sessionId) {
        return activeSessions.get(sessionId);
    }

    public SessionInfo removeSession(String sessionId) {
        return activeSessions.remove(sessionId);
    }
}
