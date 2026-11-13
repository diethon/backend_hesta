package com.hesta.backend.energy.demandresponse;

import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DemandResponseServiceImpl implements DemandResponseService {

    private final DeviceRepository deviceRepository;

    @Autowired
    @Lazy
    private DeviceCommandService deviceCommandService;

    public void setDeviceCommandService(DeviceCommandService deviceCommandService) {
        this.deviceCommandService = deviceCommandService;
    }

    private final ConcurrentHashMap<UUID, DemandResponseSettings> settingsMap = new ConcurrentHashMap<>();

    @Override
    public DemandResponseSettings getSettings(UUID homeId) {
        return settingsMap.computeIfAbsent(homeId, id -> DemandResponseSettings.builder()
                .homeId(id)
                .enabled(true)
                .peakThresholdWatts(4000.0)
                .mode("AUTO_SHED")
                .updatedAt(OffsetDateTime.now())
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
        current.setUpdatedAt(OffsetDateTime.now());
        settingsMap.put(homeId, current);
        log.info("Updated Demand Response settings for home [{}]: threshold={}W, mode={}",
                homeId, current.getPeakThresholdWatts(), current.getMode());
        return current;
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
            type.contains("LEAK") || type.contains("LOCK") || name.contains("BÁO CHÁY") ||
            name.contains("RÒ RỈ") || name.contains("KHÓA CỬA")) {
            return DevicePriorityCategory.SAFETY;
        }

        // 2. ESSENTIAL: Tủ lạnh, camera an ninh, gateway router, cổng chính
        if (type.contains("FRIDGE") || type.contains("CAMERA") || type.contains("ROUTER") ||
            type.contains("GATE") || type.contains("DOOR") || name.contains("TỦ LẠNH") ||
            name.contains("CAMERA") || name.contains("CỔNG")) {
            return DevicePriorityCategory.ESSENTIAL;
        }

        // 3. COMFORT: Điều hòa không khí, bình nước nóng, máy sưởi, quạt
        if (type.contains("AIR_CONDITIONER") || type.equals("AC") || type.startsWith("AC_") || type.endsWith("_AC") ||
            type.contains("HEATER") || type.contains("WATER_HEATER") || type.contains("FAN") ||
            name.contains("ĐIỀU HÒA") || name.contains("BÌNH NÓNG LẠNH") || name.contains("QUẠT")) {
            return DevicePriorityCategory.COMFORT;
        }

        // 4. LUXURY: Đèn trang trí, đèn chùm, ổ cắm phụ, đèn RGB
        return DevicePriorityCategory.LUXURY;
    }

    @Override
    public DemandResponseEvaluationResult evaluateAndShed(UUID homeId, Double simulatedWatts) {
        DemandResponseSettings settings = getSettings(homeId);
        List<Device> devices = deviceRepository.findByHomeIdWithRoomAndNode(homeId);

        // Calculate current total power
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
                    .evaluatedAt(OffsetDateTime.now())
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
                    .evaluatedAt(OffsetDateTime.now())
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
                .evaluatedAt(OffsetDateTime.now())
                .build();
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
        if (dev == null || dev.getCurrentState() == null) return false;
        Object p = dev.getCurrentState().get("power");
        if (p != null) {
            String s = p.toString().trim();
            return "ON".equalsIgnoreCase(s) || "TRUE".equalsIgnoreCase(s) || "1".equals(s);
        }
        Object st = dev.getCurrentState().get("state");
        if (st != null) {
            String s = st.toString().trim();
            return "OPEN".equalsIgnoreCase(s) || "RUNNING".equalsIgnoreCase(s) || "OPENING".equalsIgnoreCase(s);
        }
        return false;
    }

    private double resolveDeviceWattage(Device dev) {
        if (dev == null) return 60.0;
        if (dev.getSettings() != null && dev.getSettings().get("wattage") != null) {
            try {
                return Double.parseDouble(dev.getSettings().get("wattage").toString().trim());
            } catch (Exception ignored) {}
        }
        String type = dev.getDeviceType() != null ? dev.getDeviceType().toUpperCase() : "";
        if (type.contains("AIR_CONDITIONER") || type.contains("AC")) return 1800.0;
        if (type.contains("WATER_HEATER") || type.contains("HEATER")) return 2500.0;
        if (type.contains("FRIDGE")) return 150.0;
        if (type.contains("FAN")) return 65.0;
        if (type.contains("LIGHT")) return 30.0;
        return 80.0;
    }
}
