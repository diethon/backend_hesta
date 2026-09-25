package com.hesta.backend.realtime.security;

import com.hesta.backend.realtime.transport.RealtimeDestinations;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.CustomUserDetailsService;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.RealtimeSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.messaging.support.ChannelInterceptor;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RealtimeWebSocketChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final RealtimeSubscriptionService subscriptionService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (accessor.getCommand() == StompCommand.CONNECT) {
            authenticate(accessor);
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            authorizeSubscription(accessor);
        } else if (accessor.getCommand() == StompCommand.SEND) {
            throw new AccessDeniedException("Client realtime messages are not enabled");
        }

        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BadCredentialsException("WebSocket authentication failed");
        }

        String token = authorization.substring(BEARER_PREFIX.length());
        if (!StringUtils.hasText(token) || !jwtTokenProvider.validateToken(token)) {
            throw new BadCredentialsException("WebSocket authentication failed");
        }

        UUID userId = jwtTokenProvider.getUserIdFromToken(token);
        UserDetails loadedUser = userDetailsService.loadUserById(userId);
        if (!(loadedUser instanceof CustomUserDetails userDetails)) {
            throw new BadCredentialsException("WebSocket authentication failed");
        }
        if (!userDetails.isAccountNonLocked()) {
            throw new LockedException("User account is locked");
        }
        if (!userDetails.isEnabled()) {
            throw new DisabledException("User account is disabled");
        }

        accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(
                userDetails,
                null,
                userDetails.getAuthorities()
        ));
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        CustomUserDetails userDetails = authenticatedUser(accessor);
        UUID homeId = RealtimeDestinations.extractHomeId(accessor.getDestination())
                .orElseThrow(() -> new AccessDeniedException("Realtime destination is not allowed"));

        subscriptionService.authorizeSubscription(userDetails.getId(), homeId);
    }

    private CustomUserDetails authenticatedUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails;
        }

        throw new AccessDeniedException("Authenticated WebSocket user is required");
    }
}
