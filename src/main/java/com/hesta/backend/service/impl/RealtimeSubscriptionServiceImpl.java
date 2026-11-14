package com.hesta.backend.service.impl;

import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.realtime.transport.RealtimeDestinations;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.service.RealtimeSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RealtimeSubscriptionServiceImpl implements RealtimeSubscriptionService {

    private final HomeMemberRepository homeMemberRepository;

    @Override
    @Transactional(readOnly = true)
    public void authorizeSubscription(UUID userId, UUID homeId) {
        authorizeSubscription(userId, homeId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public void authorizeSubscription(UUID userId, UUID homeId, String destination) {
        HomeMember membership = homeMemberRepository.findByHomeIdAndUserId(homeId, userId)
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));

        if (destination != null && RealtimeDestinations.isSharedTopic(destination)) {
            // Shared topic /topic/homes/{homeId}/events is FORBIDDEN for CUSTOM members
            if (membership.getRole() != HomeRole.OWNER && membership.getAccessScope() == AccessScope.CUSTOM) {
                log.warn("CUSTOM member {} attempted to subscribe to shared topic {}. Forbidden.", userId, destination);
                throw new AppException(ErrorCode.UNAUTHORIZED);
            }
        }
    }
}
