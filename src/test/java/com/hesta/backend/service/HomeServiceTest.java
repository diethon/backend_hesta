package com.hesta.backend.service;

import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.service.impl.HomeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HomeServiceTest {

    @Mock
    private HomeRepository homeRepository;

    @Mock
    private HomeMemberRepository homeMemberRepository;

    @InjectMocks
    private HomeServiceImpl homeService;

    private final UUID userId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();
    private Home home;

    @BeforeEach
    void setUp() {
        home = Home.builder()
                .id(homeId)
                .name("Nhà của Phong")
                .build();
    }

    @Test
    void updateHome_Success_AsOwner() {
        HomeMember ownerMember = HomeMember.builder()
                .home(home)
                .role(HomeRole.OWNER)
                .build();

        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(ownerMember));
        when(homeRepository.findById(homeId))
                .thenReturn(Optional.of(home));
        when(homeRepository.save(any(Home.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = homeService.updateHome(userId, homeId, "Căn hộ 1205");

        assertThat(result.get("homeName")).isEqualTo("Căn hộ 1205");
        assertThat(result.get("role")).isEqualTo("OWNER");
        verify(homeRepository).save(argThat(h -> h.getName().equals("Căn hộ 1205")));
    }

    @Test
    void updateHome_ThrowsUnauthorized_WhenUserIsNotOwner() {
        HomeMember normalMember = HomeMember.builder()
                .home(home)
                .role(HomeRole.MEMBER)
                .build();

        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(normalMember));

        assertThatThrownBy(() -> homeService.updateHome(userId, homeId, "Tên mới"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        verify(homeRepository, never()).save(any());
    }

    @Test
    void updateHome_ThrowsUnauthorized_WhenUserIsNotMember() {
        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> homeService.updateHome(userId, homeId, "Tên mới"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);

        verify(homeRepository, never()).save(any());
    }

    @Test
    void updateHome_ThrowsHomeNotFound_WhenHomeDoesNotExist() {
        HomeMember ownerMember = HomeMember.builder()
                .home(home)
                .role(HomeRole.OWNER)
                .build();

        when(homeMemberRepository.findByHomeIdAndUserId(homeId, userId))
                .thenReturn(Optional.of(ownerMember));
        when(homeRepository.findById(homeId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> homeService.updateHome(userId, homeId, "Tên mới"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.HOME_NOT_FOUND);

        verify(homeRepository, never()).save(any());
    }
}
