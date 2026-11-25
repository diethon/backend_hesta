package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.dto.response.EnergyAnalyticsResponse;
import com.hesta.backend.energy.EnergyPowerCalculator;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceEnergyRollup;
import com.hesta.backend.entity.DeviceStateHistory;
import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.repository.DeviceEnergyRollupRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.DeviceStateHistoryRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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

    @Test
    void getHomeEnergyAnalytics_cachesResponse_andEvictsOnEvent() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();

        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(Collections.emptyList());

        // First call populates cache
        EnergyAnalyticsResponse first = energyAnalyticsService.getHomeEnergyAnalytics(userId, homeId, "30D");
        // Second call should hit in-memory cache directly without querying repository again
        EnergyAnalyticsResponse second = energyAnalyticsService.getHomeEnergyAnalytics(userId, homeId, "30D");

        assertThat(first).isSameAs(second);
        verify(deviceRepository, times(1)).findByHomeIdWithRoomAndNode(homeId);

        // Evict via event
        energyAnalyticsService.onDeviceStateChanged(new DeviceStateChangedEvent(homeId, null));

        // Third call should re-query
        EnergyAnalyticsResponse third = energyAnalyticsService.getHomeEnergyAnalytics(userId, homeId, "30D");
        assertThat(third).isNotNull();
        verify(deviceRepository, times(2)).findByHomeIdWithRoomAndNode(homeId);
    }

    @Test
    void getHomeEnergyAnalytics_offlineDeviceDoesNotAccumulateUnboundedEnergy() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        Room room = Room.builder().id(UUID.randomUUID()).name("Living Room").build();
        Device offlineAc = Device.builder()
                .id(deviceId)
                .name("Offline AC")
                .deviceType("AIR_CONDITIONER")
                .status(DeviceStatus.OFFLINE)
                .room(room)
                .currentState(Map.of("power", "ON"))
                .updatedAt(OffsetDateTime.now().minusDays(8))
                .build();

        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(offlineAc));
        when(historyRepository.findByDeviceIdsAndChangedAtBetween(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        EnergyAnalyticsResponse response = energyAnalyticsService.getHomeEnergyAnalytics(userId, homeId, "30D");

        assertThat(response).isNotNull();
        assertThat(response.getSummary().getTotalKwh()).isEqualTo(0.0);
        assertThat(response.getSummary().getCurrentTier()).isEqualTo(1);
    }

    @Test
    void getHomeEnergyAnalytics_completedRollupsStayImmutableWhenDeviceWattageChanges() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        DeviceEnergyRollupRepository rollupRepo = mock(DeviceEnergyRollupRepository.class);
        energyAnalyticsService.setRollupRepository(rollupRepo);

        Room room = Room.builder().id(UUID.randomUUID()).name("Living Room").build();
        // Wattage was later changed to 2500W, but completed rollup hour already locked 0.50 kWh at old wattage
        Device device = Device.builder()
                .id(deviceId)
                .name("Air Conditioner")
                .deviceType("AIR_CONDITIONER")
                .settings(Map.of("wattage", 2500))
                .status(DeviceStatus.ONLINE)
                .room(room)
                .currentState(Map.of("power", "OFF"))
                .build();

        OffsetDateTime currentHourStart = EnergyPowerCalculator.nowVn().truncatedTo(ChronoUnit.HOURS);
        OffsetDateTime completedBucket = currentHourStart.minusHours(2);

        DeviceEnergyRollup rollup = DeviceEnergyRollup.builder()
                .homeId(homeId)
                .deviceId(deviceId)
                .bucketStart(completedBucket)
                .kwh(new BigDecimal("0.5000"))
                .onSeconds(1800)
                .effectiveWatts(new BigDecimal("1000.00"))
                .source("ESTIMATED")
                .updatedAt(completedBucket.plusHours(1))
                .build();

        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(device));
        when(rollupRepo.findByDeviceIdInAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                eq(List.of(deviceId)), any(), any()))
                .thenReturn(List.of(rollup))
                .thenReturn(Collections.emptyList());
        when(rollupRepo.sumKwhByDeviceIdsAndWindow(eq(List.of(deviceId)), any(), any()))
                .thenReturn(new BigDecimal("0.5000"));
        when(historyRepository.findByDeviceIdsAndChangedAtBetween(eq(List.of(deviceId)), any(), any()))
                .thenReturn(Collections.emptyList());

        EnergyAnalyticsResponse response = energyAnalyticsService.getHomeEnergyAnalytics(userId, homeId, "24H");

        assertThat(response.getSummary().getTotalKwh()).isCloseTo(0.50, org.assertj.core.data.Offset.offset(0.01));
        assertThat(response.getBreakdown().get(0).getTotalOnHours()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void getHomeEnergyAnalytics_isolatesCacheBetweenOwnerAndCustomMember() {
        UUID ownerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID visibleDevId = UUID.randomUUID();
        UUID hiddenDevId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Room room = Room.builder().id(roomId).name("Bedroom").build();

        Device visible = Device.builder().id(visibleDevId).name("Lamp").deviceType("LIGHT").status(DeviceStatus.OFFLINE)
                .room(room).currentState(Map.of("power", "OFF")).build();
        Device hidden = Device.builder().id(hiddenDevId).name("AC").deviceType("AC").status(DeviceStatus.OFFLINE)
                .room(room).currentState(Map.of("power", "OFF")).build();

        MemberAccessContext ownerCtx = new MemberAccessContext(
                homeId, ownerId, UUID.randomUUID(), HomeRole.OWNER, MemberStatus.ACTIVE, AccessScope.ALL, Map.of(), Map.of());
        MemberAccessContext customCtx = new MemberAccessContext(
                homeId, memberId, UUID.randomUUID(), HomeRole.MEMBER, MemberStatus.ACTIVE, AccessScope.CUSTOM, Map.of(),
                Map.of(visibleDevId, AccessLevel.VIEW, hiddenDevId, AccessLevel.NONE));

        when(homeAuthorizationService.loadAccessContext(homeId, ownerId)).thenReturn(ownerCtx);
        when(homeAuthorizationService.loadAccessContext(homeId, memberId)).thenReturn(customCtx);
        when(homeAuthorizationService.effectiveLevel(customCtx, visible)).thenReturn(AccessLevel.VIEW);
        when(homeAuthorizationService.effectiveLevel(customCtx, hidden)).thenReturn(AccessLevel.NONE);
        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(visible, hidden));
        when(historyRepository.findByDeviceIdsAndChangedAtBetween(any(), any(), any())).thenReturn(Collections.emptyList());

        EnergyAnalyticsResponse ownerRes = energyAnalyticsService.getHomeEnergyAnalytics(ownerId, homeId, "30D");
        EnergyAnalyticsResponse memberRes = energyAnalyticsService.getHomeEnergyAnalytics(memberId, homeId, "30D");

        assertThat(ownerRes.getSummary().getTotalDeviceCount()).isEqualTo(2);
        assertThat(memberRes.getSummary().getTotalDeviceCount()).isEqualTo(1);
    }

    @Test
    void getHomeEnergyAnalytics_gateStaticOpenDrawsZeroEnergy_openingDrawsMotorPower() {
        UUID userId = UUID.randomUUID();
        UUID homeId = UUID.randomUUID();
        UUID gateId = UUID.randomUUID();
        Room room = Room.builder().id(UUID.randomUUID()).name("Gate").build();

        Device gate = Device.builder()
                .id(gateId)
                .name("Main Gate")
                .deviceType("GATE")
                .status(DeviceStatus.ONLINE)
                .room(room)
                .currentState(Map.of("state", "OPEN"))
                .build();

        OffsetDateTime now = EnergyPowerCalculator.nowVn();
        // 10s of OPENING motor movement, followed by static OPEN for 2 hours -> only 10s billed
        DeviceStateHistory hOpening = DeviceStateHistory.builder()
                .id(UUID.randomUUID()).device(gate).changedAt(now.minusHours(2))
                .previousState(Map.of("state", "CLOSED")).newState(Map.of("state", "OPENING")).build();
        DeviceStateHistory hOpen = DeviceStateHistory.builder()
                .id(UUID.randomUUID()).device(gate).changedAt(now.minusHours(2).plusSeconds(10))
                .previousState(Map.of("state", "OPENING")).newState(Map.of("state", "OPEN")).build();

        when(deviceRepository.findByHomeIdWithRoomAndNode(homeId)).thenReturn(List.of(gate));
        when(historyRepository.findByDeviceIdsAndChangedAtBetween(eq(List.of(gateId)), any(), any()))
                .thenReturn(List.of(hOpening, hOpen));

        EnergyAnalyticsResponse res = energyAnalyticsService.getHomeEnergyAnalytics(userId, homeId, "24H");
        // (350W * 10s) / 3600000 = 0.00097 kWh -> rounds to 0.00 kWh, instead of 350W * 2h = 0.70 kWh!
        assertThat(res.getSummary().getTotalKwh()).isLessThan(0.01);
    }

    @Test
    void resolveBaseWattage_matchesAllProjectDeviceTypes() {
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("WATER_PUMP", 370),
                Map.entry("LED_RGB", 24),
                Map.entry("LIGHT", 40),
                Map.entry("LUX_SENSOR", 2),
                Map.entry("FLAME_SENSOR", 2),
                Map.entry("AIR_CONDITIONER", 1500),
                Map.entry("DOOR", 350),
                Map.entry("TEMP_HUMID_SENSOR", 2),
                Map.entry("SMOKE_SENSOR", 2),
                Map.entry("MOTION_SENSOR", 2),
                Map.entry("TV", 120),
                Map.entry("CURTAIN", 60),
                Map.entry("ROLLING_DOOR", 350),
                Map.entry("FAN", 65)
        );
        expected.forEach((type, watts) -> {
            Device d = Device.builder().id(UUID.randomUUID()).name(type).deviceType(type).build();
            assertThat(EnergyPowerCalculator.resolveBaseWattage(d))
                    .as("Default wattage for %s", type)
                    .isEqualTo(watts);
        });
    }
}
