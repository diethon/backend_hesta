package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.DeviceGroupRequest;
import com.hesta.backend.dto.request.GroupCommandRequest;
import com.hesta.backend.dto.response.DeviceGroupResponse;
import com.hesta.backend.dto.response.GroupCommandResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceGroup;
import com.hesta.backend.entity.Home;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceGroupRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.DeviceGroupService;
import com.hesta.backend.service.HomeAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceGroupServiceImpl implements DeviceGroupService {

    private final DeviceGroupRepository groupRepository;
    private final DeviceRepository deviceRepository;
    private final HomeRepository homeRepository;
    private final HomeAuthorizationService homeAuthorizationService;

    @Qualifier("mqttDeviceCommandServiceImpl")
    private final DeviceCommandService deviceCommandService;

    @Override
    @Transactional(readOnly = true)
    public List<DeviceGroupResponse> getGroupsByHome(UUID userId, UUID homeId) {
        homeAuthorizationService.requireAccess(userId, homeId);
        return groupRepository.findByHomeIdWithDevices(homeId).stream()
                .map(DeviceGroupResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public DeviceGroupResponse getGroupDetail(UUID userId, UUID groupId) {
        DeviceGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        homeAuthorizationService.requireAccess(userId, group.getHome().getId());
        return DeviceGroupResponse.fromEntity(group);
    }

    @Override
    @Transactional
    public DeviceGroupResponse createGroup(UUID userId, UUID homeId, DeviceGroupRequest request) {
        homeAuthorizationService.requireSceneManagement(userId, homeId);
        Home home = homeRepository.findById(homeId)
                .orElseThrow(() -> new AppException(ErrorCode.HOME_NOT_FOUND));

        List<Device> devices = new ArrayList<>();
        if (request.getDeviceIds() != null && !request.getDeviceIds().isEmpty()) {
            devices = deviceRepository.findAllById(request.getDeviceIds()).stream()
                    .filter(d -> d.getHome() != null && d.getHome().getId().equals(homeId))
                    .collect(Collectors.toList());
        }

        DeviceGroup group = DeviceGroup.builder()
                .home(home)
                .name(request.getName().trim())
                .icon(request.getIcon() != null ? request.getIcon() : "layers")
                .devices(devices)
                .build();

        DeviceGroup saved = groupRepository.save(group);
        log.info("Created device group '{}' (id: {}) with {} devices in home {}",
                saved.getName(), saved.getId(), devices.size(), homeId);
        return DeviceGroupResponse.fromEntity(saved);
    }

    @Override
    @Transactional
    public DeviceGroupResponse updateGroup(UUID userId, UUID groupId, DeviceGroupRequest request) {
        DeviceGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        UUID homeId = group.getHome().getId();
        homeAuthorizationService.requireSceneManagement(userId, homeId);

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            group.setName(request.getName().trim());
        }
        if (request.getIcon() != null) {
            group.setIcon(request.getIcon());
        }
        if (request.getDeviceIds() != null) {
            List<Device> devices = deviceRepository.findAllById(request.getDeviceIds()).stream()
                    .filter(d -> d.getHome() != null && d.getHome().getId().equals(homeId))
                    .collect(Collectors.toList());
            group.setDevices(devices);
        }

        DeviceGroup updated = groupRepository.save(group);
        return DeviceGroupResponse.fromEntity(updated);
    }

    @Override
    @Transactional
    public void deleteGroup(UUID userId, UUID groupId) {
        DeviceGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        homeAuthorizationService.requireSceneManagement(userId, group.getHome().getId());
        groupRepository.delete(group);
    }

    @Override
    @Transactional(readOnly = true)
    public CompletableFuture<GroupCommandResponse> executeGroupCommand(UUID userId, UUID groupId, GroupCommandRequest request) {
        DeviceGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
        homeAuthorizationService.requireAccess(userId, group.getHome().getId());

        List<Device> members = group.getDevices();
        if (members == null || members.isEmpty()) {
            return CompletableFuture.completedFuture(
                    GroupCommandResponse.builder()
                            .groupId(groupId)
                            .action(request.getAction())
                            .totalDevices(0)
                            .successCount(0)
                            .failureCount(0)
                            .results(List.of())
                            .build()
            );
        }

        Map<String, Object> params = request.getParameters() != null ? request.getParameters() : Map.of();

        List<CompletableFuture<CommandResult>> futures = members.stream()
                .map(d -> deviceCommandService.sendCommand(d.getId(), request.getAction(), params, StateChangeSource.MANUAL)
                        .exceptionally(ex -> CommandResult.builder()
                                .commandId(UUID.randomUUID().toString())
                                .success(false)
                                .status("FAILED")
                                .message(ex.getMessage())
                                .build()))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<CommandResult> results = futures.stream().map(CompletableFuture::join).toList();
                    int success = (int) results.stream().filter(CommandResult::isSuccess).count();
                    int failure = results.size() - success;
                    return GroupCommandResponse.builder()
                            .groupId(groupId)
                            .action(request.getAction())
                            .totalDevices(results.size())
                            .successCount(success)
                            .failureCount(failure)
                            .results(results)
                            .build();
                });
    }
}
