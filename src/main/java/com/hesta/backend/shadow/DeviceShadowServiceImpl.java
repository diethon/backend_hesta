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
        return shadows.computeIfAbsent(deviceId, id -> {
            ShadowStateHolder holder = new ShadowStateHolder();
            Device dev = deviceRepository.findById(id).orElse(null);
            if (dev != null) {
                holder.deviceName = dev.getName();
                if (dev.getCurrentState() != null) {
                    holder.reported.putAll(dev.getCurrentState());
                    holder.desired.putAll(dev.getCurrentState());
                }
            }
            return holder;
        });
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
            holder.desired.putAll(desiredState);
        }

        holder.desiredVersion.incrementAndGet();
        holder.desiredUpdatedAt = OffsetDateTime.now();

        Map<String, Object> delta = computeDelta(holder.desired, holder.reported);
        boolean inSync = delta.isEmpty();

        log.info("Updated Desired Shadow for device [{}] (v{}). Delta size: {}", deviceId, holder.desiredVersion.get(), delta.size());

        // Attempt dispatch if delta exists
        if (!delta.isEmpty() && deviceCommandService != null) {
            try {
                String action = delta.containsKey("power") ? delta.get("power").toString().toUpperCase() : "UPDATE";
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

        if (incomingVersion != null && incomingVersion <= holder.reportedVersion.get()) {
            log.warn("Ignored stale reported state for device [{}]. Current v{}, incoming v{}",
                    deviceId, holder.reportedVersion.get(), incomingVersion);
            return getShadow(deviceId);
        }

        if (reportedState != null) {
            holder.reported.putAll(reportedState);
        }

        if (incomingVersion != null) {
            holder.reportedVersion.set(incomingVersion);
        } else {
            holder.reportedVersion.incrementAndGet();
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
            String action = delta.containsKey("power") ? delta.get("power").toString().toUpperCase() : "UPDATE";
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

    private Map<String, Object> computeDelta(Map<String, Object> desired, Map<String, Object> reported) {
        Map<String, Object> delta = new HashMap<>();
        for (Map.Entry<String, Object> entry : desired.entrySet()) {
            String key = entry.getKey();
            Object desiredVal = entry.getValue();
            Object reportedVal = reported.get(key);

            if (reportedVal == null || !String.valueOf(desiredVal).equalsIgnoreCase(String.valueOf(reportedVal))) {
                delta.put(key, desiredVal);
            }
        }
        return delta;
    }
}
