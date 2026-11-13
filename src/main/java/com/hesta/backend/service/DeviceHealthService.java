package com.hesta.backend.service;

import com.hesta.backend.dto.response.DeviceHealthResponse;

import java.util.UUID;

public interface DeviceHealthService {
    DeviceHealthResponse getDeviceHealth(UUID deviceId);
    void recordSwitch(UUID deviceId);
}
