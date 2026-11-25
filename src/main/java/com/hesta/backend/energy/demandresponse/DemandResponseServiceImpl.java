package com.hesta.backend.energy.demandresponse;

import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.dto.command.SensorReadingUpdatedEvent;
import com.hesta.backend.energy.EnergyPowerCalculator;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.HomeEnergySettings;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeEnergySettingsRepository;
import com.hesta.backend.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DemandResponseServiceImpl implements DemandResponseService {

    private static final long AUTO_EVAL_COOLDOWN_MS = 15_000L;

    private final DeviceRepository deviceRepository;

    @Autowired(required = false)
    private HomeEnergySettingsRepository settingsRepository;

    @Autowired
    @Lazy
    private DeviceCommandService deviceCommandService;

    public void setDeviceCommandService(DeviceCommandService deviceCommandService) {
        this.deviceCommandService = deviceCommandService;
    }

    public void setSettingsRepository(HomeEnergySettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    private final ConcurrentHashMap<UUID, DemandResponseSettings> settingsMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastAutoEvalAt = new ConcurrentHashMap<>();

    @Override
    public DemandResponseSettings getSettings(UUID homeId) {
        if (settingsRepository != null) {
            Optional<HomeEnergySettings> stored = settingsRepository.findById(homeId);
            if (stored.isPresent()) {
                DemandResponseSettings mapped = toDto(stored.get());
                settingsMap.put(homeId, mapped);
                return mapped;
            }
        }
        return settingsMap.computeIfAbsent(homeId, id -> DemandResponseSettings.builder()
                .homeId(id)
                .enabled(true)
                .peakThresholdWatts(4000.0)
                .mode("AUTO_SHED")
                .updatedAt(EnergyPowerCalculator.nowVn())
                .build());
    }

    @Override
    public DemandResponseSettings updateSettings(UUID homeId, DemandResponseSettings settings) {
        if (settings == null) {
            return getSettings(homeId);
        }
        DemandResponseSettings current = getSettings(homeId);
        current.setEnabled(settings.isEnabled());
        if (settings.getPeakThresholdWatts() > 0) {
            current.setPeakThresholdWatts(settings.getPeakThresholdWatts());
        }
        if (settings.getMode() != null && !settings.getMode().isBlank()) {
            current.setMode(settings.getMode().toUpperCase());
        }
        current.setUpdatedAt(EnergyPowerCalculator.nowVn());
        settingsMap.put(homeId, current);
        if (settingsRepository != null) {
            HomeEnergySettings entity = HomeEnergySettings.builder()
                    .homeId(homeId)
                    .drEnabled(current.isEnabled())
                    .peakThresholdWatts(BigDecimal.valueOf(current.getPeakThresholdWatts()))
                    .drMode(current.getMode() != null ? current.getMode() : "AUTO_SHED")
                    .billingStartDay(1)
                    .timezone(EnergyPowerCalculator.ENERGY_ZONE.getId())
                    .updatedAt(current.getUpdatedAt())
                    .build();
            settingsRepository.save(entity);
        }
        log.info("Updated Demand Response settings for home [{}]: threshold={}W, mode={}",
                homeId, current.getPeakThresholdWatts(), current.getMode());
        return current;
    }

    private static DemandResponseSettings toDto(HomeEnergySettings entity) {
        return DemandResponseSettings.builder()
                .homeId(entity.getHomeId())
                .enabled(entity.isDrEnabled())
                .peakThresholdWatts(entity.getPeakThresholdWatts() != null ? entity.getPeakThresholdWatts().doubleValue() : 4000.0)
                .mode(entity.getDrMode() != null ? entity.getDrMode() : "AUTO_SHED")
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    @Override
    public DevicePriorityCategory classifyDevicePriority(Device device) {
        if (device == null || device.getDeviceType() == null) {
            return DevicePriorityCategory.LUXURY;
        }

        String type = device.getDeviceType().toUpperCase();
        String name = device.getName() != null ? device.getName().toUpperCase() : "";

        // 1. SAFETY: Cảm biến báo cháy, báo khói, rò rỉ gas, rò nước, khóa an toàn
        if (type.contains("SMOKE") || type.contains("GAS") || type.contains("FIRE") ||
            type.contains("FLAME") ||
            type.contains("LEAK") || type.contains("LOCK") || name.contains("BÁO CHÁY") ||
            name.contains("RÒ RỈ") || name.contains("KHÓA CỬA")) {
            return DevicePriorityCategory.SAFETY;
        }

        // 2. ESSENTIAL: Tủ lạnh, camera an ninh, gateway router, cổng/cửa chính, bộ điều khiển IR và các cảm biến môi trường
        if (type.contains("FRIDGE") || type.contains("CAMERA") || type.contains("ROUTER") ||
            type.contains("GATE") || type.contains("DOOR") || type.contains("SENSOR") ||
            "IR_REMOTE".equals(type) || name.contains("TỦ LẠNH") ||
            name.contains("CAMERA") || name.contains("CỔNG")) {
            return DevicePriorityCategory.ESSENTIAL;
        }

        // 3. COMFORT: Điều hòa, bình nước nóng, máy sưởi, máy bơm nước, quạt
        if (type.contains("AIR_CONDITIONER") || type.equals("AC") || type.startsWith("AC_") || type.endsWith("_AC") ||
            type.contains("HEATER") || type.contains("WATER_HEATER") || type.contains("WATER_PUMP") ||
            type.contains("PUMP") || type.contains("FAN") ||
            name.contains("ĐIỀU HÒA") || name.contains("BÌNH NÓNG LẠNH") ||
            name.contains("MÁY BƠM") || name.contains("QUẠT")) {
            return DevicePriorityCategory.COMFORT;
        }

        // 4. LUXURY: Đèn trang trí, đèn chùm, ổ cắm phụ, đèn RGB
        return DevicePriorityCategory.LUXURY;
    }

    @Override
    public DemandResponseEvaluationResult evaluateAndShed(UUID homeId, Double simulatedWatts) {
        DemandResponseSettings settings = getSettings(homeId);
        List<Device> devices = deviceRepository.findByHomeIdWithRoomAndNode(homeId).stream()
                .filter(d -> !d.isDeleted())
                .toList();

        // Calculate current total power (excluding OFFLINE/UNKNOWN devices)
        double computedWatts = 0.0;
        List<Device> activeDevices = new ArrayList<>();

        for (Device dev : devices) {
            if (isDeviceOn(dev)) {
                activeDevices.add(dev);
                computedWatts += resolveDeviceWattage(dev);
            }
        }

        double totalWatts = (simulatedWatts != null && simulatedWatts > 0) ? simulatedWatts : computedWatts;
        double threshold = settings.getPeakThresholdWatts();
        double excess = Math.max(0.0, totalWatts - threshold);

        if (!settings.isEnabled() || excess <= 0) {
            return DemandResponseEvaluationResult.builder()
                    .homeId(homeId)
                    .totalCurrentWatts(Math.round(totalWatts * 10.0) / 10.0)
                    .peakThresholdWatts(threshold)
                    .excessWatts(0.0)
                    .status("NORMAL")
                    .sheddedDevices(Collections.emptyList())
                    .wattsSaved(0.0)
                    .message("Công suất tiêu thụ an toàn trong hạn mức quy định")
                    .evaluatedAt(EnergyPowerCalculator.nowVn())
                    .build();
        }

        // Peak exceeded
        if ("ALERT_ONLY".equalsIgnoreCase(settings.getMode())) {
            log.warn("Peak load exceeded ({}W > {}W) for home [{}] in ALERT_ONLY mode", totalWatts, threshold, homeId);
            return DemandResponseEvaluationResult.builder()
                    .homeId(homeId)
                    .totalCurrentWatts(Math.round(totalWatts * 10.0) / 10.0)
                    .peakThresholdWatts(threshold)
                    .excessWatts(Math.round(excess * 10.0) / 10.0)
                    .status("WARNING_THRESHOLD")
                    .sheddedDevices(Collections.emptyList())
                    .wattsSaved(0.0)
                    .message("CẢNH BÁO: Công suất tiêu thụ vượt hạn mức đỉnh " + (int) excess + "W")
                    .evaluatedAt(EnergyPowerCalculator.nowVn())
                    .build();
        }

        // AUTO_SHED Mode: Greedy shedding starting with LUXURY, then COMFORT
        List<String> shedded = new ArrayList<>();
        double savedWatts = 0.0;

        // Group active devices by priority
        Map<DevicePriorityCategory, List<Device>> grouped = activeDevices.stream()
                .collect(Collectors.groupingBy(this::classifyDevicePriority));

        List<Device> luxuryCandidates = grouped.getOrDefault(DevicePriorityCategory.LUXURY, Collections.emptyList());
        List<Device> comfortCandidates = grouped.getOrDefault(DevicePriorityCategory.COMFORT, Collections.emptyList());

        // Step 1: Shed LUXURY devices
        for (Device dev : luxuryCandidates) {
            if (savedWatts >= excess) break;
            if (shedDevice(dev)) {
                double w = resolveDeviceWattage(dev);
                savedWatts += w;
                shedded.add(dev.getName() + " [LUXURY: -" + (int) w + "W]");
            }
        }

        // Step 2: If still exceeding, shed COMFORT devices (e.g. water heater, AC)
        if (savedWatts < excess) {
            for (Device dev : comfortCandidates) {
                if (savedWatts >= excess) break;
                if (shedDevice(dev)) {
                    double w = resolveDeviceWattage(dev);
                    savedWatts += w;
                    shedded.add(dev.getName() + " [COMFORT: -" + (int) w + "W]");
                }
            }
        }

        log.info("Demand Response executed for home [{}]. Saved: {}W across {} devices. Remaining excess: {}W",
                homeId, savedWatts, shedded.size(), Math.max(0.0, excess - savedWatts));

        return DemandResponseEvaluationResult.builder()
                .homeId(homeId)
                .totalCurrentWatts(Math.round(totalWatts * 10.0) / 10.0)
                .peakThresholdWatts(threshold)
                .excessWatts(Math.round(excess * 10.0) / 10.0)
                .status("PEAK_SHEDDING_EXECUTED")
                .sheddedDevices(shedded)
                .wattsSaved(Math.round(savedWatts * 10.0) / 10.0)
                .message("Đã tự động ngắt tải " + shedded.size() + " thiết bị để bảo vệ lưới điện gia đình")
                .evaluatedAt(EnergyPowerCalculator.nowVn())
                .build();
    }

    @EventListener
    public void onDeviceStateChanged(DeviceStateChangedEvent event) {
        if (event != null && event.homeId() != null) {
            triggerAutoEvaluation(event.homeId());
        }
    }

    @EventListener
    public void onSensorReadingUpdated(SensorReadingUpdatedEvent event) {
        if (event == null || event.homeId() == null || event.payload() == null) {
            return;
        }
        String metric = event.payload().metricType();
        if (metric != null && (metric.equalsIgnoreCase("POWER") || metric.equalsIgnoreCase("WATT")
                || metric.equalsIgnoreCase("WATTS") || metric.equalsIgnoreCase("ACTIVE_POWER"))) {
            triggerAutoEvaluation(event.homeId());
        }
    }

    private void triggerAutoEvaluation(UUID homeId) {
        long nowMs = System.currentTimeMillis();
        Long lastMs = lastAutoEvalAt.get(homeId);
        if (lastMs != null && nowMs - lastMs < AUTO_EVAL_COOLDOWN_MS) {
            return;
        }
        lastAutoEvalAt.put(homeId, nowMs);
        try {
            DemandResponseSettings settings = getSettings(homeId);
            if (settings.isEnabled()) {
                evaluateAndShed(homeId, null);
            }
        } catch (Exception ex) {
            log.debug("Auto Demand Response check skipped for home [{}]: {}", homeId, ex.getMessage());
        }
    }

    private boolean shedDevice(Device dev) {
        if (deviceCommandService == null || dev == null) return false;
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("power", "OFF");
            params.put("reason", "DEMAND_RESPONSE_LOAD_SHEDDING");
            deviceCommandService.sendCommand(dev.getId(), "TURN_OFF", params, StateChangeSource.AUTOMATION);
            log.info("Dispatched Demand Response SHED (TURN_OFF) to device [{}] ({})", dev.getId(), dev.getName());
            return true;
        } catch (Exception e) {
            log.warn("Failed to shed device [{}]: {}", dev.getId(), e.getMessage());
            return false;
        }
    }

    private boolean isDeviceOn(Device dev) {
        if (dev == null || dev.isDeleted()) return false;
        if (dev.getStatus() == DeviceStatus.OFFLINE || dev.getStatus() == DeviceStatus.UNKNOWN) {
            return false;
        }
        return EnergyPowerCalculator.isStateOn(dev, dev.getCurrentState());
    }

    private double resolveDeviceWattage(Device dev) {
        int baseWattage = EnergyPowerCalculator.resolveBaseWattage(dev);
        return EnergyPowerCalculator.resolveEffectiveWattage(dev, baseWattage, dev != null ? dev.getCurrentState() : null);
    }
}
