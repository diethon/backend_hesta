package com.hesta.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.LedCommandRequest;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.DeviceType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.MqttService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceCommandServiceImplTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private MqttService mqttService;

    private ObjectMapper objectMapper;

    private DeviceCommandServiceImpl service;

    private UUID deviceId;
    private Device testDevice;
    private final String testTopic = "hesta/device/led-strip-01/command";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new DeviceCommandServiceImpl(deviceRepository, mqttService, objectMapper);

        deviceId = UUID.randomUUID();
        testDevice = Device.builder()
                .id(deviceId)
                .name("LED Strip Living Room")
                .deviceType(DeviceType.LIGHT)
                .status(DeviceStatus.ONLINE)
                .mqttTopic(testTopic)
                .build();
    }

    @Test
    @DisplayName("Gửi lệnh POWER_ON thành công tới topic của device và cập nhật current_state")
    void testSendCommand_Success_PowerOn() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(testDevice));

        LedCommandRequest request = LedCommandRequest.builder()
                .action("POWER_ON")
                .build();

        service.sendCommand(deviceId, request);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttService, times(1)).publish(eq(testTopic), payloadCaptor.capture());
        verify(deviceRepository, times(1)).save(testDevice);

        assertEquals("{\"action\":\"POWER_ON\"}", payloadCaptor.getValue());
        assertNotNull(testDevice.getCurrentState());
        assertEquals("ON", testDevice.getCurrentState().get("power"));
    }

    @Test
    @DisplayName("Gửi lệnh POWER_OFF thành công tới topic của device và cập nhật current_state")
    void testSendCommand_Success_PowerOff() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(testDevice));

        LedCommandRequest request = LedCommandRequest.builder()
                .action("POWER_OFF")
                .build();

        service.sendCommand(deviceId, request);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttService, times(1)).publish(eq(testTopic), payloadCaptor.capture());
        verify(deviceRepository, times(1)).save(testDevice);

        assertEquals("{\"action\":\"POWER_OFF\"}", payloadCaptor.getValue());
        assertNotNull(testDevice.getCurrentState());
        assertEquals("OFF", testDevice.getCurrentState().get("power"));
    }

    @Test
    @DisplayName("Gửi lệnh SET_RGB thành công tới topic của device và cập nhật current_state")
    void testSendCommand_Success_SetRgb() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(testDevice));

        LedCommandRequest request = LedCommandRequest.builder()
                .action("SET_RGB")
                .r(255)
                .g(128)
                .b(0)
                .build();

        service.sendCommand(deviceId, request);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttService, times(1)).publish(eq(testTopic), payloadCaptor.capture());
        verify(deviceRepository, times(1)).save(testDevice);

        assertEquals("{\"action\":\"SET_RGB\",\"r\":255,\"g\":128,\"b\":0}", payloadCaptor.getValue());
        assertNotNull(testDevice.getCurrentState());
        assertEquals(255, testDevice.getCurrentState().get("r"));
        assertEquals(128, testDevice.getCurrentState().get("g"));
        assertEquals(0, testDevice.getCurrentState().get("b"));
        assertEquals("ON", testDevice.getCurrentState().get("power"));
    }

    @Test
    @DisplayName("Gửi lệnh SET_BRIGHTNESS thành công tới topic của device và cập nhật current_state")
    void testSendCommand_Success_SetBrightness() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(testDevice));

        LedCommandRequest request = LedCommandRequest.builder()
                .action("SET_BRIGHTNESS")
                .brightness(75)
                .build();

        service.sendCommand(deviceId, request);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttService, times(1)).publish(eq(testTopic), payloadCaptor.capture());
        verify(deviceRepository, times(1)).save(testDevice);

        assertEquals("{\"action\":\"SET_BRIGHTNESS\",\"brightness\":75}", payloadCaptor.getValue());
        assertNotNull(testDevice.getCurrentState());
        assertEquals(75, testDevice.getCurrentState().get("brightness"));
        assertEquals("ON", testDevice.getCurrentState().get("power"));
    }

    @Test
    @DisplayName("Ném lỗi DEVICE_NOT_FOUND khi deviceId không tồn tại")
    void testSendCommand_DeviceNotFound() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty());

        LedCommandRequest request = LedCommandRequest.builder()
                .action("POWER_ON")
                .build();

        AppException ex = assertThrows(AppException.class, () -> service.sendCommand(deviceId, request));
        assertEquals(ErrorCode.DEVICE_NOT_FOUND, ex.getErrorCode());
        verify(mqttService, never()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("Ném lỗi DEVICE_MQTT_TOPIC_MISSING khi mqttTopic là null hoặc blank")
    void testSendCommand_MqttTopicMissing() {
        testDevice.setMqttTopic(null);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(testDevice));

        LedCommandRequest request = LedCommandRequest.builder()
                .action("POWER_ON")
                .build();

        AppException ex = assertThrows(AppException.class, () -> service.sendCommand(deviceId, request));
        assertEquals(ErrorCode.DEVICE_MQTT_TOPIC_MISSING, ex.getErrorCode());
        verify(mqttService, never()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("Ném lỗi INVALID_DEVICE_ACTION khi action không được hỗ trợ")
    void testSendCommand_InvalidAction() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(testDevice));

        LedCommandRequest request = LedCommandRequest.builder()
                .action("UNKNOWN_ACTION")
                .build();

        AppException ex = assertThrows(AppException.class, () -> service.sendCommand(deviceId, request));
        assertEquals(ErrorCode.INVALID_DEVICE_ACTION, ex.getErrorCode());
        verify(mqttService, never()).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("Ném lỗi INVALID_RGB_VALUE khi giá trị RGB nằm ngoài dải 0-255")
    void testSendCommand_InvalidRgb() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(testDevice));

        LedCommandRequest request = LedCommandRequest.builder()
                .action("SET_RGB")
                .r(300)
                .g(0)
                .b(0)
                .build();

        AppException ex = assertThrows(AppException.class, () -> service.sendCommand(deviceId, request));
        assertEquals(ErrorCode.INVALID_RGB_VALUE, ex.getErrorCode());

        LedCommandRequest negativeRequest = LedCommandRequest.builder()
                .action("SET_RGB")
                .r(0)
                .g(-5)
                .b(0)
                .build();

        AppException ex2 = assertThrows(AppException.class, () -> service.sendCommand(deviceId, negativeRequest));
        assertEquals(ErrorCode.INVALID_RGB_VALUE, ex2.getErrorCode());
    }

    @Test
    @DisplayName("Ném lỗi INVALID_BRIGHTNESS_VALUE khi độ sáng nằm ngoài dải 0-100")
    void testSendCommand_InvalidBrightness() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(testDevice));

        LedCommandRequest request = LedCommandRequest.builder()
                .action("SET_BRIGHTNESS")
                .brightness(150)
                .build();

        AppException ex = assertThrows(AppException.class, () -> service.sendCommand(deviceId, request));
        assertEquals(ErrorCode.INVALID_BRIGHTNESS_VALUE, ex.getErrorCode());

        LedCommandRequest negativeRequest = LedCommandRequest.builder()
                .action("SET_BRIGHTNESS")
                .brightness(-10)
                .build();

        AppException ex2 = assertThrows(AppException.class, () -> service.sendCommand(deviceId, negativeRequest));
        assertEquals(ErrorCode.INVALID_BRIGHTNESS_VALUE, ex2.getErrorCode());
    }

    @Test
    @DisplayName("Ném lỗi MQTT_PUBLISH_FAILED khi MqttService gặp lỗi khi publish")
    void testSendCommand_MqttPublishFailed() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(testDevice));
        doThrow(new RuntimeException("Connection lost")).when(mqttService).publish(anyString(), anyString());

        LedCommandRequest request = LedCommandRequest.builder()
                .action("POWER_ON")
                .build();

        AppException ex = assertThrows(AppException.class, () -> service.sendCommand(deviceId, request));
        assertEquals(ErrorCode.MQTT_PUBLISH_FAILED, ex.getErrorCode());
    }
}
