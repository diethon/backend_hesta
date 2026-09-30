package com.hesta.backend.realtime.publisher;

import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.dto.command.SensorReadingUpdatedEvent;
import com.hesta.backend.realtime.model.RealtimeEvent;
import com.hesta.backend.realtime.model.RealtimeEventType;
import com.hesta.backend.service.TwinHealthStatusResolver;
import org.springframework.core.annotation.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class DeviceSensorRealtimeListener {
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final TwinHealthStatusResolver healthResolver;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Order(0)
    public void onDeviceStateChanged(DeviceStateChangedEvent event) {
        realtimeEventPublisher.publish(RealtimeEvent.create(RealtimeEventType.DEVICE_STATE_CHANGED,
                event.homeId(), event.payload().deviceId(),
                event.payload().withHealthStatus(healthResolver.resolve(event.payload().lastSeen()))));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Order(0)
    public void onSensorReadingUpdated(SensorReadingUpdatedEvent event) {
        realtimeEventPublisher.publish(RealtimeEvent.create(RealtimeEventType.SENSOR_READING_UPDATED,
                event.homeId(), event.payload().deviceId(),
                event.payload().withHealthStatus(healthResolver.resolve(event.payload().observedAt()))));
    }
}
