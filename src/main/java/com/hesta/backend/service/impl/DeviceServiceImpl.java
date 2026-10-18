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
                if (deviceHealthService != null) {
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

    @Override
    @Transactional(readOnly = true)
    public byte[] exportDeviceHistoryExcel(UUID userId, UUID deviceId, OffsetDateTime from, OffsetDateTime to) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found"));
        checkHomeAccess(userId, getHomeId(device));

        List<DeviceStateHistory> histories;
        if (from != null && to != null) {
            histories = deviceStateHistoryRepository.findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(deviceId, from, to);
        } else if (from != null) {
            histories = deviceStateHistoryRepository.findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(deviceId, from, OffsetDateTime.now());
        } else {
            histories = deviceStateHistoryRepository.findByDeviceIdOrderByChangedAtDesc(deviceId);
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
                row.createCell(2).setCellValue(h.getDevice() != null ? h.getDevice().getName() : "");
                row.createCell(3).setCellValue((h.getDevice() != null && h.getDevice().getRoom() != null) ? h.getDevice().getRoom().getName() : "N/A");
                row.createCell(4).setCellValue(h.getSource() != null ? h.getSource().name() : "MANUAL");

                String prev = h.getPreviousState() != null ? h.getPreviousState().toString() : "{}";
                String curr = h.getNewState() != null ? h.getNewState().toString() : "{}";

                row.createCell(5).setCellValue(prev);

                org.apache.poi.ss.usermodel.Cell currCell = row.createCell(6);
                currCell.setCellValue(curr);

                boolean isOn = isStateOn(h.getNewState());
                currCell.setCellStyle(isOn ? onStyle : offStyle);

                String user = h.getChangedBy() != null ? (h.getChangedBy().getFullName() != null ? h.getChangedBy().getFullName() : h.getChangedBy().getEmail()) : "Hệ thống";
                row.createCell(7).setCellValue(user);
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

    public static Map<String, List<String>> resolveDeviceCapabilities(Device device) {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        if (device != null && device.getCapabilities() != null && !device.getCapabilities().isEmpty()) {
            for (Map.Entry<String, List<String>> entry : device.getCapabilities().entrySet()) {
                String key = entry.getKey() != null ? entry.getKey().trim().toUpperCase(java.util.Locale.ROOT) : "";
                if (key.isBlank()) continue;
                List<String> actions = new java.util.ArrayList<>();
                if (entry.getValue() != null) {
                    for (String act : entry.getValue()) {
                        if (act != null && !act.isBlank()) {
                            actions.add(act.trim().toUpperCase(java.util.Locale.ROOT));
                        }
                    }
                }
                result.put(key, actions);
            }
        }

        if (result.isEmpty() && device != null) {
            String type = device.getDeviceType() != null ? device.getDeviceType().trim().toUpperCase(java.util.Locale.ROOT) : "";
            switch (type) {
                case "LIGHT", "LED" -> {
                    result.put("POWER", List.of("TURN_ON", "TURN_OFF", "TOGGLE"));
                    result.put("BRIGHTNESS", List.of("SET_BRIGHTNESS"));
                }
                case "LED_RGB" -> {
                    result.put("POWER", List.of("TURN_ON", "TURN_OFF", "TOGGLE"));
                    result.put("BRIGHTNESS", List.of("SET_BRIGHTNESS"));
                    result.put("COLOR", List.of("SET_RGB", "SET_COLOR"));
                }
                case "SMART_PLUG", "SOCKET" -> {
                    result.put("POWER", List.of("TURN_ON", "TURN_OFF", "TOGGLE"));
                }
                case "FAN" -> {
                    result.put("POWER", List.of("TURN_ON", "TURN_OFF", "TOGGLE"));
                    result.put("SPEED", List.of("SET_SPEED", "SET_FAN"));
                }
                case "AIR_CONDITIONER", "AC" -> {
                    result.put("POWER", List.of("TURN_ON", "TURN_OFF", "TOGGLE"));
                    result.put("TEMPERATURE", List.of("SET_TEMPERATURE"));
                    result.put("SPEED", List.of("SET_FAN", "SET_SPEED"));
                    result.put("MODE", List.of("SET_MODE"));
                }
                case "GATE", "ROLLING_DOOR" -> {
                    result.put("GATE", List.of("OPEN", "CLOSE", "STOP"));
                }
                case "IR_REMOTE" -> {
                    result.put("POWER", List.of("TURN_ON", "TURN_OFF", "TOGGLE"));
                    result.put("TEMPERATURE", List.of("SET_TEMPERATURE"));
                    result.put("SPEED", List.of("SET_FAN", "SET_SPEED"));
                    result.put("MODE", List.of("SET_MODE"));
                    result.put("IR_TRANSMIT", List.of("SEND_IR_CODE"));
                }
                case "TEMP_HUMID_SENSOR" -> {
                    result.put("TEMPERATURE_READ", List.of());
                    result.put("HUMIDITY_READ", List.of());
                }
                case "MOTION_SENSOR" -> {
                    result.put("MOTION_DETECT", List.of());
                }
                case "SMOKE_SENSOR" -> {
                    result.put("SMOKE_DETECT", List.of());
                }
                case "CAMERA_AI" -> {
                    result.put("AI_FALL_DETECT", List.of("SET_AI_MODE"));
                    result.put("AI_INTRUSION_DETECT", List.of("SET_AI_MODE"));
                }
                default -> {
                    if (type.contains("SENSOR")) {
                        result.put("SENSOR", List.of());
                    } else {
                        result.put("POWER", List.of("TURN_ON", "TURN_OFF", "TOGGLE"));
                    }
                }
            }
        }
        return result;
    }

    public static List<String> extractSupportedActions(Map<String, List<String>> capabilities) {
        java.util.Set<String> actions = new java.util.LinkedHashSet<>();
        if (capabilities != null) {
            for (Map.Entry<String, List<String>> entry : capabilities.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    actions.addAll(entry.getValue());
                } else {
                    String key = entry.getKey() != null ? entry.getKey().toUpperCase(java.util.Locale.ROOT) : "";
                    switch (key) {
                        case "POWER" -> actions.addAll(List.of("TURN_ON", "TURN_OFF", "TOGGLE"));
                        case "BRIGHTNESS" -> actions.add("SET_BRIGHTNESS");
                        case "COLOR", "RGB" -> actions.addAll(List.of("SET_RGB", "SET_COLOR"));
                        case "TEMPERATURE", "TEMP" -> actions.add("SET_TEMPERATURE");
                        case "SPEED", "FAN" -> actions.addAll(List.of("SET_FAN", "SET_SPEED"));
                        case "MODE" -> actions.add("SET_MODE");
                        case "GATE", "DOOR" -> actions.addAll(List.of("OPEN", "CLOSE", "STOP"));
                    }
                }
            }
        }
        return new java.util.ArrayList<>(actions);
    }

    public static List<String> extractSupportedAttributes(Device device, Map<String, List<String>> capabilities) {
        java.util.Set<String> attrs = new java.util.LinkedHashSet<>();
        if (capabilities != null) {
            for (String key : capabilities.keySet()) {
                switch (key.toUpperCase(java.util.Locale.ROOT)) {
                    case "POWER" -> attrs.add("power");
                    case "BRIGHTNESS" -> attrs.add("brightness");
                    case "COLOR", "RGB" -> { attrs.add("color"); attrs.add("r"); attrs.add("g"); attrs.add("b"); }
                    case "TEMPERATURE", "TEMP", "TEMPERATURE_READ" -> attrs.add("temperature");
                    case "HUMIDITY", "HUMIDITY_READ" -> attrs.add("humidity");
                    case "MOTION", "MOTION_DETECT" -> attrs.add("motion");
                    case "SMOKE", "SMOKE_DETECT" -> attrs.add("smoke");
                    case "SPEED", "FAN" -> attrs.add("speed");
                    case "MODE" -> attrs.add("mode");
                    case "GATE", "DOOR" -> attrs.add("state");
                }
            }
        }
        if (device != null && device.getCurrentState() != null) {
            attrs.addAll(device.getCurrentState().keySet());
        }
        String type = device != null && device.getDeviceType() != null ? device.getDeviceType().toUpperCase(java.util.Locale.ROOT) : "";
        if (type.contains("TEMP") || type.contains("DHT")) {
            attrs.add("temperature");
            attrs.add("humidity");
        }
        if (type.contains("MOTION") || type.contains("PIR")) {
            attrs.add("motion");
        }
        if (type.contains("SMOKE") || type.contains("GAS")) {
            attrs.add("smoke");
        }
        return new java.util.ArrayList<>(attrs);
    }

    public static com.hesta.backend.dto.response.DeviceCapabilityInfoResponse toDeviceCapabilityInfo(Device device) {
        if (device == null) return null;
        Map<String, List<String>> caps = resolveDeviceCapabilities(device);
        List<String> actions = extractSupportedActions(caps);
        List<String> attributes = extractSupportedAttributes(device, caps);
        String type = device.getDeviceType() != null ? device.getDeviceType().toUpperCase(java.util.Locale.ROOT) : "";
        boolean isSensor = type.contains("SENSOR")
                || caps.containsKey("TEMPERATURE_READ")
                || caps.containsKey("HUMIDITY_READ")
                || caps.containsKey("MOTION_DETECT")
                || caps.containsKey("SMOKE_DETECT")
                || caps.containsKey("SENSOR");
        boolean isActionable = !actions.isEmpty();

        return com.hesta.backend.dto.response.DeviceCapabilityInfoResponse.builder()
                .id(device.getId())
                .homeId(device.getHome() != null ? device.getHome().getId() : null)
                .roomId(device.getRoom() != null ? device.getRoom().getId() : null)
                .roomName(device.getRoom() != null ? device.getRoom().getName() : null)
                .name(device.getName())
                .deviceType(device.getDeviceType())
                .status(device.getStatus() != null ? device.getStatus().name() : null)
                .icon(device.getIcon())
                .capabilities(caps)
                .supportedActions(actions)
                .supportedAttributes(attributes)
                .currentState(device.getCurrentState() != null ? device.getCurrentState() : Map.of())
                .sensor(isSensor)
                .actionable(isActionable)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public com.hesta.backend.dto.response.HomeCapabilitiesResponse getHomeCapabilities(UUID userId, UUID homeId) {
        checkHomeAccess(userId, homeId);
        List<Device> devices = deviceRepository.findByHomeId(homeId);
        List<com.hesta.backend.dto.response.DeviceCapabilityInfoResponse> deviceInfos = devices.stream()
                .map(DeviceServiceImpl::toDeviceCapabilityInfo)
                .collect(Collectors.toList());

        Map<String, java.util.Set<UUID>> devicesByCap = new java.util.LinkedHashMap<>();
        devicesByCap.put("POWER", new java.util.LinkedHashSet<>());
        devicesByCap.put("BRIGHTNESS", new java.util.LinkedHashSet<>());
        devicesByCap.put("COLOR", new java.util.LinkedHashSet<>());
        devicesByCap.put("TEMPERATURE", new java.util.LinkedHashSet<>());
        devicesByCap.put("SPEED", new java.util.LinkedHashSet<>());
        devicesByCap.put("MODE", new java.util.LinkedHashSet<>());
        devicesByCap.put("GATE", new java.util.LinkedHashSet<>());
        devicesByCap.put("SENSOR", new java.util.LinkedHashSet<>());

        java.util.Set<String> allActions = new java.util.LinkedHashSet<>();
        java.util.Set<String> sensorAttrs = new java.util.LinkedHashSet<>();

        for (com.hesta.backend.dto.response.DeviceCapabilityInfoResponse dev : deviceInfos) {
            allActions.addAll(dev.getSupportedActions());
            if (dev.isSensor()) {
                devicesByCap.get("SENSOR").add(dev.getId());
                sensorAttrs.addAll(dev.getSupportedAttributes());
            }
            Map<String, List<String>> caps = dev.getCapabilities();
            if (caps != null) {
                if (caps.containsKey("POWER")) devicesByCap.get("POWER").add(dev.getId());
                if (caps.containsKey("BRIGHTNESS")) devicesByCap.get("BRIGHTNESS").add(dev.getId());
                if (caps.containsKey("COLOR") || caps.containsKey("RGB")) devicesByCap.get("COLOR").add(dev.getId());
                if (caps.containsKey("TEMPERATURE") || caps.containsKey("TEMP")) devicesByCap.get("TEMPERATURE").add(dev.getId());
                if (caps.containsKey("SPEED") || caps.containsKey("FAN")) devicesByCap.get("SPEED").add(dev.getId());
                if (caps.containsKey("MODE")) devicesByCap.get("MODE").add(dev.getId());
                if (caps.containsKey("GATE") || caps.containsKey("DOOR")) devicesByCap.get("GATE").add(dev.getId());
            }
        }

        List<com.hesta.backend.dto.response.CapabilityGroupResponse> capabilityGroups = new java.util.ArrayList<>();
        if (!devicesByCap.get("POWER").isEmpty()) {
            capabilityGroups.add(com.hesta.backend.dto.response.CapabilityGroupResponse.builder()
                    .code("POWER").label("Bật / Tắt nguồn").icon("power")
                    .actions(List.of("TURN_ON", "TURN_OFF", "TOGGLE"))
                    .deviceIds(new java.util.ArrayList<>(devicesByCap.get("POWER")))
                    .deviceCount(devicesByCap.get("POWER").size()).build());
        }
        if (!devicesByCap.get("BRIGHTNESS").isEmpty()) {
            capabilityGroups.add(com.hesta.backend.dto.response.CapabilityGroupResponse.builder()
                    .code("BRIGHTNESS").label("Độ sáng ánh sáng").icon("sun")
                    .actions(List.of("SET_BRIGHTNESS"))
                    .deviceIds(new java.util.ArrayList<>(devicesByCap.get("BRIGHTNESS")))
                    .deviceCount(devicesByCap.get("BRIGHTNESS").size()).build());
        }
        if (!devicesByCap.get("COLOR").isEmpty()) {
            capabilityGroups.add(com.hesta.backend.dto.response.CapabilityGroupResponse.builder()
                    .code("COLOR").label("Màu sắc ánh sáng RGB").icon("palette")
                    .actions(List.of("SET_COLOR"))
                    .deviceIds(new java.util.ArrayList<>(devicesByCap.get("COLOR")))
                    .deviceCount(devicesByCap.get("COLOR").size()).build());
        }
        if (!devicesByCap.get("TEMPERATURE").isEmpty()) {
            capabilityGroups.add(com.hesta.backend.dto.response.CapabilityGroupResponse.builder()
                    .code("TEMPERATURE").label("Cài đặt nhiệt độ điều hòa").icon("snowflake")
                    .actions(List.of("SET_TEMPERATURE"))
                    .deviceIds(new java.util.ArrayList<>(devicesByCap.get("TEMPERATURE")))
                    .deviceCount(devicesByCap.get("TEMPERATURE").size()).build());
        }
        if (!devicesByCap.get("SPEED").isEmpty()) {
            capabilityGroups.add(com.hesta.backend.dto.response.CapabilityGroupResponse.builder()
                    .code("SPEED").label("Tốc độ gió & quạt").icon("wind")
                    .actions(List.of("SET_SPEED"))
                    .deviceIds(new java.util.ArrayList<>(devicesByCap.get("SPEED")))
                    .deviceCount(devicesByCap.get("SPEED").size()).build());
        }
        if (!devicesByCap.get("MODE").isEmpty()) {
            capabilityGroups.add(com.hesta.backend.dto.response.CapabilityGroupResponse.builder()
                    .code("MODE").label("Chế độ vận hành").icon("refresh-cw")
                    .actions(List.of("SET_MODE"))
                    .deviceIds(new java.util.ArrayList<>(devicesByCap.get("MODE")))
                    .deviceCount(devicesByCap.get("MODE").size()).build());
        }
        if (!devicesByCap.get("GATE").isEmpty()) {
            capabilityGroups.add(com.hesta.backend.dto.response.CapabilityGroupResponse.builder()
                    .code("GATE").label("Đóng / Mở cửa & cổng").icon("door-closed")
                    .actions(List.of("OPEN", "CLOSE", "STOP"))
                    .deviceIds(new java.util.ArrayList<>(devicesByCap.get("GATE")))
                    .deviceCount(devicesByCap.get("GATE").size()).build());
        }
        if (!devicesByCap.get("SENSOR").isEmpty()) {
            capabilityGroups.add(com.hesta.backend.dto.response.CapabilityGroupResponse.builder()
                    .code("SENSOR").label("Cảm biến môi trường & an ninh").icon("activity")
                    .actions(List.of())
                    .deviceIds(new java.util.ArrayList<>(devicesByCap.get("SENSOR")))
                    .deviceCount(devicesByCap.get("SENSOR").size()).build());
        }

        allActions.add("SET_STATE");

        return com.hesta.backend.dto.response.HomeCapabilitiesResponse.builder()
                .homeId(homeId)
                .devices(deviceInfos)
                .capabilities(capabilityGroups)
                .actionTypes(new java.util.ArrayList<>(allActions))
                .sensorAttributes(new java.util.ArrayList<>(sensorAttrs))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public com.hesta.backend.dto.response.DeviceCapabilityInfoResponse getDeviceCapabilities(UUID userId, UUID deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new com.hesta.backend.exception.AppException(com.hesta.backend.exception.ErrorCode.DEVICE_NOT_FOUND));
        checkHomeAccess(userId, getHomeId(device));
        return toDeviceCapabilityInfo(device);
    }
}
