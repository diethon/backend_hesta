package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.LedCommandRequest;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.exception.GlobalExceptionHandler;
import com.hesta.backend.security.JwtAuthenticationFilter;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.service.DeviceCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeviceCommandController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class DeviceCommandControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DeviceCommandService deviceCommandService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    @DisplayName("POST /api/devices/{deviceId}/command - Thành công trả về HTTP 200 và code 1000")
    void sendCommand_Success_Returns200() throws Exception {
        UUID deviceId = UUID.randomUUID();
        LedCommandRequest request = LedCommandRequest.builder()
                .action("POWER_ON")
                .build();

        doNothing().when(deviceCommandService).sendCommand(eq(deviceId), any(LedCommandRequest.class));

        mockMvc.perform(post("/api/devices/{deviceId}/command", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Gửi lệnh điều khiển thành công"));
    }

    @Test
    @DisplayName("POST /api/devices/{deviceId}/command - Thiết bị không tồn tại trả về HTTP 404")
    void sendCommand_DeviceNotFound_Returns404() throws Exception {
        UUID deviceId = UUID.randomUUID();
        LedCommandRequest request = LedCommandRequest.builder()
                .action("POWER_ON")
                .build();

        doThrow(new AppException(ErrorCode.DEVICE_NOT_FOUND))
                .when(deviceCommandService).sendCommand(eq(deviceId), any(LedCommandRequest.class));

        mockMvc.perform(post("/api/devices/{deviceId}/command", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.DEVICE_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.DEVICE_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("POST /api/devices/{deviceId}/command - Action không hợp lệ trả về HTTP 400")
    void sendCommand_InvalidAction_Returns400() throws Exception {
        UUID deviceId = UUID.randomUUID();
        LedCommandRequest request = LedCommandRequest.builder()
                .action("INVALID_ACTION")
                .build();

        doThrow(new AppException(ErrorCode.INVALID_DEVICE_ACTION))
                .when(deviceCommandService).sendCommand(eq(deviceId), any(LedCommandRequest.class));

        mockMvc.perform(post("/api/devices/{deviceId}/command", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_DEVICE_ACTION.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_DEVICE_ACTION.getMessage()));
    }
}
