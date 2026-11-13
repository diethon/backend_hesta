package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.DeviceGroupRequest;
import com.hesta.backend.dto.request.GroupCommandRequest;
import com.hesta.backend.dto.response.DeviceGroupResponse;
import com.hesta.backend.dto.response.GroupCommandResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.DeviceGroup;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceGroupRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.service.impl.DeviceGroupServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceGroupServiceTest {

    @Mock
    private DeviceGroupRepository groupRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private HomeRepository homeRepository;

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @Mock
    private DeviceCommandService deviceCommandService;

    @InjectMocks
    private DeviceGroupServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @Test
    void getGroupsByHome_returnsMappedList() {
        Home home = Home.builder().id(homeId).build();
        DeviceGroup group = DeviceGroup.builder().id(groupId).name("All Lights").home(home).devices(new ArrayList<>()).build();
        when(groupRepository.findByHomeIdWithDevices(homeId)).thenReturn(List.of(group));

        List<DeviceGroupResponse> result = service.getGroupsByHome(userId, homeId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("All Lights");
        verify(homeAuthorizationService).requireAccess(userId, homeId);
    }

    @Test
    void getGroupDetail_success() {
        Home home = Home.builder().id(homeId).build();
        DeviceGroup group = DeviceGroup.builder().id(groupId).name("Living Room Master").home(home).devices(new ArrayList<>()).build();
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(group));

        DeviceGroupResponse response = service.getGroupDetail(userId, groupId);

        assertThat(response.getName()).isEqualTo("Living Room Master");
        verify(homeAuthorizationService).requireAccess(userId, homeId);
    }

    @Test
    void getGroupDetail_notFound_throwsException() {
        when(groupRepository.findById(groupId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getGroupDetail(userId, groupId))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEVICE_NOT_FOUND);
    }

    @Test
    void createGroup_success() {
        Home home = Home.builder().id(homeId).build();
        Room room = Room.builder().home(home).build();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        Device dev1 = Device.builder().id(d1).name("Light 1").room(room).build();
        Device dev2 = Device.builder().id(d2).name("Light 2").room(room).build();

        DeviceGroupRequest req = new DeviceGroupRequest();
        req.setName("Evening Lights");
        req.setDeviceIds(List.of(d1, d2));

        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(deviceRepository.findAllById(req.getDeviceIds())).thenReturn(List.of(dev1, dev2));
        when(groupRepository.save(any(DeviceGroup.class))).thenAnswer(i -> {
            DeviceGroup g = i.getArgument(0);
            g.setId(groupId);
            return g;
        });

        DeviceGroupResponse created = service.createGroup(userId, homeId, req);

        assertThat(created.getName()).isEqualTo("Evening Lights");
        assertThat(created.getDeviceCount()).isEqualTo(2);
        verify(homeAuthorizationService).requireSceneManagement(userId, homeId);
    }

    @Test
    void updateGroup_success() {
        Home home = Home.builder().id(homeId).build();
        Room room = Room.builder().home(home).build();
        UUID d1 = UUID.randomUUID();
        Device dev1 = Device.builder().id(d1).name("Light 1").room(room).build();
        DeviceGroup group = DeviceGroup.builder().id(groupId).name("Old Group").home(home).devices(new ArrayList<>()).build();

        DeviceGroupRequest req = new DeviceGroupRequest();
        req.setName("Updated Group");
        req.setDeviceIds(List.of(d1));

        when(groupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(deviceRepository.findAllById(req.getDeviceIds())).thenReturn(List.of(dev1));
        when(groupRepository.save(any(DeviceGroup.class))).thenAnswer(i -> i.getArgument(0));

        DeviceGroupResponse updated = service.updateGroup(userId, groupId, req);

        assertThat(updated.getName()).isEqualTo("Updated Group");
        verify(homeAuthorizationService).requireSceneManagement(userId, homeId);
    }

    @Test
    void deleteGroup_success() {
        Home home = Home.builder().id(homeId).build();
        DeviceGroup group = DeviceGroup.builder().id(groupId).home(home).build();
        when(groupRepository.findById(groupId)).thenReturn(Optional.of(group));

        service.deleteGroup(userId, groupId);

        verify(groupRepository).delete(group);
        verify(homeAuthorizationService).requireSceneManagement(userId, homeId);
    }

    @Test
    void executeGroupCommand_dispatchesToAllDevices() {
        Home home = Home.builder().id(homeId).build();
        Room room = Room.builder().home(home).build();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        Device dev1 = Device.builder().id(d1).name("Light 1").room(room).build();
        Device dev2 = Device.builder().id(d2).name("Light 2").room(room).build();
        DeviceGroup group = DeviceGroup.builder().id(groupId).name("Dimmer Group").home(home)
                .devices(new ArrayList<>(List.of(dev1, dev2))).build();

        GroupCommandRequest req = new GroupCommandRequest();
        req.setAction("SET_BRIGHTNESS");
        req.setParameters(Map.of("brightness", 75));

        CommandResult successResult = CommandResult.builder().success(true).status("ACKNOWLEDGED").build();

        when(groupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(deviceCommandService.sendCommand(any(UUID.class), eq("SET_BRIGHTNESS"), any(), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(successResult));

        GroupCommandResponse response = service.executeGroupCommand(userId, groupId, req).join();

        assertThat(response.getSuccessCount()).isEqualTo(2);
        assertThat(response.getFailureCount()).isEqualTo(0);
        verify(deviceCommandService).sendCommand(eq(d1), eq("SET_BRIGHTNESS"), any(), eq(StateChangeSource.MANUAL));
        verify(deviceCommandService).sendCommand(eq(d2), eq("SET_BRIGHTNESS"), any(), eq(StateChangeSource.MANUAL));
    }
}
