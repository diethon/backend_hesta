package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.ResolveAnomalyRequest;
import com.hesta.backend.dto.response.DeviceAnomalyResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.enums.SeverityLevel;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceAnomalyRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.AnomalyDetectionService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceAnomalyManagementServiceImplTest {

    @Mock
    private DeviceAnomalyRepository deviceAnomalyRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @Mock
    private AnomalyDetectionService anomalyDetectionService;

    @InjectMocks
    private DeviceAnomalyManagementServiceImpl service;

    private UUID userId;
    private UUID homeId;
    private UUID deviceId;
    private UUID anomalyId;
    private Home home;
    private Device device;
    private DeviceAnomaly anomaly;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        homeId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        anomalyId = UUID.randomUUID();

        home = Home.builder().id(homeId).name("Smart Home").build();
        device = Device.builder().id(deviceId).name("Sensor 1").deviceType("GAS_SENSOR").isDeleted(false).build();
        device.setRoom(Room.builder().id(UUID.randomUUID()).home(home).name("Living Room").build());

        user = User.builder().id(userId).email("user@example.com").build();

        anomaly = DeviceAnomaly.builder()
                .id(anomalyId)
                .home(home)
                .device(device)
                .anomalyType(AnomalyType.GAS_LEAKAGE)
                .severity(SeverityLevel.HIGH)
                .status(AnomalyStatus.OPEN)
                .detectedAt(OffsetDateTime.now())
                .occurrenceCount(1)
                .build();
    }

    @Test
    @DisplayName("listAnomalies: OWNER / ALL scope queries directly by homeId at DB level")
    void listAnomalies_OwnerOrAllScope_QueriesDbLevel() {
        MemberAccessContext context = new MemberAccessContext(
                homeId, userId, UUID.randomUUID(), HomeRole.OWNER, MemberStatus.ACTIVE,
                AccessScope.ALL, Collections.emptyMap(), Collections.emptyMap()
        );
        when(homeAuthorizationService.loadAccessContext(homeId, userId)).thenReturn(context);

        Pageable pageable = PageRequest.of(0, 10);
        Page<DeviceAnomaly> page = new PageImpl<>(List.of(anomaly), pageable, 1);
        when(deviceAnomalyRepository.findByHomeIdWithFilters(eq(homeId), isNull(), isNull(), eq(pageable)))
                .thenReturn(page);

        Page<DeviceAnomalyResponse> result = service.listAnomalies(userId, homeId, null, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(anomalyId);
        verify(deviceAnomalyRepository).findByHomeIdWithFilters(eq(homeId), isNull(), isNull(), eq(pageable));
        verify(deviceAnomalyRepository, never()).findByHomeIdAndDeviceIdsWithFilters(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("listAnomalies: CUSTOM scope queries only permitted devices at DB level")
    void listAnomalies_CustomScope_QueriesPermittedDevices() {
        MemberAccessContext context = new MemberAccessContext(
                homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                AccessScope.CUSTOM, Collections.emptyMap(), Map.of(deviceId, AccessLevel.VIEW)
        );
        when(homeAuthorizationService.loadAccessContext(homeId, userId)).thenReturn(context);
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(device));
        when(homeAuthorizationService.effectiveLevel(context, device)).thenReturn(AccessLevel.VIEW);

        Pageable pageable = PageRequest.of(0, 10);
        Page<DeviceAnomaly> page = new PageImpl<>(List.of(anomaly), pageable, 1);
        when(deviceAnomalyRepository.findByHomeIdAndDeviceIdsWithFilters(
                eq(homeId), eq(Set.of(deviceId)), isNull(), isNull(), eq(pageable)))
                .thenReturn(page);

        Page<DeviceAnomalyResponse> result = service.listAnomalies(userId, homeId, null, null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(deviceAnomalyRepository).findByHomeIdAndDeviceIdsWithFilters(
                eq(homeId), eq(Set.of(deviceId)), isNull(), isNull(), eq(pageable));
    }

    @Test
    @DisplayName("listAnomalies: CUSTOM scope with no allowed devices returns empty page without DB query")
    void listAnomalies_CustomScope_NoAllowedDevices_ReturnsEmptyPage() {
        MemberAccessContext context = new MemberAccessContext(
                homeId, userId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE,
                AccessScope.CUSTOM, Collections.emptyMap(), Collections.emptyMap()
        );
        when(homeAuthorizationService.loadAccessContext(homeId, userId)).thenReturn(context);
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(device));
        when(homeAuthorizationService.effectiveLevel(context, device)).thenReturn(AccessLevel.NONE);

        Pageable pageable = PageRequest.of(0, 10);
        Page<DeviceAnomalyResponse> result = service.listAnomalies(userId, homeId, null, null, pageable);

        assertThat(result.getTotalElements()).isZero();
        verify(deviceAnomalyRepository, never()).findByHomeIdWithFilters(any(), any(), any(), any());
        verify(deviceAnomalyRepository, never()).findByHomeIdAndDeviceIdsWithFilters(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("listAnomalies: unauthenticated user throws UNAUTHENTICATED")
    void listAnomalies_Unauthenticated_ThrowsException() {
        Pageable pageable = PageRequest.of(0, 10);
        assertThatThrownBy(() -> service.listAnomalies(null, homeId, null, null, pageable))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getErrorCode() == ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("acknowledgeAnomaly: OPEN anomaly transitions to ACKNOWLEDGED")
    void acknowledgeAnomaly_OpenStatus_Success() {
        when(deviceAnomalyRepository.findByIdAndHomeId(anomalyId, homeId)).thenReturn(Optional.of(anomaly));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceAnomalyRepository.save(any(DeviceAnomaly.class))).thenAnswer(i -> i.getArgument(0));

        DeviceAnomalyResponse response = service.acknowledgeAnomaly(userId, homeId, anomalyId);

        assertThat(response.getStatus()).isEqualTo(AnomalyStatus.ACKNOWLEDGED);
        assertThat(response.getAcknowledgedBy()).isEqualTo(userId);
        assertThat(response.getAcknowledgedAt()).isNotNull();
        verify(homeAuthorizationService).requireDeviceAccess(userId, deviceId, AccessLevel.VIEW);
    }

    @Test
    @DisplayName("acknowledgeAnomaly: already ACKNOWLEDGED is idempotent and does not modify state")
    void acknowledgeAnomaly_AlreadyAcknowledged_Idempotent() {
        anomaly.setStatus(AnomalyStatus.ACKNOWLEDGED);
        OffsetDateTime originalAckTime = OffsetDateTime.now().minusHours(1);
        anomaly.setAcknowledgedAt(originalAckTime);
        anomaly.setAcknowledgedBy(user);

        when(deviceAnomalyRepository.findByIdAndHomeId(anomalyId, homeId)).thenReturn(Optional.of(anomaly));

        DeviceAnomalyResponse response = service.acknowledgeAnomaly(userId, homeId, anomalyId);

        assertThat(response.getStatus()).isEqualTo(AnomalyStatus.ACKNOWLEDGED);
        assertThat(response.getAcknowledgedAt()).isEqualTo(originalAckTime);
        verify(deviceAnomalyRepository, never()).save(any());
    }

    @Test
    @DisplayName("acknowledgeAnomaly: already RESOLVED throws ANOMALY_INVALID_STATUS")
    void acknowledgeAnomaly_AlreadyResolved_ThrowsInvalidStatus() {
        anomaly.setStatus(AnomalyStatus.RESOLVED);
        when(deviceAnomalyRepository.findByIdAndHomeId(anomalyId, homeId)).thenReturn(Optional.of(anomaly));

        assertThatThrownBy(() -> service.acknowledgeAnomaly(userId, homeId, anomalyId))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getErrorCode() == ErrorCode.ANOMALY_INVALID_STATUS);

        verify(deviceAnomalyRepository, never()).save(any());
    }

    @Test
    @DisplayName("acknowledgeAnomaly: cross-home or not found throws ANOMALY_NOT_FOUND (404)")
    void acknowledgeAnomaly_NotFound_Throws404() {
        when(deviceAnomalyRepository.findByIdAndHomeId(anomalyId, homeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledgeAnomaly(userId, homeId, anomalyId))
                .isInstanceOf(AppException.class)
                .matches(e -> ((AppException) e).getErrorCode() == ErrorCode.ANOMALY_NOT_FOUND);
    }

    @Test
    @DisplayName("resolveAnomaly: transitions to RESOLVED and records manual resolution cooldown")
    void resolveAnomaly_OpenStatus_Success() {
        when(deviceAnomalyRepository.findByIdAndHomeId(anomalyId, homeId)).thenReturn(Optional.of(anomaly));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(deviceAnomalyRepository.save(any(DeviceAnomaly.class))).thenAnswer(i -> i.getArgument(0));

        ResolveAnomalyRequest request = new ResolveAnomalyRequest(true, "Fixed the leak and verified safe");
        DeviceAnomalyResponse response = service.resolveAnomaly(userId, homeId, anomalyId, request);

        assertThat(response.getStatus()).isEqualTo(AnomalyStatus.RESOLVED);
        assertThat(response.isFalsePositive()).isTrue();
        assertThat(response.getResolutionNote()).isEqualTo("Fixed the leak and verified safe");
        assertThat(response.getResolvedBy()).isEqualTo(userId);
        assertThat(response.getResolvedAt()).isNotNull();

        verify(homeAuthorizationService).requireDeviceAccess(userId, deviceId, AccessLevel.CONTROL);
        verify(anomalyDetectionService).recordManualResolution(deviceId, AnomalyType.GAS_LEAKAGE);
    }

    @Test
    @DisplayName("resolveAnomaly: already RESOLVED is idempotent and does not overwrite timestamps")
    void resolveAnomaly_AlreadyResolved_Idempotent() {
        anomaly.setStatus(AnomalyStatus.RESOLVED);
        OffsetDateTime originalResolvedTime = OffsetDateTime.now().minusHours(2);
        anomaly.setResolvedAt(originalResolvedTime);
        anomaly.setResolvedBy(user);

        when(deviceAnomalyRepository.findByIdAndHomeId(anomalyId, homeId)).thenReturn(Optional.of(anomaly));

        ResolveAnomalyRequest request = new ResolveAnomalyRequest(false, "New note");
        DeviceAnomalyResponse response = service.resolveAnomaly(userId, homeId, anomalyId, request);

        assertThat(response.getStatus()).isEqualTo(AnomalyStatus.RESOLVED);
        assertThat(response.getResolvedAt()).isEqualTo(originalResolvedTime);
        verify(deviceAnomalyRepository, never()).save(any());
        verify(anomalyDetectionService, never()).recordManualResolution(any(), any());
    }
}
