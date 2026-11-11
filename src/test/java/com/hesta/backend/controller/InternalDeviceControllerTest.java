package com.hesta.backend.controller;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.HomeAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalDeviceControllerTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceCommandService deviceCommandService;

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @Mock
    private HomeMemberRepository homeMemberRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private InternalDeviceController controller;

    private UUID deviceId;
    private UUID homeId;
    private Device device;
    private Home home;

    @BeforeEach
    void setUp() {
        deviceId = UUID.randomUUID();
        homeId = UUID.randomUUID();
        home = Home.builder().id(homeId).name("Test Home").build();
        com.hesta.backend.entity.Room room = com.hesta.backend.entity.Room.builder().id(UUID.randomUUID()).home(home).build();
        device = Device.builder().id(deviceId).room(room).name("Light").build();
    }

    @Test
    void executeAction_withXUserId_validatesAccessAndExecutes() {
        UUID userId = UUID.randomUUID();
        CommandResult result = CommandResult.builder().success(true).build();

        when(deviceCommandService.sendCommand(eq(deviceId), eq("turn_on"), any(), eq(StateChangeSource.VOICE)))
                .thenReturn(CompletableFuture.completedFuture(result));

        var responseFuture = controller.executeAction(
                deviceId,
                userId.toString(),
                null,
                Map.of("action", "turn_on", "parameters", Map.of())
        );

        var response = responseFuture.join();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResult().isSuccess()).isTrue();

        verify(homeAuthorizationService).requireDeviceAccess(userId, deviceId, AccessLevel.CONTROL);
        verify(deviceCommandService).sendCommand(eq(deviceId), eq("turn_on"), any(), eq(StateChangeSource.VOICE));
    }

    @Test
    void executeAction_unauthenticated_whenHomeHasCustomMember_throws403() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        HomeMember customMember = HomeMember.builder()
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.MEMBER)
                .accessScope(AccessScope.CUSTOM)
                .build();
        when(homeMemberRepository.findByHomeId(homeId)).thenReturn(List.of(customMember));

        assertThatThrownBy(() -> controller.executeAction(
                deviceId,
                null,
                null,
                Map.of("action", "turn_on", "parameters", Map.of())
        ))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(deviceCommandService, never()).sendCommand(any(), any(), any(), any());
    }

    @Test
    void executeAction_unauthenticated_whenHomeHasOnlyAllMembers_allowsVoiceControl() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));

        HomeMember ownerMember = HomeMember.builder()
                .status(MemberStatus.ACTIVE)
                .role(HomeRole.OWNER)
                .accessScope(AccessScope.ALL)
                .build();
        when(homeMemberRepository.findByHomeId(homeId)).thenReturn(List.of(ownerMember));

        CommandResult result = CommandResult.builder().success(true).build();
        when(deviceCommandService.sendCommand(eq(deviceId), eq("turn_on"), any(), eq(StateChangeSource.VOICE)))
                .thenReturn(CompletableFuture.completedFuture(result));

        var responseFuture = controller.executeAction(
                deviceId,
                null,
                null,
                Map.of("action", "turn_on", "parameters", Map.of())
        );

        var response = responseFuture.join();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResult().isSuccess()).isTrue();
    }
}
