package com.hesta.backend.service;

import com.hesta.backend.dto.request.AdminCreateDeviceRequest;
import com.hesta.backend.dto.request.DeviceClaimRequest;
import com.hesta.backend.dto.response.AdminDeviceCreatedResponse;
import com.hesta.backend.dto.response.DevicePublicPreviewResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.dto.response.QrInfoResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.impl.DeviceOnboardingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceOnboardingServiceTest {

    @Mock DeviceRepository deviceRepository;
    @Mock DeviceRegistrationTokenRepository tokenRepository;
    @Mock EdgeNodeRepository edgeNodeRepository;
    @Mock RoomRepository roomRepository;
    @Mock UserRepository userRepository;
    @Mock HomeAuthorizationService homeAuthorizationService;

    @InjectMocks DeviceOnboardingServiceImpl onboardingService;

    private UUID adminId;
    private UUID userId;
    private UUID homeId;
    private UUID roomId;
    private UUID deviceId;
    private Home testHome;
    private Room testRoom;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        userId = UUID.randomUUID();
        homeId = UUID.randomUUID();
        roomId = UUID.randomUUID();
        deviceId = UUID.randomUUID();

        testHome = Home.builder().id(homeId).name("My Smart Home").build();
        testRoom = Room.builder().id(roomId).name("Living Room").home(testHome).build();
    }

    @Test
    @DisplayName("Admin create device successfully and generates QR")
    void adminCreateDevice_Success() {
        AdminCreateDeviceRequest request = AdminCreateDeviceRequest.builder()
                .name("SYNA RGB Light")
                .deviceType("LIGHT")
                .model("SYNA-RGB-001")
                .serialNumber("SYNA-RGB-001-0001")
                .build();

        when(deviceRepository.existsBySerialNumber("SYNA-RGB-001-0001")).thenReturn(false);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(User.builder().id(adminId).build()));
        when(deviceRepository.save(any(Device.class))).thenAnswer(invocation -> {
            Device d = invocation.getArgument(0);
            d.setId(deviceId);
            return d;
        });

        AdminDeviceCreatedResponse response = onboardingService.adminCreateDevice(adminId, request);

        assertThat(response).isNotNull();
        assertThat(response.getDevice()).isNotNull();
        assertThat(response.getDevice().getName()).isEqualTo("SYNA RGB Light");
        assertThat(response.getDevice().getDeviceType()).isEqualTo("LIGHT");
        assertThat(response.getDevice().getModel()).isEqualTo("SYNA-RGB-001");
        assertThat(response.getDevice().getSerialNumber()).isEqualTo("SYNA-RGB-001-0001");
        assertThat(response.getDevice().getStatus()).isEqualTo(DeviceStatus.UNCLAIMED.name());

        assertThat(response.getQr()).isNotNull();
        assertThat(response.getQr().getPayload()).startsWith("syna://device/register?token=");
        assertThat(response.getQr().getToken()).isNotBlank();
        assertThat(response.getQr().isSingleUse()).isTrue();

        verify(tokenRepository).save(any(DeviceRegistrationToken.class));
    }

    @Test
    @DisplayName("Admin create device with duplicate serial throws error")
    void adminCreateDevice_DuplicateSerial_ThrowsError() {
        AdminCreateDeviceRequest request = AdminCreateDeviceRequest.builder()
                .name("SYNA RGB Light")
                .deviceType("LIGHT")
                .serialNumber("SYNA-RGB-001-0001")
                .build();

        when(deviceRepository.existsBySerialNumber("SYNA-RGB-001-0001")).thenReturn(true);

        assertThatThrownBy(() -> onboardingService.adminCreateDevice(adminId, request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DEVICE_SERIAL_EXISTS);

        verify(deviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Resolve valid QR token returns preview info")
    void resolveQrToken_ValidToken_Success() {
        String rawToken = "sample-secret-token-12345";
        Device device = Device.builder()
                .id(deviceId)
                .name("SYNA RGB Light")
                .deviceType("LIGHT")
                .model("SYNA-RGB-001")
                .serialNumber("SYNA-RGB-001-0001")
                .status(DeviceStatus.UNCLAIMED)
                .build();

        DeviceRegistrationToken token = DeviceRegistrationToken.builder()
                .device(device)
                .expiresAt(OffsetDateTime.now().plusDays(2))
                .build();

        when(tokenRepository.findByTokenHashWithDevice(anyString())).thenReturn(Optional.of(token));

        DevicePublicPreviewResponse preview = onboardingService.resolveQrToken(rawToken);

        assertThat(preview.getDeviceId()).isEqualTo(deviceId);
        assertThat(preview.getName()).isEqualTo("SYNA RGB Light");
        assertThat(preview.getDeviceType()).isEqualTo("LIGHT");
        assertThat(preview.getModel()).isEqualTo("SYNA-RGB-001");
        assertThat(preview.getStatus()).isEqualTo(DeviceStatus.UNCLAIMED.name());
    }

    @Test
    @DisplayName("Resolve expired QR token throws QR_TOKEN_EXPIRED")
    void resolveQrToken_Expired_ThrowsError() {
        String rawToken = "expired-token";
        Device device = Device.builder().id(deviceId).status(DeviceStatus.UNCLAIMED).build();
        DeviceRegistrationToken token = DeviceRegistrationToken.builder()
                .device(device)
                .expiresAt(OffsetDateTime.now().minusHours(1))
                .build();

        when(tokenRepository.findByTokenHashWithDevice(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> onboardingService.resolveQrToken(rawToken))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QR_TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("Resolve already used QR token throws QR_TOKEN_USED")
    void resolveQrToken_Used_ThrowsError() {
        String rawToken = "used-token";
        Device device = Device.builder().id(deviceId).status(DeviceStatus.UNCLAIMED).build();
        DeviceRegistrationToken token = DeviceRegistrationToken.builder()
                .device(device)
                .expiresAt(OffsetDateTime.now().plusDays(1))
                .usedAt(OffsetDateTime.now().minusMinutes(5))
                .build();

        when(tokenRepository.findByTokenHashWithDevice(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> onboardingService.resolveQrToken(rawToken))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QR_TOKEN_USED);
    }

    @Test
    @DisplayName("User claim device successfully into Home and Room with EdgeNode")
    void claimDevice_Success() {
        String rawToken = "valid-claim-token";
        Device device = Device.builder()
                .id(deviceId)
                .name("SYNA RGB Light")
                .deviceType("LIGHT")
                .localId("dev-rgb-1")
                .status(DeviceStatus.UNCLAIMED)
                .build();

        DeviceRegistrationToken token = DeviceRegistrationToken.builder()
                .device(device)
                .expiresAt(OffsetDateTime.now().plusDays(2))
                .build();

        when(tokenRepository.findByTokenHashWithDevice(anyString())).thenReturn(Optional.of(token));
        when(homeAuthorizationService.requireAccess(userId, homeId)).thenReturn(testHome);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(testRoom));
        when(roomRepository.hasAccessToRoom(roomId, userId)).thenReturn(true);
        when(edgeNodeRepository.findByHomeIdAndNodeCode(homeId, "esp32-001")).thenReturn(Optional.empty());
        when(edgeNodeRepository.findByNodeCode("esp32-001")).thenReturn(Optional.empty());
        when(edgeNodeRepository.save(any(EdgeNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));

        DeviceClaimRequest request = DeviceClaimRequest.builder()
                .token(rawToken)
                .homeId(homeId)
                .roomId(roomId)
                .name("Living Room RGB Light")
                .nodeCode("esp32-001")
                .build();

        DeviceResponse response = onboardingService.claimDevice(userId, deviceId, request);

        assertThat(response).isNotNull();
        assertThat(response.getName()).isEqualTo("Living Room RGB Light");
        assertThat(response.getRoomId()).isEqualTo(roomId);
        assertThat(response.getStatus()).isEqualTo(DeviceStatus.ONLINE.name());
        assertThat(response.getMqttTopic()).isEqualTo("hesta/nodes/esp32-001/devices/dev-rgb-1/state");

        assertThat(token.getUsedAt()).isNotNull();
        verify(tokenRepository).save(token);
    }

    @Test
    @DisplayName("User claim device already claimed throws DEVICE_ALREADY_CLAIMED")
    void claimDevice_AlreadyClaimed_ThrowsError() {
        String rawToken = "valid-token";
        Device device = Device.builder()
                .id(deviceId)
                .status(DeviceStatus.ONLINE)
                .build();

        DeviceRegistrationToken token = DeviceRegistrationToken.builder()
                .device(device)
                .expiresAt(OffsetDateTime.now().plusDays(2))
                .build();

        when(tokenRepository.findByTokenHashWithDevice(anyString())).thenReturn(Optional.of(token));

        DeviceClaimRequest request = DeviceClaimRequest.builder()
                .token(rawToken)
                .homeId(homeId)
                .roomId(roomId)
                .build();

        assertThatThrownBy(() -> onboardingService.claimDevice(userId, deviceId, request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DEVICE_ALREADY_CLAIMED);
    }

    @Test
    @DisplayName("User claim with unauthorized home throws error")
    void claimDevice_UnauthorizedHome_ThrowsError() {
        String rawToken = "valid-token";
        Device device = Device.builder()
                .id(deviceId)
                .status(DeviceStatus.UNCLAIMED)
                .build();

        DeviceRegistrationToken token = DeviceRegistrationToken.builder()
                .device(device)
                .expiresAt(OffsetDateTime.now().plusDays(2))
                .build();

        when(tokenRepository.findByTokenHashWithDevice(anyString())).thenReturn(Optional.of(token));
        when(homeAuthorizationService.requireAccess(userId, homeId))
                .thenThrow(new AppException(ErrorCode.UNAUTHORIZED));

        DeviceClaimRequest request = DeviceClaimRequest.builder()
                .token(rawToken)
                .homeId(homeId)
                .roomId(roomId)
                .build();

        assertThatThrownBy(() -> onboardingService.claimDevice(userId, deviceId, request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Regenerate QR invalidates old tokens and creates new one")
    void regenerateQr_InvalidatesOldTokens() {
        Device device = Device.builder().id(deviceId).status(DeviceStatus.UNCLAIMED).build();
        DeviceRegistrationToken oldToken = DeviceRegistrationToken.builder()
                .device(device)
                .expiresAt(OffsetDateTime.now().plusDays(1))
                .build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(tokenRepository.findActiveTokensByDeviceId(deviceId)).thenReturn(List.of(oldToken));

        QrInfoResponse newQr = onboardingService.regenerateQr(adminId, deviceId);

        assertThat(oldToken.getRevokedAt()).isNotNull();
        verify(tokenRepository).saveAll(any());
        verify(tokenRepository).save(any(DeviceRegistrationToken.class));
        assertThat(newQr.getToken()).isNotBlank();
        assertThat(newQr.getPayload()).startsWith("syna://device/register?token=");
    }
}
