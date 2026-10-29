package com.hesta.backend.security;

import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceAccessValidatorTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private RoomRepository roomRepository;

    private DeviceAccessValidator validator;

    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();

    private CustomUserDetails userDetails;

    @BeforeEach
    void setUp() {
        validator = new DeviceAccessValidator(deviceRepository, roomRepository);
        userDetails = new CustomUserDetails(
                userId,
                "user@example.com",
                "password",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    @Test
    @DisplayName("canAccessDevice: returns true when repository says user has access")
    void canAccessDevice_withUserId_hasAccess() {
        when(deviceRepository.hasAccessToDevice(deviceId, userId)).thenReturn(true);
        assertTrue(validator.canAccessDevice(userId, deviceId));
        verify(deviceRepository).hasAccessToDevice(deviceId, userId);
    }

    @Test
    @DisplayName("canAccessDevice: returns false when userId or deviceId is null")
    void canAccessDevice_withNullArgs_returnsFalse() {
        assertFalse(validator.canAccessDevice((UUID) null, deviceId));
        assertFalse(validator.canAccessDevice(userId, (UUID) null));
    }

    @Test
    @DisplayName("canAccessDevice: with CustomUserDetails principal delegates with user id")
    void canAccessDevice_withCustomUserDetails_delegates() {
        when(deviceRepository.hasAccessToDevice(deviceId, userId)).thenReturn(true);
        assertTrue(validator.canAccessDevice((Object) userDetails, deviceId));
    }

    @Test
    @DisplayName("canAccessDevice: with anonymous String principal returns false without error")
    void canAccessDevice_withAnonymousString_returnsFalse() {
        assertFalse(validator.canAccessDevice("anonymousUser", deviceId));
    }

    @Test
    @DisplayName("canAccessDevice: with null principal returns false")
    void canAccessDevice_withNullPrincipal_returnsFalse() {
        assertFalse(validator.canAccessDevice((Object) null, deviceId));
    }

    @Test
    @DisplayName("canAccessRoom: returns true when repository says user has access")
    void canAccessRoom_withUserId_hasAccess() {
        when(roomRepository.hasAccessToRoom(roomId, userId)).thenReturn(true);
        assertTrue(validator.canAccessRoom(userId, roomId));
        verify(roomRepository).hasAccessToRoom(roomId, userId);
    }

    @Test
    @DisplayName("canAccessRoom: returns false when userId or roomId is null")
    void canAccessRoom_withNullArgs_returnsFalse() {
        assertFalse(validator.canAccessRoom((UUID) null, roomId));
        assertFalse(validator.canAccessRoom(userId, (UUID) null));
    }

    @Test
    @DisplayName("canAccessRoom: with CustomUserDetails principal delegates with user id")
    void canAccessRoom_withCustomUserDetails_delegates() {
        when(roomRepository.hasAccessToRoom(roomId, userId)).thenReturn(true);
        assertTrue(validator.canAccessRoom((Object) userDetails, roomId));
    }

    @Test
    @DisplayName("canAccessRoom: with anonymous String principal returns false without error")
    void canAccessRoom_withAnonymousString_returnsFalse() {
        assertFalse(validator.canAccessRoom("anonymousUser", roomId));
    }

    @Test
    @DisplayName("canAccessRoom: with null principal returns false")
    void canAccessRoom_withNullPrincipal_returnsFalse() {
        assertFalse(validator.canAccessRoom((Object) null, roomId));
    }
}
