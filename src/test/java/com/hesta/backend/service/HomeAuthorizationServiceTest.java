package com.hesta.backend.service;

import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.HomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeAuthorizationServiceTest {

    @Mock
    private HomeRepository homeRepository;
    @Mock
    private HomeMemberRepository homeMemberRepository;

    @InjectMocks
    private HomeAuthorizationService authorizationService;

    private UUID userId;
    private UUID homeId;
    private Home home;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        homeId = UUID.randomUUID();
        home = Home.builder().id(homeId).name("Home").build();
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
    }

    @Test
    void activeOwnerCanManageScenes() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(membership(HomeRole.OWNER, MemberStatus.ACTIVE)));

        assertThat(authorizationService.requireSceneManagement(userId, homeId)).isSameAs(home);
    }

    @Test
    void memberCannotMutateScenes() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(membership(HomeRole.MEMBER, MemberStatus.ACTIVE)));

        assertUnauthorized(() -> authorizationService.requireSceneManagement(userId, homeId));
    }

    @Test
    void userWithoutPersistedMembershipCannotAccessAnotherHome() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId)).thenReturn(Optional.empty());

        assertUnauthorized(() -> authorizationService.requireAccess(userId, homeId));
    }

    @Test
    void disabledOwnerCannotAccessScenes() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(membership(HomeRole.OWNER, MemberStatus.DISABLED)));

        assertUnauthorized(() -> authorizationService.requireSceneManagement(userId, homeId));
    }

    private HomeMember membership(HomeRole role, MemberStatus status) {
        return HomeMember.builder().home(home).role(role).status(status).build();
    }

    private void assertUnauthorized(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(AppException.class)
                .extracting(exception -> ((AppException) exception).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
