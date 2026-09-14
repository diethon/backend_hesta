package com.hesta.backend.realtime.security;

import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.realtime.transport.RealtimeDestinations;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.CustomUserDetailsService;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.RealtimeSubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeWebSocketChannelInterceptorTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private CustomUserDetailsService userDetailsService;

    @Mock
    private RealtimeSubscriptionService subscriptionService;

    @Mock
    private MessageChannel messageChannel;

    @InjectMocks
    private RealtimeWebSocketChannelInterceptor interceptor;

    @Test
    void connect_withValidBearerToken_setsAuthenticatedCustomUser() {
        UUID userId = UUID.randomUUID();
        CustomUserDetails userDetails = principal(userId);
        when(jwtTokenProvider.validateToken("access-token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("access-token")).thenReturn(userId);
        when(userDetailsService.loadUserById(userId)).thenReturn(userDetails);
        Message<byte[]> message = connectMessage("Bearer access-token");

        Message<?> result = interceptor.preSend(message, messageChannel);

        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(accessor).isNotNull();
        assertThat(accessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(((UsernamePasswordAuthenticationToken) accessor.getUser()).getPrincipal())
                .isSameAs(userDetails);
    }

    @Test
    void connect_withoutBearerToken_rejectsAnonymousConnection() {
        Message<byte[]> message = connectMessage(null);

        assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void connect_withInvalidBearerToken_rejectsConnection() {
        when(jwtTokenProvider.validateToken("invalid-token")).thenReturn(false);
        Message<byte[]> message = connectMessage("Bearer invalid-token");

        assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void connect_withDisabledAccount_rejectsConnection() {
        UUID userId = UUID.randomUUID();
        when(jwtTokenProvider.validateToken("access-token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("access-token")).thenReturn(userId);
        when(userDetailsService.loadUserById(userId))
                .thenReturn(principal(userId, AccountStatus.DISABLED));

        assertThatThrownBy(() -> interceptor.preSend(connectMessage("Bearer access-token"), messageChannel))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void connect_withLockedAccount_rejectsConnection() {
        UUID userId = UUID.randomUUID();
        when(jwtTokenProvider.validateToken("access-token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("access-token")).thenReturn(userId);
        when(userDetailsService.loadUserById(userId))
                .thenReturn(principal(userId, AccountStatus.LOCKED));

        assertThatThrownBy(() -> interceptor.preSend(connectMessage("Bearer access-token"), messageChannel))
                .isInstanceOf(LockedException.class);
    }

    @Test
    void subscribe_withAuthenticatedUser_authorizesExtractedHome() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        Message<byte[]> message = subscribeMessage(
                RealtimeDestinations.homeEvents(homeId),
                authenticated(principal(userId))
        );

        interceptor.preSend(message, messageChannel);

        verify(subscriptionService).authorizeSubscription(userId, homeId);
    }

    @Test
    void subscribe_withoutAuthentication_rejectsSubscription() {
        Message<byte[]> message = subscribeMessage(
                RealtimeDestinations.homeEvents(UUID.randomUUID()),
                null
        );

        assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void subscribe_toNonHomeDestination_rejectsSubscription() {
        Message<byte[]> message = subscribeMessage(
                "/topic/global/events",
                authenticated(principal(UUID.randomUUID()))
        );

        assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void send_toBrokerDestination_rejectsClientPublishedEvent() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(RealtimeDestinations.homeEvents(UUID.randomUUID()));
        accessor.setUser(authenticated(principal(UUID.randomUUID())));
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
                .isInstanceOf(AccessDeniedException.class);
    }

    private Message<byte[]> connectMessage(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) {
            accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeMessage(String destination, UsernamePasswordAuthenticationToken user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("subscription-1");
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private UsernamePasswordAuthenticationToken authenticated(CustomUserDetails userDetails) {
        return UsernamePasswordAuthenticationToken.authenticated(
                userDetails,
                null,
                userDetails.getAuthorities()
        );
    }

    private CustomUserDetails principal(UUID userId) {
        return principal(userId, AccountStatus.ACTIVE);
    }

    private CustomUserDetails principal(UUID userId, AccountStatus status) {
        return new CustomUserDetails(
                userId,
                "member@example.com",
                "",
                status,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
