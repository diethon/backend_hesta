package com.hesta.backend.service;

import com.hesta.backend.dto.response.DeviceHealthResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.service.impl.DeviceHealthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceHealthServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceStateHistoryRepository historyRepository;

    @InjectMocks
    private DeviceHealthServiceImpl healthService;

    private UUID deviceId;
    private Device device;

    @BeforeEach
    void setUp() {
        deviceId = UUID.randomUUID();
        device = Device.builder().id(deviceId).name("Relay Switch 1").build();
    }

    @Test
    @DisplayName("Device Health: calculates baseline health without chattering")
    void testGetDeviceHealth_Normal() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(historyRepository.findByDeviceIdOrderByChangedAtDesc(deviceId)).thenReturn(List.of());

        healthService.recordSwitch(deviceId);
        healthService.recordSwitch(deviceId);

        DeviceHealthResponse response = healthService.getDeviceHealth(deviceId);

        assertNotNull(response);
        assertEquals(2, response.getSwitchCycles());
        assertEquals(DeviceHealthServiceImpl.MAX_RATED_CYCLES, response.getMaxRatedCycles());
        assertFalse(response.getChatteringWarning());
        assertFalse(response.getSafetyTripped());
        assertTrue(response.getHealthPercent() > 99.0);
        assertNotNull(response.getLastCycleAt());
    }

    @Test
    @DisplayName("Device Health: triggers chattering warning and safety trip when switches > 10 in 60s")
    void testGetDeviceHealth_ChatteringDetected() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(historyRepository.findByDeviceIdOrderByChangedAtDesc(deviceId)).thenReturn(List.of());

        for (int i = 0; i < 12; i++) {
            healthService.recordSwitch(deviceId);
        }

        DeviceHealthResponse response = healthService.getDeviceHealth(deviceId);

        assertNotNull(response);
        assertEquals(12, response.getSwitchCycles());
        assertTrue(response.getChatteringWarning(), "Chattering warning must be triggered when switch count > 10 in 60s");
        assertTrue(response.getSafetyTripped(), "Safety trip must activate when chattering occurs");
    }
}
