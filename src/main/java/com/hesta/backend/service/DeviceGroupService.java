package com.hesta.backend.service;

import com.hesta.backend.dto.request.DeviceGroupRequest;
import com.hesta.backend.dto.request.GroupCommandRequest;
import com.hesta.backend.dto.response.DeviceGroupResponse;
import com.hesta.backend.dto.response.GroupCommandResponse;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface DeviceGroupService {

    List<DeviceGroupResponse> getGroupsByHome(UUID userId, UUID homeId);

    DeviceGroupResponse getGroupDetail(UUID userId, UUID groupId);

    DeviceGroupResponse createGroup(UUID userId, UUID homeId, DeviceGroupRequest request);

    DeviceGroupResponse updateGroup(UUID userId, UUID groupId, DeviceGroupRequest request);

    void deleteGroup(UUID userId, UUID groupId);

    CompletableFuture<GroupCommandResponse> executeGroupCommand(UUID userId, UUID groupId, GroupCommandRequest request);
}
