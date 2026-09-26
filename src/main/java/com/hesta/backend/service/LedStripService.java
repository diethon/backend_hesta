package com.hesta.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.entity.Device;
import com.hesta.backend.repository.DeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class LedStripService {

    private final MqttService mqttService;
    private final ObjectMapper objectMapper;
    private final DeviceRepository deviceRepository;

    @Value("${mqtt.topic.device.command}")
    private String commandTopic;

    public LedStripService(
            MqttService mqttService,
            ObjectMapper objectMapper,
            DeviceRepository deviceRepository
    ) {
        this.mqttService = mqttService;
        this.objectMapper = objectMapper;
        this.deviceRepository = deviceRepository;
    }

    public void powerOn() {
        publishAction("POWER_ON");
        updateDeviceState("POWER_ON", null, null, null, null);
    }

    public void powerOff() {
        publishAction("POWER_OFF");
        updateDeviceState("POWER_OFF", null, null, null, null);
    }

    public void setRgb(int r, int g, int b) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "SET_RGB");
        payload.put("r", r);
        payload.put("g", g);
        payload.put("b", b);

        publish(payload);
        updateDeviceState("SET_RGB", r, g, b, null);
    }

    public void setBrightness(int brightness) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", "SET_BRIGHTNESS");
        payload.put("brightness", brightness);

        publish(payload);
        updateDeviceState("SET_BRIGHTNESS", null, null, null, brightness);
    }

    private void publishAction(String action) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("action", action);
        publish(payload);
    }

    private void publish(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            mqttService.publish(commandTopic, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot create MQTT JSON payload", e);
        }
    }

    private void updateDeviceState(String action, Integer r, Integer g, Integer b, Integer brightness) {
        if (commandTopic == null || commandTopic.isBlank()) {
            return;
        }
        try {
            deviceRepository.findByMqttTopic(commandTopic.trim()).ifPresent(device -> {
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
                        currentState.put("r", r);
                        currentState.put("g", g);
                        currentState.put("b", b);
                        currentState.put("power", "ON");
                        break;
                    case "SET_BRIGHTNESS":
                        currentState.put("brightness", brightness);
                        if (brightness != null && brightness > 0) {
                            currentState.put("power", "ON");
                        }
                        break;
                    default:
                        break;
                }

                device.setCurrentState(currentState);
                deviceRepository.save(device);
                log.info("Updated current_state for device [{}]: {}", device.getId(), currentState);
            });
        } catch (Exception e) {
            log.error("Failed to update current_state for command topic [{}]: {}", commandTopic, e.getMessage(), e);
        }
    }
}
