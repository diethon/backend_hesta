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
import com.hesta.backend.realtime.publisher.RealtimeEventPublisher;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.service.ManualOverrideService manualOverrideService;

    private static final ConcurrentHashMap<UUID, OffsetDateTime> lastSensorSaveTime = new ConcurrentHashMap<>();

    private void checkHomeAccess(UUID userId, UUID homeId) {
        homeAuthorizationService.requireAccess(userId, homeId);
    }

    private void checkHomeOwner(UUID userId, UUID homeId) {
        homeAuthorizationService.requireSceneManagement(userId, homeId);
    }

    private DeviceResponse mapWithOverride(Device device) {
        DeviceResponse res = DeviceResponse.fromEntity(device);
        if (res != null && manualOverrideService != null) {
            OffsetDateTime overrideUntil = manualOverrideService.getOverrideUntil(device.getId());
            res.setManualOverrideUntil(overrideUntil);
            res.setManualOverrideActive(overrideUntil != null);
        }
        return res;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevicesByHome(UUID userId, UUID homeId) {
        checkHomeAccess(userId, homeId);
        return deviceRepository.findByHomeId(homeId).stream()
                .map(this::mapWithOverride)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevicesByRoom(UUID userId, UUID roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new RuntimeException("Room not found"));
        checkHomeAccess(userId, room.getHome().getId());

        return deviceRepository.findByRoomId(roomId).stream()
                .map(this::mapWithOverride)
                .collect(Collectors.toList());
    }

    private UUID getHomeId(Device device) {
        if (device.getHome() != null) {
            return device.getHome().getId();
        }
        throw new RuntimeException("Device is not associated with any home");
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceResponse getDeviceDetail(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        checkHomeAccess(userId, getHomeId(device));
        return mapWithOverride(device);
    }

    @Override
    @Transactional
    public void updateDeviceStateFromMqtt(String nodeCode, String deviceIdStr, Map<String, Object> payload) {
        try {
            Device device = null;
            try {
                UUID deviceId = UUID.fromString(deviceIdStr);
                device = deviceRepository.findById(deviceId).orElse(null);
            } catch (IllegalArgumentException e) {
                if (nodeCode != null && !"unknown".equalsIgnoreCase(nodeCode)) {
                    device = deviceRepository.findByNodeCodeAndLocalId(nodeCode, deviceIdStr).orElse(null);
                }
                if (device == null) {
                    device = deviceRepository.findByLocalId(deviceIdStr).orElse(null);
                }
                if (device == null) {
                    device = deviceRepository.findByMqttTopicContaining(deviceIdStr).orElse(null);
                }
            }
            if (device == null) {
                System.err.println("Device not found: " + deviceIdStr);
                return;
            }

            boolean isGateOrDoor = device.getDeviceType() != null &&
                    ("GATE".equalsIgnoreCase(device.getDeviceType()) || "ROLLING_DOOR".equalsIgnoreCase(device.getDeviceType()));

            Map<String, Object> prevState = device.getCurrentState() != null ? new HashMap<>(device.getCurrentState()) : new HashMap<>();
            Map<String, Object> newState;

            if (isGateOrDoor) {
                newState = new HashMap<>();
                Object stateVal = null;
                if (payload != null) {
                    stateVal = payload.get("state");
                    if (stateVal == null) {
                        stateVal = payload.get("status");
                    }
                }
                if (stateVal != null) {
                    newState.put("state", stateVal.toString().toUpperCase());
                } else if (prevState.containsKey("state")) {
                    newState.put("state", prevState.get("state"));
                }
            } else {
                newState = new HashMap<>(prevState);
                if (payload != null) {
                    for (Map.Entry<String, Object> entry : payload.entrySet()) {
                        if (!"source".equals(entry.getKey())) {
                            newState.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
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
            if (device.getDeviceType() != null && device.getDeviceType().endsWith("SENSOR")  && !"MOTION_SENSOR".equalsIgnoreCase(device.getDeviceType())) {
                OffsetDateTime lastSave = lastSensorSaveTime.get(device.getId());
                if (lastSave != null && Duration.between(lastSave, OffsetDateTime.now()).toMinutes() < 5) {
                    shouldSaveHistory = false; // Bỏ qua ghi DB Lịch sử
                } else {
                    lastSensorSaveTime.put(device.getId(), OffsetDateTime.now());
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

        checkHomeOwner(userId, getHomeId(device));

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            device.setName(request.getName().trim());
        }

        if (request.getRoomId() != null) {
            Room room = roomRepository.findById(request.getRoomId())
                    .orElseThrow(() -> new RuntimeException("Room not found"));
            if (!room.getHome().getId().equals(getHomeId(device))) {
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

        checkHomeOwner(userId, getHomeId(device));

        device.setDeleted(true);
        deviceRepository.save(device);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.hesta.backend.dto.response.DeviceStateHistoryResponse> getDeviceHistory(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        checkHomeAccess(userId, getHomeId(device));

        return deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId)
                .stream()
                .map(com.hesta.backend.dto.response.DeviceStateHistoryResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportDeviceHistoryCsv(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        checkHomeAccess(userId, getHomeId(device));

        List<DeviceStateHistory> histories = deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId);
        return buildCsv(histories);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportHomeHistoryCsv(UUID userId, UUID homeId) {
        checkHomeAccess(userId, homeId);
        List<Device> devices = deviceRepository.findByHomeId(homeId);
        List<UUID> deviceIds = devices.stream().map(Device::getId).toList();
        List<DeviceStateHistory> histories = deviceStateHistoryRepository.findByDeviceIdInOrderByChangedAtDesc(deviceIds);
        return buildCsv(histories);
    }

    private byte[] buildCsv(List<DeviceStateHistory> histories) {
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM so Microsoft Excel automatically decodes Vietnamese diacritics
        sb.append('\uFEFF');
        sb.append("ID,Thời Gian,Thiết Bị,Phòng,Nguồn Kích Hoạt,Trạng Thái Trước,Trạng Thái Sau,Người Thực Hiện\n");
        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (DeviceStateHistory h : histories) {
            String id = h.getId() != null ? h.getId().toString() : "";
            String time = h.getChangedAt() != null ? h.getChangedAt().format(dtf) : "";
            String devName = h.getDevice() != null ? h.getDevice().getName() : "";
            String roomName = (h.getDevice() != null && h.getDevice().getRoom() != null) ? h.getDevice().getRoom().getName() : "N/A";
            String source = h.getSource() != null ? h.getSource().name() : "MANUAL";
            String prev = h.getPreviousState() != null ? h.getPreviousState().toString() : "{}";
            String curr = h.getNewState() != null ? h.getNewState().toString() : "{}";
            String user = h.getChangedBy() != null ? (h.getChangedBy().getFullName() != null ? h.getChangedBy().getFullName() : h.getChangedBy().getEmail()) : "Hệ thống";

            sb.append(escapeCsv(id)).append(",")
              .append(escapeCsv(time)).append(",")
              .append(escapeCsv(devName)).append(",")
              .append(escapeCsv(roomName)).append(",")
              .append(escapeCsv(source)).append(",")
              .append(escapeCsv(prev)).append(",")
              .append(escapeCsv(curr)).append(",")
              .append(escapeCsv(user)).append("\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String escapeCsv(String value) {
        if (value == null) return "\"\"";
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
