package com.hesta.backend.service;

import com.hesta.backend.dto.response.DeviceUptimeResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.service.impl.DeviceProfilingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceProfilingServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceStateHistoryRepository historyRepository;

    @InjectMocks
    private DeviceProfilingServiceImpl profilingService;

    private UUID deviceId;
    private Device device;

    @BeforeEach
    void setUp() {
        deviceId = UUID.randomUUID();
        device = Device.builder()
                .id(deviceId)
                .name("Living Room Light")
                .currentState(Map.of("power", true))
                .build();
    }

    @Test
    @DisplayName("Uptime Profiling: calculates uptime correctly when no history exists and device is ON")
    void testGetDeviceUptime_NoHistory_DeviceOn() {
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(historyRepository.findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(eq(deviceId), any(), any()))
                .thenReturn(List.of());

        DeviceUptimeResponse response = profilingService.getDeviceUptime(deviceId, 7);

        assertNotNull(response);
        assertEquals(168.0, response.getTotalHours());
        assertEquals(168.0, response.getOnHours());
        assertEquals(0.0, response.getOffHours());
        assertEquals(100.0, response.getUptimePercent());
        assertFalse(response.getTimelinePoints().isEmpty());
    }

    @Test
    @DisplayName("Uptime Profiling: calculates timeline points and on/off intervals from history")
    void testGetDeviceUptime_WithHistory() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime t1 = now.minusDays(5);
        OffsetDateTime t2 = now.minusDays(2);

        DeviceStateHistory h1 = DeviceStateHistory.builder()
                .device(device)
                .previousState(Map.of("power", "OFF"))
                .newState(Map.of("power", "ON"))
                .changedAt(t1)
                .build();

        DeviceStateHistory h2 = DeviceStateHistory.builder()
                .device(device)
                .previousState(Map.of("power", "ON"))
                .newState(Map.of("power", "OFF"))
                .changedAt(t2)
                .build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(device));
        when(historyRepository.findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(eq(deviceId), any(), any()))
                .thenReturn(List.of(h1, h2));

        DeviceUptimeResponse response = profilingService.getDeviceUptime(deviceId, 7);

        assertNotNull(response);
        assertEquals(168.0, response.getTotalHours());
        assertTrue(response.getOnHours() > 0);
        assertTrue(response.getOffHours() > 0);
        assertTrue(response.getUptimePercent() >= 0 && response.getUptimePercent() <= 100);
        assertEquals(24, response.getTimelinePoints().size());
        for (int i = 0; i < 24; i++) {
            assertEquals(i, response.getTimelinePoints().get(i).getHour());
            assertNotNull(response.getTimelinePoints().get(i).getState());
        }
    }

    @Test
    @DisplayName("Uptime Profiling: accurately bounds totalHours when device was created less than requested days ago")
    void testGetDeviceUptime_RecentlyCreatedDevice() {
        OffsetDateTime now = OffsetDateTime.now();
        Device recentDevice = Device.builder()
                .id(deviceId)
                .name("New Lamp")
                .createdAt(now.minusDays(2))
                .currentState(Map.of("power", true))
                .build();

        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(recentDevice));
        when(historyRepository.findByDeviceIdAndChangedAtBetweenOrderByChangedAtAsc(eq(deviceId), any(), any()))
                .thenReturn(List.of());

        DeviceUptimeResponse response = profilingService.getDeviceUptime(deviceId, 7);

        assertNotNull(response);
        // Expect approximately 48 hours (+- 1 hour due to test execution drift), NOT 168.0
        assertTrue(response.getTotalHours() >= 47.0 && response.getTotalHours() <= 49.0);
        assertEquals(24, response.getTimelinePoints().size());
    }
}
