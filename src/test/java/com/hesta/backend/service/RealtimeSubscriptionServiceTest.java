package com.hesta.backend.service;

import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.realtime.transport.RealtimeDestinations;
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
        HomeMember membership = HomeMember.builder()
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.MEMBER)
                .accessScope(AccessScope.ALL)
                .build();
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

        assertUnauthorized(userId, homeId, RealtimeDestinations.homeEvents(homeId));
    }

    @Test
    void authorizeSubscription_whenMembershipIsInactive_rejectsSubscription() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        HomeMember membership = HomeMember.builder().status(MemberStatus.DISABLED).build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(membership));

        assertUnauthorized(userId, homeId, RealtimeDestinations.homeEvents(homeId));
    }

    @Test
    void authorizeSubscription_whenCustomMemberSubscribesToSharedTopic_rejectsSubscription() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        HomeMember membership = HomeMember.builder()
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.MEMBER)
                .accessScope(AccessScope.CUSTOM)
                .build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(membership));

        assertUnauthorized(userId, homeId, RealtimeDestinations.homeEvents(homeId));
    }

    @Test
    void authorizeSubscription_whenCustomMemberSubscribesToUserQueue_allowsSubscription() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        HomeMember membership = HomeMember.builder()
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.MEMBER)
                .accessScope(AccessScope.CUSTOM)
                .build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(membership));

        assertThatCode(() -> subscriptionService.authorizeSubscription(
                userId,
                homeId,
                "/user" + RealtimeDestinations.userHomeEvents(homeId)
        )).doesNotThrowAnyException();
    }

    @Test
    void authorizeSubscription_whenOwnerSubscribesToSharedTopic_allowsSubscription() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        HomeMember membership = HomeMember.builder()
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.OWNER)
                .accessScope(AccessScope.ALL)
                .build();
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(membership));

        assertThatCode(() -> subscriptionService.authorizeSubscription(
                userId,
                homeId,
                RealtimeDestinations.homeEvents(homeId)
        )).doesNotThrowAnyException();
    }

    private void assertUnauthorized(UUID userId, UUID homeId, String destination) {
        assertThatThrownBy(() -> subscriptionService.authorizeSubscription(userId, homeId, destination))
                .isInstanceOf(AppException.class)
                .extracting(exception -> ((AppException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
