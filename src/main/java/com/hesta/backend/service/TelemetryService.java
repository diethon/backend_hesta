package com.hesta.backend.service;

import com.hesta.backend.dto.request.TelemetryPayload;

public interface TelemetryService {
    void processTelemetry(String topic, TelemetryPayload payload);
}
