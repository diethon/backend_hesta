package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.dto.response.DeviceHealthResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.entity.MaintenanceTicket;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.MaintenanceTicketStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceAnomalyRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.repository.MaintenanceTicketRepository;
import com.hesta.backend.service.DeviceHealthService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceDiagnosticContextService {

    private static final int MAX_RECENT_HISTORY = 8;
    private static final Set<AnomalyStatus> OPEN_ANOMALY_STATUSES = EnumSet.of(
            AnomalyStatus.OPEN, AnomalyStatus.ACKNOWLEDGED);

    private final HomeAuthorizationService homeAuthorizationService;
    private final DeviceRepository deviceRepository;
    private final DeviceHealthService deviceHealthService;
    private final DeviceAnomalyRepository deviceAnomalyRepository;
    private final DeviceStateHistoryRepository deviceStateHistoryRepository;
    private final MaintenanceTicketRepository maintenanceTicketRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DeviceDiagnosticContext buildContext(UUID homeId, UUID deviceId, UUID userId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext access = homeAuthorizationService.loadAccessContext(homeId, userId);

        Device device = deviceRepository.findById(deviceId)
                .filter(d -> !d.isDeleted())
                .filter(d -> d.getHome() != null && homeId.equals(d.getHome().getId()))
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        if (homeAuthorizationService.effectiveLevel(access, device) == AccessLevel.NONE) {
            throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        Long offlineMinutes = null;
        String offlineLabel = null;
        if (device.getStatus() == DeviceStatus.OFFLINE && device.getLastSeen() != null) {
            long mins = Math.max(0L, Duration.between(device.getLastSeen(), now).toMinutes());
            offlineMinutes = mins;
            offlineLabel = formatDurationVi(mins);
        }

        DeviceDiagnosticContext.HealthSnapshot healthSnapshot = null;
        try {
            DeviceHealthResponse health = deviceHealthService.getDeviceHealth(deviceId);
            if (health != null) {
                healthSnapshot = new DeviceDiagnosticContext.HealthSnapshot(
                        health.getHealthPercent() != null ? health.getHealthPercent() : 100.0,
                        health.getSwitchCycles() != null ? health.getSwitchCycles() : 0,
                        Boolean.TRUE.equals(health.getChatteringWarning()),
                        Boolean.TRUE.equals(health.getSafetyTripped())
                );
            }
        } catch (Exception e) {
            log.debug("Could not load DeviceHealth for {}: {}", deviceId, e.getMessage());
        }

        List<DeviceAnomaly> openAnomalies = deviceAnomalyRepository.findByDeviceIdAndStatusIn(
                deviceId, OPEN_ANOMALY_STATUSES);
        List<DeviceDiagnosticContext.AnomalySnapshot> anomalySnapshots = openAnomalies.stream()
                .map(a -> new DeviceDiagnosticContext.AnomalySnapshot(
                        a.getId(),
                        a.getAnomalyType() != null ? a.getAnomalyType().name() : null,
                        a.getSeverity() != null ? a.getSeverity().name() : null,
                        a.getReason(),
                        a.getDetectedAt()))
                .toList();

        List<DeviceStateHistory> histories = deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId);
        List<DeviceDiagnosticContext.HistorySnapshot> recentHistory = histories == null
                ? List.of()
                : histories.stream()
                .limit(MAX_RECENT_HISTORY)
                .map(h -> new DeviceDiagnosticContext.HistorySnapshot(
                        h.getSource() != null ? h.getSource().name() : null,
                        h.getNewState(),
                        h.getChangedAt()))
                .toList();

        List<MaintenanceTicket> activeTickets = maintenanceTicketRepository.findTicketsByDeviceInStatuses(
                deviceId, MaintenanceTicketStatus.ACTIVE);
        DeviceDiagnosticContext.ActiveTicketSnapshot activeTicket = activeTickets.isEmpty()
                ? null
                : new DeviceDiagnosticContext.ActiveTicketSnapshot(
                activeTickets.get(0).getId(),
                activeTickets.get(0).getCode(),
                activeTickets.get(0).getStatus(),
                activeTickets.get(0).getTitle());

        List<Device> homeDevices = deviceRepository.findByHomeIdWithRoomAndNode(homeId);
        int homeTotal = 0;
        int homeOffline = 0;
        if (homeDevices != null) {
            for (Device d : homeDevices) {
                if (!d.isDeleted() && d.getStatus() != DeviceStatus.UNCLAIMED) {
                    homeTotal++;
                    if (d.getStatus() == DeviceStatus.OFFLINE) {
                        homeOffline++;
                    }
                }
            }
        }
        boolean homeWideOutage = homeTotal >= 3 && (homeOffline * 100 >= homeTotal * 70);

        return new DeviceDiagnosticContext(
                device.getId(),
                device.getName() != null ? device.getName() : device.getDeviceType(),
                device.getDeviceType(),
                device.getModel(),
                device.getRoom() != null ? device.getRoom().getName() : null,
                device.getStatus(),
                device.getLastSeen(),
                offlineMinutes,
                offlineLabel,
                summarizeState(device.getCurrentState()),
                healthSnapshot,
                anomalySnapshots,
                recentHistory,
                activeTicket,
                homeWideOutage,
                homeTotal,
                homeOffline
        );
    }

    @Transactional(readOnly = true)
    public List<Device> listAccessibleDevices(UUID homeId, UUID userId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        MemberAccessContext access = homeAuthorizationService.loadAccessContext(homeId, userId);
        return deviceRepository.findByHomeIdWithRoomAndNode(homeId).stream()
                .filter(d -> !d.isDeleted())
                .filter(d -> d.getStatus() != DeviceStatus.UNCLAIMED)
                .filter(d -> homeAuthorizationService.effectiveLevel(access, d) != AccessLevel.NONE)
                .sorted(Comparator.comparing(d -> d.getName() != null ? d.getName() : ""))
                .toList();
    }

    public static String summarizeState(Map<String, Object> state) {
        if (state == null || state.isEmpty()) {
            return "Chưa có dữ liệu trạng thái";
        }
        return state.entrySet().stream()
                .limit(5)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    public static String formatDurationVi(long minutes) {
        if (minutes < 60) {
            return minutes + " phút";
        }
        long hours = minutes / 60;
        long remMinutes = minutes % 60;
        if (hours < 24) {
            return remMinutes > 0 ? hours + " giờ " + remMinutes + " phút" : hours + " giờ";
        }
        long days = hours / 24;
        long remHours = hours % 24;
        return remHours > 0 ? days + " ngày " + remHours + " giờ" : days + " ngày";
    }
}
