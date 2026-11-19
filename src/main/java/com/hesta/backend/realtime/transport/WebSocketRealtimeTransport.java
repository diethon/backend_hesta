package com.hesta.backend.realtime.transport;

import com.hesta.backend.dto.response.NotificationRealtimePayload;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.event.MemberAccessChangedEvent;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketRealtimeTransport implements RealtimeTransport {

    private final SimpMessagingTemplate messagingTemplate;
    private final HomeMemberRepository homeMemberRepository;
    private final HomeAuthorizationService homeAuthorizationService;

    @Override
    public void publish(RealtimeEvent<?> event) {
        if (event == null || event.homeId() == null) {
            return;
        }

        UUID homeId = event.homeId();

        // 1. Broadcast to the shared topic for OWNER and scope-ALL members
        messagingTemplate.convertAndSend(RealtimeDestinations.homeEvents(homeId), event);

        // 2. Deliver filtered events to active CUSTOM members via their individual user destinations
        try {
            List<HomeMember> members = homeMemberRepository.findByHomeId(homeId);
            for (HomeMember member : members) {
                if (member.getStatus() != MemberStatus.ACTIVE
                        || member.getRole() == HomeRole.OWNER
                        || member.getAccessScope() != AccessScope.CUSTOM
                        || member.getUser() == null) {
                    continue;
                }

                UUID memberUserId = member.getUser().getId();
                if (shouldDeliverToCustomMember(memberUserId, event)) {
                    messagingTemplate.convertAndSendToUser(
                            memberUserId.toString(),
                            RealtimeDestinations.userHomeEvents(homeId),
                            event
                    );
                }
            }
        } catch (Exception e) {
            log.error("Error delivering realtime event to CUSTOM members in home {}: {}", homeId, e.getMessage(), e);
        }
    }

    private boolean shouldDeliverToCustomMember(UUID userId, RealtimeEvent<?> event) {
        if (event.deviceId() != null) {
            // Must have at least VIEW level to receive device state / sensor updates
            return homeAuthorizationService.hasDeviceAccess(userId, event.deviceId(), AccessLevel.VIEW);
        }

        if (event.type() == RealtimeEventType.NOTIFICATION_CREATED) {
            if (event.data() instanceof NotificationRealtimePayload payload) {
                return userId.equals(payload.recipientId());
            }
            return false;
        }

        if (event.type() == RealtimeEventType.MEMBER_ACCESS_UPDATED) {
            if (event.data() instanceof MemberAccessChangedEvent accessEvent) {
                return userId.equals(accessEvent.userId());
            }
            return true;
        }

        return true;
    }
}
