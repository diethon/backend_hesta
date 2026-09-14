package com.hesta.backend.service;

import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.service.impl.RealtimeSubscriptionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RealtimeSubscriptionServiceTest {

    @Mock
    private HomeMemberRepository homeMemberRepository;

    @InjectMocks
    private RealtimeSubscriptionServiceImpl subscriptionService;

    @Test
    void authorizeSubscription_whenUserIsActiveHomeMember_allowsSubscription() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        HomeMember membership = HomeMember.builder().status(MemberStatus.ACTIVE).build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(membership));

        assertThatCode(() -> subscriptionService.authorizeSubscription(userId, homeId))
                .doesNotThrowAnyException();
    }

    @Test
    void authorizeSubscription_whenUserIsNotMember_rejectsSubscription() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Optional.empty());

        assertUnauthorized(userId, homeId);
    }

    @Test
    void authorizeSubscription_whenMembershipIsInactive_rejectsSubscription() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        HomeMember membership = HomeMember.builder().status(MemberStatus.DISABLED).build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(membership));

        assertUnauthorized(userId, homeId);
    }

    private void assertUnauthorized(UUID userId, UUID homeId) {
        assertThatThrownBy(() -> subscriptionService.authorizeSubscription(userId, homeId))
                .isInstanceOf(AppException.class)
                .extracting(exception -> ((AppException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
