package com.hesta.backend.realtime.publisher;

import com.hesta.backend.dto.command.DeviceStateChangedEvent;
import com.hesta.backend.dto.command.SensorReadingUpdatedEvent;
import com.hesta.backend.service.TwinHealthEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class TwinHealthActivityListener {
    private final TwinHealthEvaluationService evaluator;

    @Order(1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deviceChanged(DeviceStateChangedEvent event) {
        evaluator.evaluateDevice(event.payload().deviceId());
    }

    @Order(1)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sensorChanged(SensorReadingUpdatedEvent event) {
        evaluator.evaluateSensor(event.payload().deviceId(), event.payload().metricType());
    }
}
