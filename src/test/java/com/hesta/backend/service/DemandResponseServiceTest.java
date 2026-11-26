package com.hesta.backend.service;

import com.hesta.backend.energy.demandresponse.DemandResponseEvaluationResult;
import com.hesta.backend.energy.demandresponse.DemandResponseServiceImpl;
import com.hesta.backend.energy.demandresponse.DemandResponseSettings;
import com.hesta.backend.energy.demandresponse.DevicePriorityCategory;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.HomeEnergySettings;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeEnergySettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DemandResponseServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceCommandService deviceCommandService;

    @InjectMocks
    private DemandResponseServiceImpl demandResponseService;

    private UUID homeId;
    private Device smokeDetector;
    private Device fridge;
    private Device airConditioner;
    private Device decorativeLight;

    @BeforeEach
    void setUp() {
        homeId = UUID.randomUUID();

        smokeDetector = Device.builder()
                .id(UUID.randomUUID())
                .name("Cảm Biến Báo Khói")
                .deviceType("SMOKE_DETECTOR")
                .currentState(Map.of("power", "ON"))
                .build();

        fridge = Device.builder()
                .id(UUID.randomUUID())
                .name("Tủ Lạnh Inverter")
                .deviceType("FRIDGE")
                .currentState(Map.of("power", "ON"))
                .build();

        airConditioner = Device.builder()
                .id(UUID.randomUUID())
                .name("Điều Hòa Phòng Khách")
                .deviceType("AIR_CONDITIONER")
                .currentState(Map.of("power", "ON"))
                .build();

        decorativeLight = Device.builder()
                .id(UUID.randomUUID())
                .name("Đèn Chùm Pha Lê")
                .deviceType("LIGHT")
                .currentState(Map.of("power", "ON"))
                .build();

        demandResponseService.setDeviceCommandService(deviceCommandService);
    }

    @Test
    @DisplayName("Should strictly classify devices into correct priority categories")
    void testPriorityClassification() {
        assertEquals(DevicePriorityCategory.SAFETY, demandResponseService.classifyDevicePriority(smokeDetector));
        assertEquals(DevicePriorityCategory.ESSENTIAL, demandResponseService.classifyDevicePriority(fridge));
        assertEquals(DevicePriorityCategory.COMFORT, demandResponseService.classifyDevicePriority(airConditioner));
        assertEquals(DevicePriorityCategory.LUXURY, demandResponseService.classifyDevicePriority(decorativeLight));

        assertEquals(DevicePriorityCategory.SAFETY, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("FLAME_SENSOR").name("Flame").build()));
        assertEquals(DevicePriorityCategory.SAFETY, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("SMOKE_SENSOR").name("Smoke").build()));
        assertEquals(DevicePriorityCategory.ESSENTIAL, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("LUX_SENSOR").name("Lux").build()));
        assertEquals(DevicePriorityCategory.ESSENTIAL, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("MOTION_SENSOR").name("Motion").build()));
        assertEquals(DevicePriorityCategory.ESSENTIAL, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("TEMP_HUMID_SENSOR").name("Temp").build()));
        assertEquals(DevicePriorityCategory.ESSENTIAL, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("DOOR").name("Door").build()));
        assertEquals(DevicePriorityCategory.ESSENTIAL, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("ROLLING_DOOR").name("Rolling Door").build()));
        assertEquals(DevicePriorityCategory.COMFORT, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("WATER_PUMP").name("Pump").build()));
        assertEquals(DevicePriorityCategory.LUXURY, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("TV").name("Smart TV").build()));
        assertEquals(DevicePriorityCategory.LUXURY, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("LED_RGB").name("LED RGB").build()));
        assertEquals(DevicePriorityCategory.LUXURY, demandResponseService.classifyDevicePriority(
                Device.builder().deviceType("CURTAIN").name("Curtain").build()));
    }

    @Test
    @DisplayName("Should shed LUXURY first, protect SAFETY & ESSENTIAL, and save enough watts")
    void testGreedySheddingOrder() {
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId))
                .thenReturn(List.of(smokeDetector, fridge, airConditioner, decorativeLight));

        // Threshold = 1000W, simulated load = 2500W -> excess = 1500W
        demandResponseService.updateSettings(homeId, DemandResponseSettings.builder()
                .homeId(homeId)
                .enabled(true)
                .peakThresholdWatts(1000.0)
                .mode("AUTO_SHED")
                .build());

        DemandResponseEvaluationResult result = demandResponseService.evaluateAndShed(homeId, 2500.0);

        assertEquals("PEAK_SHEDDING_EXECUTED", result.getStatus());
        assertTrue(result.getWattsSaved() > 0);

        // Verify that smokeDetector and fridge are NEVER touched
        verify(deviceCommandService, never()).sendCommand(eq(smokeDetector.getId()), any(), any(), any());
        verify(deviceCommandService, never()).sendCommand(eq(fridge.getId()), any(), any(), any());

        // Verify that decorativeLight (LUXURY) was shed
        verify(deviceCommandService, atLeastOnce()).sendCommand(eq(decorativeLight.getId()), eq("TURN_OFF"), any(), any());
    }

    @Test
    @DisplayName("Should persist and load DemandResponseSettings via HomeEnergySettingsRepository")
    void testPersistAndLoadSettingsFromRepository() {
        HomeEnergySettingsRepository settingsRepo = mock(HomeEnergySettingsRepository.class);
        demandResponseService.setSettingsRepository(settingsRepo);

        HomeEnergySettings stored = HomeEnergySettings.builder()
                .homeId(homeId)
                .drEnabled(true)
                .peakThresholdWatts(new java.math.BigDecimal("5200.00"))
                .drMode("ALERT_ONLY")
                .billingStartDay(1)
                .timezone("Asia/Ho_Chi_Minh")
                .build();
        when(settingsRepo.findById(homeId)).thenReturn(java.util.Optional.of(stored));

        DemandResponseSettings loaded = demandResponseService.getSettings(homeId);
        assertEquals(5200.0, loaded.getPeakThresholdWatts());
        assertEquals("ALERT_ONLY", loaded.getMode());

        demandResponseService.updateSettings(homeId, DemandResponseSettings.builder()
                .enabled(false)
                .peakThresholdWatts(6000.0)
                .mode("AUTO_SHED")
                .build());
        verify(settingsRepo).save(argThat(e -> e.getHomeId().equals(homeId)
                && !e.isDrEnabled()
                && e.getPeakThresholdWatts().doubleValue() == 6000.0
                && "AUTO_SHED".equals(e.getDrMode())));
    }
}
