package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.dto.request.DeviceUpdateRequest;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Room;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.service.DeviceService;
import com.hesta.backend.enums.DeviceType;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;
    private final HomeAuthorizationService homeAuthorizationService;
    private final RoomRepository roomRepository;
    private final DeviceStateHistoryRepository deviceStateHistoryRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TwinSnapshotMapper twinSnapshotMapper;

    private static final ConcurrentHashMap<UUID, OffsetDateTime> lastSensorSaveTime = new ConcurrentHashMap<>();

    private void checkHomeAccess(UUID userId, UUID homeId) {
        homeAuthorizationService.requireAccess(userId, homeId);
    }

    private void checkHomeOwner(UUID userId, UUID homeId) {
        homeAuthorizationService.requireSceneManagement(userId, homeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevicesByHome(UUID userId, UUID homeId) {
        checkHomeAccess(userId, homeId);
        return deviceRepository.findByHomeId(homeId).stream()
                .map(DeviceResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevicesByRoom(UUID userId, UUID roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Room not found"));
        checkHomeAccess(userId, room.getHome().getId());

        return deviceRepository.findByRoomId(roomId).stream()
                .map(DeviceResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceResponse getDeviceDetail(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        checkHomeAccess(userId, device.getHome().getId());
        return DeviceResponse.fromEntity(device);
    }

    @Override
    @Transactional
    public void updateDeviceStateFromMqtt(String deviceIdStr, Map<String, Object> payload) {
        try {
            UUID deviceId = UUID.fromString(deviceIdStr);
            Device device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> new RuntimeException("Device not found"));

            List<String> allowedKeys = device.getCapabilities();
            Map<String, Object> newState = new HashMap<>();

            if (allowedKeys == null || allowedKeys.isEmpty()) {
                newState.putAll(payload);
            } else {
                for (Map.Entry<String, Object> entry : payload.entrySet()) {
                    if (allowedKeys.contains(entry.getKey())) {
                        newState.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            Map<String, Object> prevState = device.getCurrentState() != null ? new HashMap<>(device.getCurrentState()) : new HashMap<>();
            device.setLastSeen(OffsetDateTime.now());



            // 2. Chặn Spam Heartbeat (Nếu trạng thái y hệt nhau, không làm gì thêm)
            if (prevState.equals(newState)) {
                deviceRepository.save(device); // Chỉ lưu lastSeen
                return; // Ngắt mạch, không lưu History, không bắn Websocket
            }

            // Cập nhật trạng thái mới
            device.setCurrentState(newState);
            deviceRepository.save(device);

            // 3. Throttling cho CẢM BIẾN (Chỉ lưu History 5 phút 1 lần)
            boolean shouldSaveHistory = true;
            if (device.getDeviceType() == DeviceType.SENSOR) {
                OffsetDateTime lastSave = lastSensorSaveTime.get(deviceId);
                if (lastSave != null && Duration.between(lastSave, OffsetDateTime.now()).toMinutes() < 5) {
                    shouldSaveHistory = false; // Bỏ qua ghi DB Lịch sử
                } else {
                    lastSensorSaveTime.put(deviceId, OffsetDateTime.now());
                }
            }

            // Ghi Lịch sử nếu được phép
            if (shouldSaveHistory) {
                DeviceStateHistory history = DeviceStateHistory.builder()
                    .device(device)
                    .previousState(prevState)
                    .newState(newState)
                    .source(payload.containsKey("source") ? StateChangeSource.valueOf(payload.get("source").toString().toUpperCase()) : StateChangeSource.MANUAL)
                    .changedAt(OffsetDateTime.now())
                    .build();
                deviceStateHistoryRepository.save(history);
            }

            applicationEventPublisher.publishEvent(new DeviceStateChangedEvent(
                    device.getHome().getId(), twinSnapshotMapper.device(device)));

        } catch (IllegalArgumentException e) {
            System.err.println("Invalid payload or UUID format: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public DeviceResponse updateDeviceConfig(UUID userId, UUID deviceId, DeviceUpdateRequest request) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));

        checkHomeOwner(userId, device.getHome().getId());

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            device.setName(request.getName().trim());
        }

        if (request.getRoomId() != null) {
            Room room = roomRepository.findById(request.getRoomId())
                    .orElseThrow(() -> new RuntimeException("Room not found"));
            if (!room.getHome().getId().equals(device.getHome().getId())) {
                throw new RuntimeException("Room does not belong to this home");
            }
            device.setRoom(room);
        }

        if (request.getIcon() != null) {
            device.setIcon(request.getIcon());
        }
        if (request.getDigitalTwinX() != null) {
            device.setDigitalTwinX(request.getDigitalTwinX());
        }
        if (request.getDigitalTwinY() != null) {
            device.setDigitalTwinY(request.getDigitalTwinY());
        }
        if (request.getDigitalTwinZ() != null) {
            device.setDigitalTwinZ(request.getDigitalTwinZ());
        }

        Device saved = deviceRepository.save(device);
        return DeviceResponse.fromEntity(saved);
    }

    @Override
    @Transactional
    public void removeDevice(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));

        checkHomeOwner(userId, device.getHome().getId());

        device.setDeleted(true);
        deviceRepository.save(device);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.hesta.backend.dto.response.DeviceStateHistoryResponse> getDeviceHistory(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        checkHomeAccess(userId, device.getHome().getId());

        return deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId)
                .stream()
                .map(com.hesta.backend.dto.response.DeviceStateHistoryResponse::fromEntity)
                .collect(Collectors.toList());
    }
}
