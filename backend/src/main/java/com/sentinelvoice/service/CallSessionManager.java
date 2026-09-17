package com.sentinelvoice.service;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.InterventionLevel;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CallSessionManager {

    private final Map<String, CallSession> sessions = new ConcurrentHashMap<>();

    public CallSession createSession(String sessionId, String callerId, String callerName, String claimedRole) {
        CallSession session = new CallSession(sessionId, callerId, callerName, claimedRole);
        sessions.put(sessionId, session);
        return session;
    }

    public CallSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void updateRisk(String sessionId, double score, InterventionLevel level) {
        CallSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        session.setRollingRiskScore(score);
        session.setCurrentInterventionLevel(level);
        session.getMetadata().put("riskScore", score);
        session.getMetadata().put("interventionLevel", level.name());
    }

    public void appendAudio(String sessionId, byte[] chunk) {
        CallSession session = sessions.get(sessionId);
        if (session == null) {
            return;
        }
        session.appendAudioChunk(chunk);
    }

    public byte[] drainAudio(String sessionId) {
        CallSession session = sessions.get(sessionId);
        if (session == null) {
            return new byte[0];
        }
        return session.drainAudioBuffer();
    }

    public Map<String, CallSession> getAllSessions() {
        return Map.copyOf(sessions);
    }
}
