package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.DeviceService;
import com.hesta.backend.service.HomeAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final HomeAuthorizationService homeAuthorizationService;
    private final DeviceRepository deviceRepository;

    @Override
    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevicesForHome(UUID authenticatedUserId, UUID homeId) {
        homeAuthorizationService.requireAccess(authenticatedUserId, homeId);
        return deviceRepository.findAllByHomeIdOrderByNameAsc(homeId).stream()
                .map(this::toResponse)
                .toList();
    }

    private DeviceResponse toResponse(Device device) {
        return DeviceResponse.builder()
                .id(device.getId())
                .homeId(device.getHome().getId())
                .name(device.getName())
                .deviceType(device.getDeviceType())
                .status(device.getStatus())
                .build();
    }
}
