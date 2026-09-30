package com.hesta.backend.service;

import com.hesta.backend.dto.request.DeviceUpdateRequest;
import com.hesta.backend.dto.response.DeviceResponse;

import java.util.List;
import java.util.UUID;

public interface DeviceService {

    List<DeviceResponse> getDevicesByHome(UUID userId, UUID homeId);

    List<DeviceResponse> getDevicesByRoom(UUID userId, UUID roomId);

    DeviceResponse getDeviceDetail(UUID userId, UUID deviceId);

    void updateDeviceStateFromMqtt(String nodeCode, String deviceIdStr, java.util.Map<String, Object> payload);

    DeviceResponse updateDeviceConfig(UUID userId, UUID deviceId, DeviceUpdateRequest request);

    void removeDevice(UUID userId, UUID deviceId);

    List<com.hesta.backend.dto.response.DeviceStateHistoryResponse> getDeviceHistory(UUID userId, UUID deviceId);
}
