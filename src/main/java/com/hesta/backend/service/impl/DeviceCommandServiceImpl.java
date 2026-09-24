package com.hesta.backend.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.LedCommandRequest;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.MqttService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class DeviceCommandServiceImpl implements DeviceCommandService {

    private final DeviceRepository deviceRepository;
    private final MqttService mqttService;
    private final ObjectMapper objectMapper;

    @Override
    public void sendCommand(UUID deviceId, LedCommandRequest request) {
        log.info("Processing command for device [{}]: {}", deviceId, request != null ? request.getAction() : null);

        // 1. Tìm Device trong database
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));

        // 2. Kiểm tra mqttTopic
        String mqttTopic = device.getMqttTopic();
        if (mqttTopic == null || mqttTopic.isBlank()) {
            log.error("Device [{}] does not have a configured MQTT topic", deviceId);
            throw new AppException(ErrorCode.DEVICE_MQTT_TOPIC_MISSING);
        }

        // 3. Validate command action
        if (request == null || request.getAction() == null || request.getAction().isBlank()) {
            throw new AppException(ErrorCode.INVALID_DEVICE_ACTION);
        }

        String action = request.getAction().trim().toUpperCase();
        LedCommandRequest payloadRequest;

        switch (action) {
            case "POWER_ON":
            case "POWER_OFF":
                payloadRequest = LedCommandRequest.builder()
                        .action(action)
                        .build();
                break;

            case "SET_RGB":
                validateRgb(request.getR(), request.getG(), request.getB());
                payloadRequest = LedCommandRequest.builder()
                        .action(action)
                        .r(request.getR())
                        .g(request.getG())
                        .b(request.getB())
                        .build();
                break;

            case "SET_BRIGHTNESS":
                validateBrightness(request.getBrightness());
                payloadRequest = LedCommandRequest.builder()
                        .action(action)
                        .brightness(request.getBrightness())
                        .build();
                break;

            default:
                log.warn("Unsupported device command action: {}", action);
                throw new AppException(ErrorCode.INVALID_DEVICE_ACTION);
        }

        // 4. Serialize command thành JSON bằng Jackson ObjectMapper
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadRequest);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize MQTT payload for device [{}]: {}", deviceId, e.getMessage(), e);
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }

        // 5. Publish JSON tới mqttTopic của Device
        try {
            mqttService.publish(mqttTopic.trim(), payload);
            log.info("Successfully published command to device [{}] on topic [{}]: {}", deviceId, mqttTopic.trim(), payload);
        } catch (Exception e) {
            log.error("Failed to publish MQTT message for device [{}] to topic [{}]: {}", deviceId, mqttTopic, e.getMessage(), e);
            throw new AppException(ErrorCode.MQTT_PUBLISH_FAILED);
        }

        // 6. Cập nhật trạng thái current_state vào thiết bị trong database
        Map<String, Object> currentState = device.getCurrentState();
        if (currentState == null) {
            currentState = new HashMap<>();
        } else {
            currentState = new HashMap<>(currentState);
        }

        switch (action) {
            case "POWER_ON":
                currentState.put("power", "ON");
                break;

            case "POWER_OFF":
                currentState.put("power", "OFF");
                break;

            case "SET_RGB":
                currentState.put("r", request.getR());
                currentState.put("g", request.getG());
                currentState.put("b", request.getB());
                currentState.put("power", "ON");
                break;

            case "SET_BRIGHTNESS":
                currentState.put("brightness", request.getBrightness());
                if (request.getBrightness() > 0) {
                    currentState.put("power", "ON");
                }
                break;

            default:
                break;
        }

        device.setCurrentState(currentState);
        deviceRepository.save(device);
        log.info("Updated current_state for device [{}]: {}", deviceId, currentState);
    }

    private void validateRgb(Integer r, Integer g, Integer b) {
        if (r == null || g == null || b == null) {
            throw new AppException(ErrorCode.INVALID_RGB_VALUE);
        }
        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
            throw new AppException(ErrorCode.INVALID_RGB_VALUE);
        }
    }

    private void validateBrightness(Integer brightness) {
        if (brightness == null || brightness < 0 || brightness > 100) {
            throw new AppException(ErrorCode.INVALID_BRIGHTNESS_VALUE);
        }
    }

    @Override
    public CompletableFuture<CommandResult> sendCommand(
            UUID deviceId,
            DeviceAction action,
            Map<String, Object> parameters,
            StateChangeSource source
    ) {
        String commandId = UUID.randomUUID().toString();
        log.info("Sending legacy command [{}] to device [{}], source [{}]", action, deviceId, source);

        if (action == null) {
            return CompletableFuture.completedFuture(
                    CommandResult.builder()
                            .commandId(commandId)
                            .success(false)
                            .status("FAILED")
                            .errorCode(ErrorCode.INVALID_DEVICE_ACTION.name())
                            .message("Action cannot be null")
                            .build()
            );
        }

        try {
            LedCommandRequest.LedCommandRequestBuilder requestBuilder = LedCommandRequest.builder();

            switch (action) {
                case TURN_ON:
                    requestBuilder.action("POWER_ON");
                    break;
                case TURN_OFF:
                    requestBuilder.action("POWER_OFF");
                    break;
                case SET_BRIGHTNESS:
                    requestBuilder.action("SET_BRIGHTNESS");
                    if (parameters != null && parameters.containsKey("brightness")) {
                        requestBuilder.brightness(Integer.parseInt(parameters.get("brightness").toString()));
                    } else if (parameters != null && parameters.containsKey("level")) {
                        requestBuilder.brightness(Integer.parseInt(parameters.get("level").toString()));
                    }
                    break;
                default:
                    requestBuilder.action(action.name());
                    break;
            }

            sendCommand(deviceId, requestBuilder.build());

            Map<String, Object> ackState = new HashMap<>();
            if (parameters != null) {
                ackState.putAll(parameters);
            }
            if (action == DeviceAction.TURN_ON) ackState.put("power", "ON");
            if (action == DeviceAction.TURN_OFF) ackState.put("power", "OFF");

            return CompletableFuture.completedFuture(
                    CommandResult.builder()
                            .commandId(commandId)
                            .success(true)
                            .status("ACKNOWLEDGED")
                            .message("Command executed and published successfully")
                            .acknowledgedState(ackState)
                            .build()
            );
        } catch (AppException e) {
            return CompletableFuture.completedFuture(
                    CommandResult.builder()
                            .commandId(commandId)
                            .success(false)
                            .status("FAILED")
                            .errorCode(e.getErrorCode().name())
                            .message(e.getMessage())
                            .build()
            );
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    CommandResult.builder()
                            .commandId(commandId)
                            .success(false)
                            .status("FAILED")
                            .errorCode(ErrorCode.UNCATEGORIZED_EXCEPTION.name())
                            .message(e.getMessage())
                            .build()
            );
        }
    }

    @Override
    public CompletableFuture<List<CommandResult>> sendRoomCommand(UUID roomId, DeviceAction action, Map<String, Object> parameters, StateChangeSource source) {
        return null;
    }
}
