package com.hesta.backend.service;

import com.hesta.backend.dto.response.DeviceUptimeResponse;

import java.util.UUID;

public interface DeviceProfilingService {
    DeviceUptimeResponse getDeviceUptime(UUID deviceId, int days);

    /**
     * Xóa cache uptime của thiết bị. Nếu deviceId == null thì xóa toàn bộ cache.
     */
    default void evictDeviceUptimeCache(UUID deviceId) {}
}
