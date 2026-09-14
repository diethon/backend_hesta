package com.hesta.backend.service.impl;

import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.service.RealtimeSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RealtimeSubscriptionServiceImpl implements RealtimeSubscriptionService {

    private final HomeMemberRepository homeMemberRepository;

    @Override
    @Transactional(readOnly = true)
    public void authorizeSubscription(UUID userId, UUID homeId) {
        boolean activeMember = homeMemberRepository.findByHomeIdAndUserId(homeId, userId)
                .filter(membership -> membership.getStatus() == MemberStatus.ACTIVE)
                .isPresent();
        if (!activeMember) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }
}
