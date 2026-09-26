package com.hesta.backend.service;

import java.util.UUID;

public interface TwinHealthEvaluationService {
    void evaluateAll();
    void evaluateDevice(UUID deviceId);
    void evaluateSensor(UUID deviceId, String metricType);
}
