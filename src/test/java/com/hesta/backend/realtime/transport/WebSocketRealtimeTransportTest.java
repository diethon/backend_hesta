package com.hesta.backend.realtime.transport;

import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketRealtimeTransportTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private HomeMemberRepository homeMemberRepository;

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @InjectMocks
    private WebSocketRealtimeTransport transport;

    @Test
    void publish_withHomeEvent_routesToSharedTopic() {
        UUID firstHomeId = UUID.randomUUID();
        UUID secondHomeId = UUID.randomUUID();
        RealtimeEvent<TestPayload> event = RealtimeEvent.create(
                RealtimeEventType.SENSOR_READING_UPDATED,
                firstHomeId,
                new TestPayload("23.5")
        );

        when(homeMemberRepository.findByHomeId(firstHomeId)).thenReturn(List.of());

        transport.publish(event);

        verify(messagingTemplate).convertAndSend(RealtimeDestinations.homeEvents(firstHomeId), event);
        verify(messagingTemplate, never()).convertAndSend(RealtimeDestinations.homeEvents(secondHomeId), event);
    }

    @Test
    void publish_withDeviceEvent_whenCustomMemberHasViewAccess_deliversToUserDestination() {
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID customUserId = UUID.randomUUID();

        User user = User.builder().id(customUserId).build();
        HomeMember customMember = HomeMember.builder()
                .user(user)
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.MEMBER)
                .accessScope(AccessScope.CUSTOM)
                .build();

        when(homeMemberRepository.findByHomeId(homeId)).thenReturn(List.of(customMember));
        when(homeAuthorizationService.hasDeviceAccess(customUserId, deviceId, AccessLevel.VIEW)).thenReturn(true);

        RealtimeEvent<TestPayload> event = RealtimeEvent.create(
                RealtimeEventType.DEVICE_STATE_CHANGED,
                homeId,
                deviceId,
                new TestPayload("ON")
        );

        transport.publish(event);

        // Verify broadcast to shared topic
        verify(messagingTemplate).convertAndSend(RealtimeDestinations.homeEvents(homeId), event);
        // Verify targeted delivery to custom user destination
        verify(messagingTemplate).convertAndSendToUser(
                eq(customUserId.toString()),
                eq(RealtimeDestinations.userHomeEvents(homeId)),
                eq(event)
        );
    }

    @Test
    void publish_withDeviceEvent_whenCustomMemberHasNoneAccess_doesNotDeliverToUserDestination() {
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID customUserId = UUID.randomUUID();

        User user = User.builder().id(customUserId).build();
        HomeMember customMember = HomeMember.builder()
                .user(user)
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.MEMBER)
                .accessScope(AccessScope.CUSTOM)
                .build();

        when(homeMemberRepository.findByHomeId(homeId)).thenReturn(List.of(customMember));
        when(homeAuthorizationService.hasDeviceAccess(customUserId, deviceId, AccessLevel.VIEW)).thenReturn(false);

        RealtimeEvent<TestPayload> event = RealtimeEvent.create(
                RealtimeEventType.DEVICE_STATE_CHANGED,
                homeId,
                deviceId,
                new TestPayload("ON")
        );

        transport.publish(event);

        // Verify broadcast to shared topic
        verify(messagingTemplate).convertAndSend(RealtimeDestinations.homeEvents(homeId), event);
        // Verify NO delivery to custom user
        verify(messagingTemplate, never()).convertAndSendToUser(
                anyString(),
                anyString(),
                any()
        );
    }

    private record TestPayload(String value) {
    }
}
