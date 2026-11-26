package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.dto.response.DeviceHealthResponse;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceAnomalyRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.repository.MaintenanceTicketRepository;
import com.hesta.backend.service.DeviceHealthService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceDiagnosticContextServiceTest {

    @Mock private HomeAuthorizationService homeAuthorizationService;
    @Mock private DeviceRepository deviceRepository;
    @Mock private DeviceHealthService deviceHealthService;
    @Mock private DeviceAnomalyRepository deviceAnomalyRepository;
    @Mock private DeviceStateHistoryRepository deviceStateHistoryRepository;
    @Mock private MaintenanceTicketRepository maintenanceTicketRepository;

    private DeviceDiagnosticContextService service;
    private final Instant now = Instant.parse("2026-11-23T08:00:00Z");
    private final UUID homeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private Device device;
    private MemberAccessContext access;

    @BeforeEach
    void setUp() {
        service = new DeviceDiagnosticContextService(
                homeAuthorizationService, deviceRepository, deviceHealthService,
                deviceAnomalyRepository, deviceStateHistoryRepository, maintenanceTicketRepository,
                Clock.fixed(now, ZoneOffset.UTC));

        Home home = new Home();
        home.setId(homeId);
        Room room = new Room();
        room.setId(UUID.randomUUID());
        room.setName("Phòng khách");
        room.setHome(home);

        device = new Device();
        device.setId(deviceId);
        device.setName("Đèn trần");
        device.setDeviceType("LIGHT");
        device.setModel("HESTA-L1");
        device.setRoom(room);
        device.setStatus(DeviceStatus.OFFLINE);
        device.setLastSeen(OffsetDateTime.ofInstant(now, ZoneOffset.UTC).minusMinutes(135));
        device.setCurrentState(Map.of("power", "OFF"));

        access = new MemberAccessContext(homeId, userId, UUID.randomUUID(), HomeRole.MEMBER,
                MemberStatus.ACTIVE, AccessScope.CUSTOM, Map.of(), Map.of());
        when(homeAuthorizationService.loadAccessContext(homeId, userId)).thenReturn(access);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
    }

    @Test
    void buildsFullDiagnosticSnapshotWithOfflineDurationHealthAnomaliesAndActiveTicket() {
        when(homeAuthorizationService.effectiveLevel(access, device)).thenReturn(AccessLevel.VIEW);
        when(deviceHealthService.getDeviceHealth(deviceId)).thenReturn(DeviceHealthResponse.builder()
                .healthPercent(98.5).switchCycles(1500).chatteringWarning(false).safetyTripped(false).build());
        when(deviceAnomalyRepository.findByDeviceIdAndStatusIn(eq(deviceId), any())).thenReturn(List.of(
                DeviceAnomaly.builder().id(UUID.randomUUID()).anomalyType(AnomalyType.SENSOR_OFFLINE)
                        .severity(SeverityLevel.MEDIUM).reason("Mất tín hiệu 5 phút").build()));
        when(deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId)).thenReturn(List.of(
                DeviceStateHistory.builder().source(StateChangeSource.MANUAL).newState(Map.of("power", "OFF")).build()));
        MaintenanceTicket active = new MaintenanceTicket();
        active.setId(UUID.randomUUID());
        active.setCode("MT-261123-ABC123");
        active.setStatus(MaintenanceTicketStatus.SUBMITTED);
        active.setTitle("Kiểm tra Đèn trần");
        when(maintenanceTicketRepository.findTicketsByDeviceInStatuses(eq(deviceId), any())).thenReturn(List.of(active));

        DeviceDiagnosticContext ctx = service.buildContext(homeId, deviceId, userId);
        assertThat(ctx.offlineDurationMinutes()).isEqualTo(135L);
        assertThat(ctx.offlineDurationLabel()).isEqualTo("2 giờ 15 phút");
        assertThat(ctx.health().healthPercent()).isEqualTo(98.5);
        assertThat(ctx.openAnomalies()).hasSize(1);
        assertThat(ctx.recentHistory()).hasSize(1);
        assertThat(ctx.activeTicket().code()).isEqualTo("MT-261123-ABC123");
    }

    @Test
    void rejectsDeviceWithoutAccessLevel() {
        when(homeAuthorizationService.effectiveLevel(access, device)).thenReturn(AccessLevel.NONE);

        assertThatThrownBy(() -> service.buildContext(homeId, deviceId, userId))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DEVICE_NOT_FOUND);
    }

    @Test
    void detectsHomeWideOutageWhenAtLeast70PercentOfflineAndAtLeast3Devices() {
        when(homeAuthorizationService.effectiveLevel(access, device)).thenReturn(AccessLevel.VIEW);
        Device d2 = new Device(); d2.setId(UUID.randomUUID()); d2.setStatus(DeviceStatus.OFFLINE);
        Device d3 = new Device(); d3.setId(UUID.randomUUID()); d3.setStatus(DeviceStatus.OFFLINE);
        Device d4 = new Device(); d4.setId(UUID.randomUUID()); d4.setStatus(DeviceStatus.ONLINE);
        // 3 of 4 offline = 75% >= 70% -> homeWideOutage = true
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(device, d2, d3, d4));

        DeviceDiagnosticContext outageCtx = service.buildContext(homeId, deviceId, userId);
        assertThat(outageCtx.homeWideOutage()).isTrue();
        assertThat(outageCtx.homeTotalDevices()).isEqualTo(4);
        assertThat(outageCtx.homeOfflineDevices()).isEqualTo(3);

        // All 4 devices offline (100% >= 70%) -> homeWideOutage = true
        d4.setStatus(DeviceStatus.OFFLINE);
        DeviceDiagnosticContext allOfflineCtx = service.buildContext(homeId, deviceId, userId);
        assertThat(allOfflineCtx.homeWideOutage()).isTrue();
        assertThat(allOfflineCtx.homeTotalDevices()).isEqualTo(4);
        assertThat(allOfflineCtx.homeOfflineDevices()).isEqualTo(4);

        // Only 2 devices total (100% offline) -> < 3 devices -> homeWideOutage = false
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(device, d2));
        assertThat(service.buildContext(homeId, deviceId, userId).homeWideOutage()).isFalse();
    }
}
