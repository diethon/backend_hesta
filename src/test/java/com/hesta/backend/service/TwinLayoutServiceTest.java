package com.hesta.backend.service;

import com.hesta.backend.dto.request.TwinLayoutSaveRequest;
import com.hesta.backend.dto.request.TwinNodeLayoutRequest;
import com.hesta.backend.dto.request.TwinRoomLayoutRequest;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.TwinLayout;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.repository.TwinLayoutRepository;
import com.hesta.backend.repository.TwinNodeLayoutRepository;
import com.hesta.backend.repository.TwinRoomLayoutRepository;
import com.hesta.backend.service.impl.TwinLayoutServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwinLayoutServiceTest {
    @Mock HomeAuthorizationService authorization;
    @Mock HomeRepository homes;
    @Mock TwinLayoutRepository layouts;
    @Mock TwinRoomLayoutRepository roomLayouts;
    @Mock TwinNodeLayoutRepository nodeLayouts;
    @Mock RoomRepository rooms;
    @Mock DeviceRepository devices;
    @Mock SensorReadingRepository readings;
    private TwinLayoutService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TwinLayoutServiceImpl(authorization, homes, layouts, roomLayouts, nodeLayouts,
                rooms, devices, readings);
    }

    @Test
    void getLayout_withoutSavedLayout_returnsEmptyRevisionZero() {
        when(layouts.findByHomeId(homeId)).thenReturn(Optional.empty());
        assertThat(service.getLayout(userId, homeId))
                .isEqualTo(new com.hesta.backend.dto.response.TwinLayoutResponse(homeId, 0, List.of(), List.of()));
        verify(authorization).requireAccess(userId, homeId);
    }

    @Test
    void saveLayout_ownerReplacesAtomicallyAndIncrementsRevision() {
        Home home = Home.builder().id(homeId).name("Home").build();
        Room room = Room.builder().id(roomId).home(home).name("Living").build();
        when(authorization.requireLayoutManagement(userId, homeId)).thenReturn(home);
        when(homes.findByIdForUpdate(homeId)).thenReturn(Optional.of(home));
        when(layouts.findByHomeIdForUpdate(homeId)).thenReturn(Optional.of(TwinLayout.builder()
                .id(UUID.randomUUID()).homeId(homeId).revision(3L).build()));
        when(rooms.findAllById(any())).thenReturn(List.of(room));
        when(layouts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomLayouts.findByLayoutIdOrderByRoomId(any())).thenReturn(List.of());
        when(nodeLayouts.findByLayoutIdOrderByNodeTypeAscNodeIdAsc(any())).thenReturn(List.of());

        var request = new TwinLayoutSaveRequest(3L,
                List.of(new TwinRoomLayoutRequest(roomId, bd(".05"), bd(".05"), bd(".40"), bd(".30"))),
                List.of());
        var response = service.saveLayout(userId, homeId, request);

        assertThat(response.homeId()).isEqualTo(homeId);
        assertThat(response.revision()).isEqualTo(4L);
        verify(roomLayouts).deleteByLayoutId(any());
        verify(nodeLayouts).deleteByLayoutId(any());
        verify(roomLayouts).saveAll(any());
        verify(nodeLayouts).saveAll(any());
    }

    @Test
    void saveLayout_revisionConflict_doesNotDeleteExistingChildren() {
        Home home = Home.builder().id(homeId).build();
        when(authorization.requireLayoutManagement(userId, homeId)).thenReturn(home);
        when(homes.findByIdForUpdate(homeId)).thenReturn(Optional.of(home));
        when(layouts.findByHomeIdForUpdate(homeId)).thenReturn(Optional.of(TwinLayout.builder().homeId(homeId).revision(4L).build()));
        assertThatThrownBy(() -> service.saveLayout(userId, homeId,
                new TwinLayoutSaveRequest(3L, List.of(), List.of())))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.TWIN_LAYOUT_REVISION_CONFLICT);
        verifyNoInteractions(rooms, devices, readings);
        verify(roomLayouts, never()).deleteByLayoutId(any());
    }

    @Test
    void saveLayout_invalidGeometry_isRejectedBeforePersistence() {
        Home home = Home.builder().id(homeId).build();
        when(authorization.requireLayoutManagement(userId, homeId)).thenReturn(home);
        when(homes.findByIdForUpdate(homeId)).thenReturn(Optional.of(home));
        when(layouts.findByHomeIdForUpdate(homeId)).thenReturn(Optional.empty());
        var request = new TwinLayoutSaveRequest(0L, List.of(new TwinRoomLayoutRequest(
                roomId, bd(".80"), bd(".10"), bd(".30"), bd(".20"))), List.of());
        assertThatThrownBy(() -> service.saveLayout(userId, homeId, request))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.TWIN_LAYOUT_GEOMETRY_INVALID);
        verifyNoInteractions(rooms, devices, readings);
    }

    @Test
    void saveLayout_crossHomeRoom_isRejected() {
        Home home = Home.builder().id(homeId).build();
        Room foreign = Room.builder().id(roomId).home(Home.builder().id(UUID.randomUUID()).build()).build();
        when(authorization.requireLayoutManagement(userId, homeId)).thenReturn(home);
        when(homes.findByIdForUpdate(homeId)).thenReturn(Optional.of(home));
        when(layouts.findByHomeIdForUpdate(homeId)).thenReturn(Optional.empty());
        when(rooms.findAllById(any())).thenReturn(List.of(foreign));
        var request = new TwinLayoutSaveRequest(0L, List.of(new TwinRoomLayoutRequest(
                roomId, bd(".1"), bd(".1"), bd(".2"), bd(".2"))), List.of());
        assertThatThrownBy(() -> service.saveLayout(userId, homeId, request))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.TWIN_LAYOUT_ROOM_INVALID);
        verify(roomLayouts, never()).saveAll(any());
    }

    @Test
    void saveLayout_memberAuthorizationFailure_doesNotTouchPersistence() {
        doThrow(new AppException(ErrorCode.UNAUTHORIZED)).when(authorization).requireLayoutManagement(userId, homeId);
        assertThatThrownBy(() -> service.saveLayout(userId, homeId,
                new TwinLayoutSaveRequest(0L, List.of(), List.of())))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(homes, layouts, roomLayouts, nodeLayouts, rooms, devices, readings);
    }

    private BigDecimal bd(String value) { return new BigDecimal(value); }
}
