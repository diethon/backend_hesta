package com.hesta.backend.service;

import com.hesta.backend.dto.event.AnomalyRaisedEvent;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceAnomaly;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.repository.DeviceAnomalyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SensorOfflineRecordServiceTest {

    @Mock DeviceAnomalyRepository repository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks SensorOfflineRecordService service;

    private final Device device = Device.builder()
            .id(UUID.randomUUID())
            .name("Gas Sensor")
            .lastSeen(OffsetDateTime.now().minusMinutes(10))
            .room(Room.builder().home(Home.builder().id(UUID.randomUUID()).build()).build())
            .build();

    @Test
    void outageAlreadyResolvedByUser_isNotRaisedAgain() {
        when(repository.findByDeviceIdAndAnomalyTypeAndStatusIn(eq(device.getId()), eq(AnomalyType.SENSOR_OFFLINE), any()))
                .thenReturn(Optional.empty());
        when(repository.existsByDeviceIdAndAnomalyTypeAndResolvedAtAfter(device.getId(), AnomalyType.SENSOR_OFFLINE, device.getLastSeen()))
                .thenReturn(true);

        service.handleDeviceOffline(device, Duration.ofMinutes(5));

        verify(repository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void newOutage_raisesAnomalyAndAlert() {
        when(repository.findByDeviceIdAndAnomalyTypeAndStatusIn(eq(device.getId()), eq(AnomalyType.SENSOR_OFFLINE), any()))
                .thenReturn(Optional.empty());
        when(repository.existsByDeviceIdAndAnomalyTypeAndResolvedAtAfter(device.getId(), AnomalyType.SENSOR_OFFLINE, device.getLastSeen()))
                .thenReturn(false);
        when(repository.saveAndFlush(any(DeviceAnomaly.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.handleDeviceOffline(device, Duration.ofMinutes(5));

        verify(repository).saveAndFlush(any(DeviceAnomaly.class));
        verify(eventPublisher).publishEvent(any(AnomalyRaisedEvent.class));
    }
}
