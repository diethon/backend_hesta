package com.hesta.backend.service;

import com.hesta.backend.dto.request.RoomCreateRequest;
import com.hesta.backend.dto.request.RoomUpdateRequest;
import com.hesta.backend.dto.response.RoomResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.service.impl.RoomServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private HomeAuthorizationService homeAuthorizationService;

    @InjectMocks
    private RoomServiceImpl roomService;

    private final UUID userId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private Home home;
    private Room room;

    @BeforeEach
    void setUp() {
        home = Home.builder().id(homeId).name("My Smart Home").build();
        room = Room.builder()
                .id(roomId)
                .home(home)
                .name("Living Room")
                .icon("living")
                .layoutX(BigDecimal.valueOf(10.5))
                .layoutY(BigDecimal.valueOf(20.5))
                .build();
    }

    @Test
    void getRoomsByHome_returnsRoomsWithDeviceCount() {
        when(homeAuthorizationService.requireAccess(userId, homeId)).thenReturn(home);
        when(roomRepository.findByHomeIdOrderByCreatedAtAsc(homeId)).thenReturn(List.of(room));

        Device d1 = Device.builder().id(UUID.randomUUID()).room(room).build();
        Device d2 = Device.builder().id(UUID.randomUUID()).room(room).build();
        when(deviceRepository.findByHomeId(homeId)).thenReturn(List.of(d1, d2));

        List<RoomResponse> result = roomService.getRoomsByHome(userId, homeId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Living Room");
        assertThat(result.get(0).getDeviceCount()).isEqualTo(2);
        verify(homeAuthorizationService).requireAccess(userId, homeId);
    }

    @Test
    void getRoomDetail_success() {
        when(homeAuthorizationService.requireAccess(userId, homeId)).thenReturn(home);
        when(roomRepository.findByIdAndHomeId(roomId, homeId)).thenReturn(Optional.of(room));
        when(deviceRepository.findByRoomId(roomId)).thenReturn(List.of(Device.builder().id(UUID.randomUUID()).build()));

        RoomResponse result = roomService.getRoomDetail(userId, homeId, roomId);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(roomId);
        assertThat(result.getDeviceCount()).isEqualTo(1);
    }

    @Test
    void getRoomDetail_notFound_throwsException() {
        when(homeAuthorizationService.requireAccess(userId, homeId)).thenReturn(home);
        when(roomRepository.findByIdAndHomeId(roomId, homeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roomService.getRoomDetail(userId, homeId, roomId))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_NOT_FOUND);
    }

    @Test
    void createRoom_success() {
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(roomRepository.existsByHomeIdAndNameIgnoreCase(homeId, "Kitchen")).thenReturn(false);
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room r = invocation.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        RoomCreateRequest request = RoomCreateRequest.builder()
                .name("Kitchen")
                .icon("kitchen")
                .layoutX(BigDecimal.ONE)
                .layoutY(BigDecimal.TEN)
                .build();

        RoomResponse result = roomService.createRoom(userId, homeId, request);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Kitchen");
        assertThat(result.getIcon()).isEqualTo("kitchen");
        verify(roomRepository).save(any(Room.class));
    }

    @Test
    void createRoom_duplicateName_throwsException() {
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(roomRepository.existsByHomeIdAndNameIgnoreCase(homeId, "Living Room")).thenReturn(true);

        RoomCreateRequest request = RoomCreateRequest.builder().name("Living Room").build();

        assertThatThrownBy(() -> roomService.createRoom(userId, homeId, request))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_NAME_ALREADY_EXISTS);
        verify(roomRepository, never()).save(any());
    }

    @Test
    void updateRoom_success() {
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(roomRepository.findByIdAndHomeId(roomId, homeId)).thenReturn(Optional.of(room));
        when(roomRepository.existsByHomeIdAndNameIgnoreCaseAndIdNot(homeId, "Master Bedroom", roomId)).thenReturn(false);
        when(roomRepository.save(any(Room.class))).thenReturn(room);
        when(deviceRepository.findByRoomId(roomId)).thenReturn(Collections.emptyList());

        RoomUpdateRequest request = RoomUpdateRequest.builder()
                .name("Master Bedroom")
                .icon("bedroom")
                .build();

        RoomResponse result = roomService.updateRoom(userId, homeId, roomId, request);

        assertThat(result).isNotNull();
        assertThat(room.getName()).isEqualTo("Master Bedroom");
        assertThat(room.getIcon()).isEqualTo("bedroom");
        verify(roomRepository).save(room);
    }

    @Test
    void deleteRoom_unassignsDevicesAndDeletesRoom() {
        when(homeAuthorizationService.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(roomRepository.findByIdAndHomeId(roomId, homeId)).thenReturn(Optional.of(room));

        Device d1 = Device.builder().id(UUID.randomUUID()).room(room).build();
        Device d2 = Device.builder().id(UUID.randomUUID()).room(room).build();
        when(deviceRepository.findByRoomId(roomId)).thenReturn(List.of(d1, d2));

        roomService.deleteRoom(userId, homeId, roomId);

        // Đảm bảo thiết bị đã được set room = null (an toàn không bị xóa vật lý)
        assertThat(d1.getRoom()).isNull();
        assertThat(d2.getRoom()).isNull();
        verify(deviceRepository).saveAll(List.of(d1, d2));
        verify(roomRepository).delete(room);
    }
}
