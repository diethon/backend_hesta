package com.hesta.backend.realtime.config;

import com.hesta.backend.realtime.security.RealtimeWebSocketChannelInterceptor;
import com.hesta.backend.realtime.transport.RealtimeDestinations;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class RealtimeWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String[] ALLOWED_ORIGINS = {
            "http://localhost:5173",
            "http://127.0.0.1:5173"
    };
    private static final int TIME_TO_FIRST_MESSAGE_MILLIS = 10_000;

    private final RealtimeWebSocketChannelInterceptor channelInterceptor;
    private final WebSocketRealtimeProperties properties;
    private final TaskScheduler heartbeatTaskScheduler;

    public RealtimeWebSocketConfig(
            RealtimeWebSocketChannelInterceptor channelInterceptor,
            WebSocketRealtimeProperties properties,
            @Qualifier("realtimeHeartbeatTaskScheduler") TaskScheduler heartbeatTaskScheduler
    ) {
        this.channelInterceptor = channelInterceptor;
        this.properties = properties;
        this.heartbeatTaskScheduler = heartbeatTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(RealtimeDestinations.WEBSOCKET_ENDPOINT)
                .setAllowedOrigins(ALLOWED_ORIGINS);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeatMillis = properties.heartbeat().toMillis();
        registry.enableSimpleBroker(RealtimeDestinations.TOPIC_PREFIX)
                .setHeartbeatValue(new long[]{heartbeatMillis, heartbeatMillis})
                .setTaskScheduler(heartbeatTaskScheduler);
        registry.setApplicationDestinationPrefixes(RealtimeDestinations.APPLICATION_PREFIX);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(channelInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setTimeToFirstMessage(TIME_TO_FIRST_MESSAGE_MILLIS);
    }
}
