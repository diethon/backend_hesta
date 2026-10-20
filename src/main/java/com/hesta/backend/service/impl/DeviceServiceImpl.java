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
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.service.DeviceHealthService deviceHealthService;

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
                .orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));
        checkHomeAccess(userId, room.getHome().getId());

        return deviceRepository.findByRoomId(roomId).stream()
                .map(this::mapWithOverride)
                .collect(Collectors.toList());
    }

    private UUID getHomeId(Device device) {
        if (device.getHome() != null) {
            return device.getHome().getId();
        }
        throw new AppException(ErrorCode.DEVICE_NOT_FOUND);
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceResponse getDeviceDetail(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
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
            if (device.getStatus() != com.hesta.backend.enums.DeviceStatus.ONLINE) {
                device.setStatus(com.hesta.backend.enums.DeviceStatus.ONLINE);
            }

            // 2. Chặn Spam Heartbeat (Nếu trạng thái y hệt nhau, không làm gì thêm)
            if (prevState.equals(newState)) {
                deviceRepository.save(device); // Lưu lastSeen và status ONLINE
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
                boolean isSensor = device.getDeviceType() != null && device.getDeviceType().endsWith("SENSOR");
                if (deviceHealthService != null && !isSensor) {
                    try {
                        deviceHealthService.recordSwitch(device.getId());
                    } catch (Exception ignored) {}
                }
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
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        checkHomeOwner(userId, getHomeId(device));

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            device.setName(request.getName().trim());
        }

        if (request.getRoomId() != null) {
            Room room = roomRepository.findById(request.getRoomId())
                    .orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));
            if (!room.getHome().getId().equals(getHomeId(device))) {
                throw new AppException(ErrorCode.SCENE_DEVICE_HOME_MISMATCH);
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

        if (request.getSettings() != null) {
            Map<String, Object> currentSettings = device.getSettings() != null ? new HashMap<>(device.getSettings()) : new HashMap<>();
            currentSettings.putAll(request.getSettings());
            device.setSettings(currentSettings);
        }

        Device saved = deviceRepository.save(device);
        return DeviceResponse.fromEntity(saved);
    }

    @Override
    @Transactional
    public void removeDevice(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        checkHomeOwner(userId, getHomeId(device));

        device.setDeleted(true);
        deviceRepository.save(device);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.hesta.backend.dto.response.DeviceStateHistoryResponse> getDeviceHistory(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
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
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
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

        // Giới hạn an toàn tối đa 2000 dòng chống OOM
        List<DeviceStateHistory> safeList = histories.size() > 2000 ? histories.subList(0, 2000) : histories;

        for (DeviceStateHistory h : safeList) {
            String id = h.getId() != null ? h.getId().toString() : "";
            String time = h.getChangedAt() != null ? h.getChangedAt().format(dtf) : "";
            String devName = h.getDevice() != null ? h.getDevice().getName() : "";
            String roomName = (h.getDevice() != null && h.getDevice().getRoom() != null) ? h.getDevice().getRoom().getName() : "N/A";
            String source = h.getSource() != null ? h.getSource().name() : "MANUAL";
            String prev = formatStateHumanReadable(h.getPreviousState());
            String curr = formatStateHumanReadable(h.getNewState());
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
        String trimmed = value.trim();
        if (trimmed.startsWith("=") || trimmed.startsWith("+") || trimmed.startsWith("-") || trimmed.startsWith("@")) {
            value = "'" + value;
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] exportDeviceHistoryExcel(UUID userId, UUID deviceId, OffsetDateTime from, OffsetDateTime to) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        checkHomeAccess(userId, getHomeId(device));

        List<DeviceStateHistory> histories;
        if (from != null && to != null) {
            histories = deviceStateHistoryRepository.findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(deviceId, from, to);
        } else if (from != null) {
            histories = deviceStateHistoryRepository.findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(deviceId, from, OffsetDateTime.now());
        } else {
            // Mặc định an toàn 30 ngày gần nhất nếu không chỉ định mốc thời gian
            OffsetDateTime defaultFrom = OffsetDateTime.now().minusDays(30);
            histories = deviceStateHistoryRepository.findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(deviceId, defaultFrom, OffsetDateTime.now());
            if (histories.isEmpty()) {
                histories = deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId);
            }
        }

        // Giới hạn an toàn tối đa 2000 dòng để bảo vệ JVM heap
        if (histories.size() > 2000) {
            histories = histories.subList(0, 2000);
        }

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Lịch sử trạng thái");

            // Header styling
            org.apache.poi.ss.usermodel.CellStyle headerStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.ROYAL_BLUE.getIndex());
            headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);

            // ON state style (Green background / font)
            org.apache.poi.ss.usermodel.CellStyle onStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font onFont = workbook.createFont();
            onFont.setBold(true);
            onFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_GREEN.getIndex());
            onStyle.setFont(onFont);
            onStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_GREEN.getIndex());
            onStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            // OFF state style (Gray background / font)
            org.apache.poi.ss.usermodel.CellStyle offStyle = workbook.createCellStyle();
            org.apache.poi.ss.usermodel.Font offFont = workbook.createFont();
            offFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_50_PERCENT.getIndex());
            offStyle.setFont(offFont);
            offStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            offStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            String[] headers = {"STT", "Thời Gian", "Thiết Bị", "Phòng", "Nguồn Kích Hoạt", "Trạng Thái Trước", "Trạng Thái Sau", "Người Thực Hiện"};
            org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                org.apache.poi.ss.usermodel.Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            int rowIdx = 1;
            for (DeviceStateHistory h : histories) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(rowIdx - 1);
                row.createCell(1).setCellValue(h.getChangedAt() != null ? h.getChangedAt().format(dtf) : "");
                row.createCell(2).setCellValue(sanitizeForExcel(h.getDevice() != null ? h.getDevice().getName() : ""));
                row.createCell(3).setCellValue(sanitizeForExcel((h.getDevice() != null && h.getDevice().getRoom() != null) ? h.getDevice().getRoom().getName() : "N/A"));
                row.createCell(4).setCellValue(sanitizeForExcel(h.getSource() != null ? h.getSource().name() : "MANUAL"));

                String prev = formatStateHumanReadable(h.getPreviousState());
                String curr = formatStateHumanReadable(h.getNewState());

                row.createCell(5).setCellValue(sanitizeForExcel(prev));

                org.apache.poi.ss.usermodel.Cell currCell = row.createCell(6);
                currCell.setCellValue(sanitizeForExcel(curr));

                boolean isOn = isStateOn(h.getNewState());
                currCell.setCellStyle(isOn ? onStyle : offStyle);

                String user = h.getChangedBy() != null ? (h.getChangedBy().getFullName() != null ? h.getChangedBy().getFullName() : h.getChangedBy().getEmail()) : "Hệ thống";
                row.createCell(7).setCellValue(sanitizeForExcel(user));
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi tạo file Excel lịch sử thiết bị: " + e.getMessage(), e);
        }
    }

    private String formatStateHumanReadable(Map<String, Object> state) {
        if (state == null || state.isEmpty()) return "Không xác định";

        if (state.containsKey("state") && state.get("state") != null) {
            String s = state.get("state").toString().toUpperCase();
            if ("OPEN".equals(s)) return "MỞ";
            if ("CLOSED".equals(s)) return "ĐÓNG";
            if ("OPENING".equals(s)) return "ĐANG MỞ";
            if ("CLOSING".equals(s)) return "ĐANG ĐÓNG";
            if ("STOPPED".equals(s)) return "DỪNG LẠI";
        }

        boolean isOn = isStateOn(state);
        if (!isOn) return "TẮT";

        List<String> details = new ArrayList<>();
        if (state.get("brightness") != null) {
            details.add("Sáng: " + state.get("brightness") + "%");
        }
        if (state.get("temperature") != null) {
            details.add("Nhiệt độ: " + state.get("temperature") + "°C");
        }
        if (state.get("mode") != null) {
            details.add("Chế độ: " + state.get("mode"));
        }
        if (state.get("fan") != null) {
            details.add("Gió: " + state.get("fan"));
        }
        if (state.get("speed") != null) {
            details.add("Tốc độ: " + state.get("speed"));
        }
        if (state.get("color") instanceof Map<?, ?> colorMap) {
            Object r = colorMap.get("r");
            Object g = colorMap.get("g");
            Object b = colorMap.get("b");
            if (r != null && g != null && b != null) {
                details.add(String.format("RGB(%s, %s, %s)", r, g, b));
            }
        }

        if (details.isEmpty()) {
            return "BẬT";
        }
        return "BẬT (" + String.join(", ", details) + ")";
    }

    private boolean isStateOn(Map<String, Object> state) {
        if (state == null || state.isEmpty()) return false;
        Object power = state.get("power");
        if (power != null) {
            if (power instanceof Boolean b) return b;
            String pStr = power.toString().trim().toUpperCase();
            if ("ON".equals(pStr) || "TRUE".equals(pStr) || "1".equals(pStr)) return true;
        }
        Object status = state.get("status");
        if (status != null) {
            String sStr = status.toString().trim().toUpperCase();
            if ("ON".equals(sStr) || "OPEN".equals(sStr) || "ACTIVE".equals(sStr)) return true;
        }
        Object stateVal = state.get("state");
        if (stateVal != null) {
            if (stateVal instanceof Boolean b) return b;
            String sStr = stateVal.toString().trim().toUpperCase();
            if ("ON".equals(sStr) || "TRUE".equals(sStr) || "OPEN".equals(sStr)) return true;
        }
        return false;
    }

    private String sanitizeForExcel(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.startsWith("=") || trimmed.startsWith("+") || trimmed.startsWith("-") || trimmed.startsWith("@")) {
            return "'" + value;
        }
        return value;
    }
}
