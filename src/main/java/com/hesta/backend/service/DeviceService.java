package com.hesta.backend.service;

import com.hesta.backend.dto.response.DeviceResponse;

import java.util.List;
import java.util.UUID;

public interface DeviceService {
    List<DeviceResponse> getDevicesForHome(UUID authenticatedUserId, UUID homeId);
}
