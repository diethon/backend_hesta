package com.hesta.backend.service;

import com.hesta.backend.dto.request.AdminCreateDeviceRequest;
import com.hesta.backend.dto.request.DeviceClaimRequest;
import com.hesta.backend.dto.response.AdminDeviceCreatedResponse;
import com.hesta.backend.dto.response.DevicePublicPreviewResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.dto.response.QrInfoResponse;

import java.util.UUID;

public interface DeviceOnboardingService {

    AdminDeviceCreatedResponse adminCreateDevice(UUID adminId, AdminCreateDeviceRequest request);

    DeviceResponse getDeviceById(UUID deviceId);

    QrInfoResponse getDeviceQr(UUID deviceId);

    QrInfoResponse regenerateQr(UUID adminId, UUID deviceId);

    DevicePublicPreviewResponse resolveQrToken(String rawToken);

    DeviceResponse claimDevice(UUID userId, UUID deviceId, DeviceClaimRequest request);
}
