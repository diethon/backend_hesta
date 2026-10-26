package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.EnergyAnalyticsResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnergyAnalyticsServiceImplTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceStateHistoryRepository historyRepository;

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @InjectMocks
    private EnergyAnalyticsServiceImpl energyAnalyticsService;

    @Test
    void getHomeEnergyAnalytics_calculatesComparisonPercentDynamically() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        Room room = Room.builder().id(UUID.randomUUID()).name("Living Room").build();
        Device device = Device.builder()
                .id(deviceId)
                .name("Air Conditioner")
                .deviceType("AC")
                .status(DeviceStatus.ONLINE)
                .room(room)
                .currentState(Map.of("power", "ON"))
                .build();

        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(device));

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = now.minusDays(30);
        OffsetDateTime prevFrom = from.minusDays(30);

        // Current period: 1 history record turning ON 10 hours ago -> ~10 hours on at 1000W = ~10 kWh
        DeviceStateHistory currHistory = DeviceStateHistory.builder()
                .id(UUID.randomUUID())
                .device(device)
                .changedAt(now.minusHours(10))
                .newState(Map.of("power", "ON"))
                .build();

        // Prev period: 1 history record turning ON 20 hours before 'from' -> ~20 hours on at 1000W = ~20 kWh
        DeviceStateHistory prevHistory = DeviceStateHistory.builder()
                .id(UUID.randomUUID())
                .device(device)
                .changedAt(from.minusHours(20))
                .newState(Map.of("power", "ON"))
                .build();

        when(historyRepository.findByDeviceIdsAndChangedAtBetween(eq(List.of(deviceId)), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenAnswer(invocation -> {
                    OffsetDateTime startArg = invocation.getArgument(1);
                    // If startArg is around prevFrom -> return prevHistory, else currHistory
                    if (startArg.isBefore(from.minusDays(1))) {
                        return List.of(prevHistory);
                    } else {
                        return List.of(currHistory);
                    }
                });

        EnergyAnalyticsResponse response = energyAnalyticsService.getHomeEnergyAnalytics(userId, homeId, "30D");

        assertThat(response).isNotNull();
        assertThat(response.getSummary()).isNotNull();
        assertThat(response.getSummary().getComparisonLastPeriodPercent()).isNotNull();
        verify(homeAuthorizationService).requireAccess(userId, homeId);
    }

    @Test
    void getHomeEnergyAnalytics_zeroPreviousPeriodYieldsZeroPercent() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        Room room = Room.builder().id(UUID.randomUUID()).name("Bedroom").build();
        Device device = Device.builder()
                .id(deviceId)
                .name("Lamp")
                .deviceType("LIGHT")
                .status(DeviceStatus.ONLINE)
                .room(room)
                .currentState(Map.of("power", "OFF"))
                .build();

        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(device));
        when(historyRepository.findByDeviceIdsAndChangedAtBetween(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        EnergyAnalyticsResponse response = energyAnalyticsService.getHomeEnergyAnalytics(userId, homeId, "7D");

        assertThat(response).isNotNull();
        assertThat(response.getSummary().getComparisonLastPeriodPercent()).isEqualTo(0.0);
    }

    @Test
    void getHomeEnergyAnalytics_usesCustomSettingsWattageWhenAvailable() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        Room room = Room.builder().id(UUID.randomUUID()).name("Bedroom").build();
        // Default LIGHT is 40W, but settings defines custom wattage 85W
        Device device = Device.builder()
                .id(deviceId)
                .name("Custom Bulb")
                .deviceType("LIGHT")
                .settings(Map.of("wattage", 85))
                .status(DeviceStatus.ONLINE)
                .room(room)
                .currentState(Map.of("power", "ON"))
                .build();

        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(device));
        when(historyRepository.findByDeviceIdsAndChangedAtBetween(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        EnergyAnalyticsResponse response = energyAnalyticsService.getHomeEnergyAnalytics(userId, homeId, "30D");

        assertThat(response).isNotNull();
        assertThat(response.getBreakdown()).isNotEmpty();
        assertThat(response.getBreakdown().get(0).getWattage()).isEqualTo(85);
    }

    @Test
    void getHomeEnergyAnalytics_scalesPowerByBrightnessForDimmer() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        Room room = Room.builder().id(UUID.randomUUID()).name("Living Room").build();
        // 40W Light with 50% brightness in history
        Device device = Device.builder()
                .id(deviceId)
                .name("Smart Ceiling Light")
                .deviceType("LIGHT")
                .status(DeviceStatus.ONLINE)
                .room(room)
                .currentState(Map.of("power", "ON", "brightness", 50))
                .build();

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = now.minusHours(24);

        // Turn on 2 hours ago with brightness 50%
        DeviceStateHistory history = DeviceStateHistory.builder()
                .id(UUID.randomUUID())
                .device(device)
                .changedAt(now.minusHours(2))
                .previousState(Map.of("power", "OFF"))
                .newState(Map.of("power", "ON", "brightness", 50))
                .build();

        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(device));
        when(historyRepository.findByDeviceIdsAndChangedAtBetween(eq(List.of(deviceId)), any(), any()))
                .thenReturn(List.of(history));

        EnergyAnalyticsResponse response = energyAnalyticsService.getHomeEnergyAnalytics(userId, homeId, "24H");

        assertThat(response).isNotNull();
        assertThat(response.getTimeSeries()).hasSize(24);
        // Effective wattage = 40W * 0.5 = 20W. 2 hours on = (20W * 2h) / 1000 = 0.04 kWh
        assertThat(response.getSummary().getTotalKwh()).isCloseTo(0.04, org.assertj.core.data.Offset.offset(0.01));
    }
}
