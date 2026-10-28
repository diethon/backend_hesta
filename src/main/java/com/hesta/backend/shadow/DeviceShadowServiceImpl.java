package com.hesta.backend.shadow;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceShadowServiceImpl implements DeviceShadowService {

    private final DeviceRepository deviceRepository;

    @Autowired
    @Lazy
    private DeviceCommandService deviceCommandService;

    private static class ShadowStateHolder {
        final Map<String, Object> desired = new ConcurrentHashMap<>();
        final Map<String, Object> reported = new ConcurrentHashMap<>();
        final AtomicLong desiredVersion = new AtomicLong(0);
        final AtomicLong reportedVersion = new AtomicLong(0);
        volatile OffsetDateTime desiredUpdatedAt = OffsetDateTime.now();
        volatile OffsetDateTime reportedUpdatedAt = OffsetDateTime.now();
        volatile String deviceName = "";
    }

    private final ConcurrentHashMap<UUID, ShadowStateHolder> shadows = new ConcurrentHashMap<>();

    private ShadowStateHolder getOrInitHolder(UUID deviceId) {
        ShadowStateHolder existing = shadows.get(deviceId);
        if (existing != null) {
            return existing;
        }

        Device dev = deviceRepository.findById(deviceId).orElse(null);
        ShadowStateHolder newHolder = new ShadowStateHolder();
        if (dev != null) {
            newHolder.deviceName = dev.getName();
            if (dev.getCurrentState() != null) {
                dev.getCurrentState().forEach((k, v) -> {
                    if (k != null && v != null) {
                        newHolder.reported.put(k, v);
                        newHolder.desired.put(k, v);
                    }
                });
            }
        }
        ShadowStateHolder prior = shadows.putIfAbsent(deviceId, newHolder);
        return prior != null ? prior : newHolder;
    }

    @Override
    public DeviceShadowDto getShadow(UUID deviceId) {
        Device dev = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        ShadowStateHolder holder = getOrInitHolder(deviceId);
        holder.deviceName = dev.getName();

        Map<String, Object> delta = computeDelta(holder.desired, holder.reported);
        boolean inSync = delta.isEmpty();

        return DeviceShadowDto.builder()
                .deviceId(deviceId)
                .deviceName(holder.deviceName)
                .desiredState(new HashMap<>(holder.desired))
                .reportedState(new HashMap<>(holder.reported))
                .deltaState(delta)
                .desiredVersion(holder.desiredVersion.get())
                .reportedVersion(holder.reportedVersion.get())
                .desiredUpdatedAt(holder.desiredUpdatedAt)
                .reportedUpdatedAt(holder.reportedUpdatedAt)
                .inSync(inSync)
                .build();
    }

    @Override
    public DeviceShadowDto updateDesiredState(UUID deviceId, Map<String, Object> desiredState) {
        Device dev = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        ShadowStateHolder holder = getOrInitHolder(deviceId);
        holder.deviceName = dev.getName();

        if (desiredState != null) {
            desiredState.forEach((k, v) -> {
                if (k != null && v != null) {
                    holder.desired.put(k, v);
                }
            });
        }

        holder.desiredVersion.incrementAndGet();
        holder.desiredUpdatedAt = OffsetDateTime.now();

        Map<String, Object> delta = computeDelta(holder.desired, holder.reported);
        boolean inSync = delta.isEmpty();

        log.info("Updated Desired Shadow for device [{}] (v{}). Delta size: {}", deviceId, holder.desiredVersion.get(), delta.size());

        // Attempt dispatch if delta exists
        if (!delta.isEmpty() && deviceCommandService != null) {
            try {
                String action = resolveShadowAction(delta);
                deviceCommandService.sendCommand(deviceId, action, delta, StateChangeSource.SHADOW_SYNC);
            } catch (Exception e) {
                log.warn("Device [{}] command dispatch from shadow delayed: {}", deviceId, e.getMessage());
            }
        }

        return getShadow(deviceId);
    }

    @Override
    public DeviceShadowDto updateReportedState(UUID deviceId, Map<String, Object> reportedState, Long incomingVersion) {
        ShadowStateHolder holder = getOrInitHolder(deviceId);

        long curVersion;
        long nextVersion;
        do {
            curVersion = holder.reportedVersion.get();
            if (incomingVersion != null && incomingVersion <= curVersion) {
                log.warn("Ignored stale reported state for device [{}]. Current v{}, incoming v{}",
                        deviceId, curVersion, incomingVersion);
                return getShadow(deviceId);
            }
            nextVersion = incomingVersion != null ? incomingVersion : curVersion + 1;
        } while (!holder.reportedVersion.compareAndSet(curVersion, nextVersion));

        if (reportedState != null) {
            reportedState.forEach((k, v) -> {
                if (k != null && v != null) {
                    holder.reported.put(k, v);
                }
            });
        }
        holder.reportedUpdatedAt = OffsetDateTime.now();

        Map<String, Object> delta = computeDelta(holder.desired, holder.reported);
        log.debug("Updated Reported Shadow for device [{}] (v{}). InSync: {}", deviceId, holder.reportedVersion.get(), delta.isEmpty());

        return getShadow(deviceId);
    }

    @Override
    public DeviceShadowDto reconcile(UUID deviceId) {
        ShadowStateHolder holder = getOrInitHolder(deviceId);
        Map<String, Object> delta = computeDelta(holder.desired, holder.reported);

        if (delta.isEmpty()) {
            log.info("Device [{}] shadow is already in sync", deviceId);
            return getShadow(deviceId);
        }

        log.info("Reconciling device [{}] shadow with delta: {}", deviceId, delta);
        if (deviceCommandService != null) {
            String action = resolveShadowAction(delta);
            deviceCommandService.sendCommand(deviceId, action, delta, StateChangeSource.SHADOW_SYNC);
        }

        return getShadow(deviceId);
    }

    @Override
    public List<DeviceShadowDto> getUnsyncedShadows() {
        return shadows.keySet().stream()
                .map(this::getShadow)
                .filter(dto -> !dto.isInSync())
                .collect(Collectors.toList());
    }

    private String resolveShadowAction(Map<String, Object> delta) {
        if (delta == null || delta.isEmpty()) return "UPDATE";
        if (delta.containsKey("power")) {
            Object p = delta.get("power");
            if (p != null) {
                String s = p.toString().trim().toUpperCase();
                if ("TRUE".equals(s) || "ON".equals(s) || "1".equals(s)) return "ON";
                if ("FALSE".equals(s) || "OFF".equals(s) || "0".equals(s)) return "OFF";
            }
            return "SET_POWER";
        }
        if (delta.containsKey("brightness") || delta.containsKey("level")) return "SET_BRIGHTNESS";
        if (delta.containsKey("r") || delta.containsKey("g") || delta.containsKey("b") || delta.containsKey("color") || delta.containsKey("hex")) return "SET_RGB";
        if (delta.containsKey("temperature") || delta.containsKey("temp")) return "SET_TEMPERATURE";
        if (delta.containsKey("state")) {
            Object st = delta.get("state");
            if (st != null) return st.toString().toUpperCase();
        }
        return "UPDATE";
    }

    private Map<String, Object> computeDelta(Map<String, Object> desired, Map<String, Object> reported) {
        Map<String, Object> delta = new HashMap<>();
        for (Map.Entry<String, Object> entry : desired.entrySet()) {
            String key = entry.getKey();
            Object desiredVal = entry.getValue();
            if (desiredVal == null) continue;
            Object reportedVal = reported.get(key);

            if (reportedVal == null || !String.valueOf(desiredVal).equalsIgnoreCase(String.valueOf(reportedVal))) {
                delta.put(key, desiredVal);
            }
        }
        return delta;
    }
}
