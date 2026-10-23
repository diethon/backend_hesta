package com.hesta.backend.service;

import com.hesta.backend.entity.Device;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.shadow.DeviceShadowDto;
import com.hesta.backend.shadow.DeviceShadowServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceShadowServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceCommandService deviceCommandService;

    @InjectMocks
    private DeviceShadowServiceImpl shadowService;

    private UUID deviceId;
    private Device mockDevice;

    @BeforeEach
    void setUp() {
        deviceId = UUID.randomUUID();
        mockDevice = Device.builder()
                .id(deviceId)
                .name("Đèn Phòng Khách")
                .deviceType("LIGHT")
                .currentState(new HashMap<>(Map.of("power", "OFF", "brightness", 50)))
                .build();
    }

    @Test
    @DisplayName("Should initialize shadow and compute delta when desired state changes")
    void testDesiredStateDeltaCalculation() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(mockDevice));

        // Initial state is power: OFF
        DeviceShadowDto shadow = shadowService.getShadow(deviceId);
        assertTrue(shadow.isInSync());

        // Update desired to power: ON, brightness: 80
        Map<String, Object> desired = Map.of("power", "ON", "brightness", 80);
        DeviceShadowDto updated = shadowService.updateDesiredState(deviceId, desired);

        assertFalse(updated.isInSync());
        assertEquals("ON", updated.getDeltaState().get("power"));
        assertEquals(80, updated.getDeltaState().get("brightness"));
        assertEquals(1, updated.getDesiredVersion());
    }

    @Test
    @DisplayName("Should ignore stale out-of-order reported state")
    void testRejectStaleReportedState() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(mockDevice));

        // Report version 5
        shadowService.updateReportedState(deviceId, Map.of("power", "ON"), 5L);
        DeviceShadowDto s1 = shadowService.getShadow(deviceId);
        assertEquals(5L, s1.getReportedVersion());
        assertEquals("ON", s1.getReportedState().get("power"));

        // Out-of-order packet with version 3 arrives -> must be ignored!
        shadowService.updateReportedState(deviceId, Map.of("power", "OFF"), 3L);
        DeviceShadowDto s2 = shadowService.getShadow(deviceId);
        assertEquals(5L, s2.getReportedVersion());
        assertEquals("ON", s2.getReportedState().get("power"), "Stale state must not overwrite latest reported state");
    }
}
