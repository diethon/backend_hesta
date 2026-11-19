package com.hesta.backend.realtime.transport;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active WebSocket sessions and supports immediate disconnection
 * of affected members when access permissions or home roles change.
 */
@Component
@Slf4j
public class WebSocketSessionManager implements WebSocketHandlerDecoratorFactory {

    private final Map<String, WebSocketSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> userSessionIds = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessionsById.put(session.getId(), session);
                extractAndRegisterUser(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessionsById.remove(session.getId());
                userSessionIds.values().forEach(set -> set.remove(session.getId()));
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    public void registerUserSession(UUID userId, String sessionId) {
        if (userId != null && sessionId != null) {
            userSessionIds.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        }
    }

    public void closeUserSessions(UUID userId) {
        if (userId == null) return;
        Set<String> sessionIds = userSessionIds.remove(userId);
        if (sessionIds == null || sessionIds.isEmpty()) return;

        for (String sId : sessionIds) {
            WebSocketSession session = sessionsById.remove(sId);
            if (session != null && session.isOpen()) {
                try {
                    log.info("Closing WebSocket session {} for user {} due to access change/revocation", sId, userId);
                    session.close(new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "MEMBER_ACCESS_UPDATED"));
                } catch (IOException e) {
                    log.warn("Failed to close WebSocket session {}: {}", sId, e.getMessage());
                }
            }
        }
    }

    private void extractAndRegisterUser(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal != null) {
            try {
                UUID userId = UUID.fromString(principal.getName());
                registerUserSession(userId, session.getId());
            } catch (Exception ignored) {
            }
        }
    }
}
