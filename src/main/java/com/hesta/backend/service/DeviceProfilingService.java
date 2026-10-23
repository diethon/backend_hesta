package com.hesta.backend.service;

import com.hesta.backend.dto.response.DeviceUptimeResponse;

import java.util.UUID;

public interface DeviceProfilingService {
    DeviceUptimeResponse getDeviceUptime(UUID deviceId, int days);
}
