package com.hesta.backend.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.SecurityConfig;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.realtime.config.RealtimeConfig;
import com.hesta.backend.realtime.config.RealtimeWebSocketConfig;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import com.hesta.backend.realtime.publisher.RealtimeEventPublisherImpl;
import com.hesta.backend.realtime.security.RealtimeWebSocketChannelInterceptor;
import com.hesta.backend.realtime.transport.RealtimeDestinations;
import com.hesta.backend.realtime.transport.WebSocketRealtimeTransport;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.CustomUserDetailsService;
import com.hesta.backend.security.CustomAccessDeniedHandler;
import com.hesta.backend.security.CustomAuthenticationEntryPoint;
import com.hesta.backend.security.JwtAuthenticationFilter;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.RealtimeSubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = RealtimeWebSocketIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.realtime.websocket.heartbeat=1s"
)
@ActiveProfiles("test")
class RealtimeWebSocketIntegrationTest {

    private static final String ACCESS_TOKEN = "integration-access-token";

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @MockitoBean
    private RealtimeSubscriptionService subscriptionService;

    @Autowired
    private RealtimeEventPublisher realtimeEventPublisher;

    @Autowired
    private ObjectMapper objectMapper;

    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler clientTaskScheduler;
    private StompSession stompSession;

    @BeforeEach
    void setUp() {
        clientTaskScheduler = new ThreadPoolTaskScheduler();
        clientTaskScheduler.setPoolSize(1);
        clientTaskScheduler.setThreadNamePrefix("realtime-test-client-");
        clientTaskScheduler.initialize();
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setTaskScheduler(clientTaskScheduler);
    }

    @AfterEach
    void tearDown() {
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.disconnect();
        }
        stompClient.stop();
        clientTaskScheduler.shutdown();
    }

    @Test
    void publish_whenAuthenticatedClientSubscribesToHome_deliversOnlyMatchingHomeEvent() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID subscribedHomeId = UUID.randomUUID();
        UUID otherHomeId = UUID.randomUUID();
        CustomUserDetails userDetails = principal(userId);
        when(jwtTokenProvider.validateToken(ACCESS_TOKEN)).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(ACCESS_TOKEN)).thenReturn(userId);
        when(userDetailsService.loadUserById(userId)).thenReturn(userDetails);
        CountDownLatch subscriptionAuthorized = new CountDownLatch(1);
        doAnswer(invocation -> {
            subscriptionAuthorized.countDown();
            return null;
        }).when(subscriptionService).authorizeSubscription(userId, subscribedHomeId);

        stompSession = connect(ACCESS_TOKEN);
        BlockingQueue<JsonNode> receivedEvents = new LinkedBlockingQueue<>();
        stompSession.subscribe(
                RealtimeDestinations.homeEvents(subscribedHomeId),
                jsonFrameHandler(receivedEvents)
        );
        assertThat(subscriptionAuthorized.await(5, TimeUnit.SECONDS)).isTrue();

        realtimeEventPublisher.publish(RealtimeEvent.create(
                RealtimeEventType.DEVICE_STATE_CHANGED,
                otherHomeId,
                new TestPayload("other-home")
        ));
        assertThat(receivedEvents.poll(500, TimeUnit.MILLISECONDS)).isNull();

        RealtimeEvent<TestPayload> expected = RealtimeEvent.create(
                RealtimeEventType.DEVICE_STATE_CHANGED,
                subscribedHomeId,
                new TestPayload("subscribed-home")
        );
        realtimeEventPublisher.publish(expected);

        JsonNode received = receivedEvents.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.path("eventId").asText()).isEqualTo(expected.eventId());
        assertThat(received.path("type").asText()).isEqualTo("DEVICE_STATE_CHANGED");
        assertThat(received.path("homeId").asText()).isEqualTo(subscribedHomeId.toString());
        assertThat(received.path("data").path("value").asText()).isEqualTo("subscribed-home");
        verify(subscriptionService).authorizeSubscription(userId, subscribedHomeId);
    }

    @Test
    void connect_withoutStompBearerToken_rejectsAnonymousConnection() {
        assertThatThrownBy(() -> connect(null))
                .isInstanceOf(ExecutionException.class);
    }

    private StompSession connect(String accessToken) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.setOrigin("http://localhost:5173");
        StompHeaders connectHeaders = new StompHeaders();
        if (accessToken != null) {
            connectHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }

        return stompClient.connectAsync(
                "ws://localhost:" + port + RealtimeDestinations.WEBSOCKET_ENDPOINT,
                handshakeHeaders,
                connectHeaders,
                new StompSessionHandlerAdapter() {
                }
        ).get(5, TimeUnit.SECONDS);
    }

    private StompFrameHandler jsonFrameHandler(BlockingQueue<JsonNode> receivedEvents) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    receivedEvents.add(objectMapper.readTree((byte[]) payload));
                } catch (Exception exception) {
                    throw new IllegalStateException("Unable to read realtime test event", exception);
                }
            }
        };
    }

    private CustomUserDetails principal(UUID userId) {
        return new CustomUserDetails(
                userId,
                "member@example.com",
                "",
                AccountStatus.ACTIVE,
                List.of(() -> "ROLE_USER")
        );
    }

    private record TestPayload(String value) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @Import({
            RealtimeConfig.class,
            RealtimeWebSocketConfig.class,
            RealtimeWebSocketChannelInterceptor.class,
            WebSocketRealtimeTransport.class,
            RealtimeEventPublisherImpl.class,
            SecurityConfig.class,
            JwtAuthenticationFilter.class,
            CustomAuthenticationEntryPoint.class,
            CustomAccessDeniedHandler.class
    })
    static class TestApplication {
    }
}
