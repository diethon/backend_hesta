package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.TwinLayoutSaveRequest;
import com.hesta.backend.dto.request.TwinNodeLayoutRequest;
import com.hesta.backend.dto.request.TwinRoomLayoutRequest;
import com.hesta.backend.dto.response.TwinLayoutResponse;
import com.hesta.backend.dto.response.TwinNodeLayoutResponse;
import com.hesta.backend.dto.response.TwinRoomLayoutResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.TwinLayout;
import com.hesta.backend.entity.TwinNodeLayout;
import com.hesta.backend.entity.TwinRoomLayout;
import com.hesta.backend.enums.TwinNodeType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.repository.TwinLayoutRepository;
import com.hesta.backend.repository.TwinNodeLayoutRepository;
import com.hesta.backend.repository.TwinRoomLayoutRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.TwinLayoutService;
import com.hesta.backend.service.TwinArchitectureValidation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TwinLayoutServiceImpl implements TwinLayoutService {
    private final HomeAuthorizationService authorizationService;
    private final HomeRepository homeRepository;
    private final TwinLayoutRepository layoutRepository;
    private final TwinRoomLayoutRepository roomLayoutRepository;
    private final TwinNodeLayoutRepository nodeLayoutRepository;
    private final RoomRepository roomRepository;
    private final DeviceRepository deviceRepository;
    private final SensorReadingRepository sensorReadingRepository;

    @Override
    @Transactional(readOnly = true)
    public TwinLayoutResponse getLayout(UUID userId, UUID homeId) {
        authorizationService.requireAccess(userId, homeId);
        return layoutRepository.findByHomeId(homeId)
                .map(this::toResponse)
                .orElseGet(() -> new TwinLayoutResponse(homeId, 0L, List.of(), List.of()));
    }

    @Override
    @Transactional
    public TwinLayoutResponse saveLayout(UUID userId, UUID homeId, TwinLayoutSaveRequest request) {
        authorizationService.requireLayoutManagement(userId, homeId);
        if (request == null || request.rooms() == null || request.nodes() == null || request.expectedRevision() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        // Locking the existing Home row also serializes two concurrent first saves,
        // when no TwinLayout row exists yet to lock.
        Home home = homeRepository.findByIdForUpdate(homeId)
                .orElseThrow(() -> new AppException(ErrorCode.HOME_NOT_FOUND));
        TwinLayout layout = layoutRepository.findByHomeIdForUpdate(homeId).orElse(null);
        long currentRevision = layout == null ? 0L : layout.getRevision();
        if (request.expectedRevision() != currentRevision) {
            throw new AppException(ErrorCode.TWIN_LAYOUT_REVISION_CONFLICT);
        }

        Map<UUID, Room> rooms = validateRooms(homeId, request.rooms(), request.nodes());
        validateNodes(homeId, request.nodes(), rooms);
        TwinArchitectureValidation.validate(request.architecture(), request.rooms(), request.nodes());

        if (layout == null) {
            layout = TwinLayout.builder().homeId(home.getId()).revision(1L).build();
        } else {
            layout.setRevision(currentRevision + 1L);
        }
        var architecture = request.architecture() != null ? request.architecture() : layout.getArchitecture();
        layout.setArchitecture(TwinArchitectureValidation.prune(architecture,
                request.rooms().stream().map(TwinRoomLayoutRequest::roomId).collect(java.util.stream.Collectors.toSet()),
                request.nodes().stream().map(n -> n.nodeType() + ":" + n.nodeId()).collect(java.util.stream.Collectors.toSet()),
                request.rooms().stream().map(TwinRoomLayoutRequest::floor).collect(java.util.stream.Collectors.toSet())));
        layout = layoutRepository.saveAndFlush(layout);

        roomLayoutRepository.deleteByLayoutId(layout.getId());
        nodeLayoutRepository.deleteByLayoutId(layout.getId());
        roomLayoutRepository.flush();
        nodeLayoutRepository.flush();

        TwinLayout finalLayout = layout;
        roomLayoutRepository.saveAll(request.rooms().stream().map(room -> TwinRoomLayout.builder()
                .layoutId(finalLayout.getId()).roomId(room.roomId()).floor(room.floor().shortValue()).x(room.x()).y(room.y())
                .width(room.width()).height(room.height()).build()).toList());
        nodeLayoutRepository.saveAll(request.nodes().stream().map(node -> TwinNodeLayout.builder()
                .layoutId(finalLayout.getId()).nodeType(node.nodeType()).nodeId(node.nodeId())
                .roomId(node.roomId()).x(node.x()).y(node.y()).build()).toList());
        roomLayoutRepository.flush();
        nodeLayoutRepository.flush();
        return toResponse(layout);
    }

    private Map<UUID, Room> validateRooms(UUID homeId, List<TwinRoomLayoutRequest> roomRequests,
                                           List<TwinNodeLayoutRequest> nodeRequests) {
        Set<UUID> roomIds = new HashSet<>();
        for (TwinRoomLayoutRequest room : roomRequests) {
            if (room == null || room.roomId() == null) throw new AppException(ErrorCode.TWIN_LAYOUT_ROOM_INVALID);
            validateRoomGeometry(room);
            if (!roomIds.add(room.roomId())) throw new AppException(ErrorCode.TWIN_LAYOUT_DUPLICATE_ENTRY);
        }
        for (TwinNodeLayoutRequest node : nodeRequests) {
            if (node == null || node.nodeType() == null || node.nodeId() == null || node.nodeId().isBlank()) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
            validateNodeGeometry(node);
            if (node.roomId() != null) roomIds.add(node.roomId());
        }
        Map<UUID, Room> rooms = new HashMap<>();
        roomRepository.findAllById(roomIds).forEach(room -> rooms.put(room.getId(), room));
        if (rooms.size() != roomIds.size() || rooms.values().stream().anyMatch(room -> !homeId.equals(room.getHome().getId()))) {
            throw new AppException(ErrorCode.TWIN_LAYOUT_ROOM_INVALID);
        }
        return rooms;
    }

    private void validateNodes(UUID homeId, List<TwinNodeLayoutRequest> requests, Map<UUID, Room> rooms) {
        Set<String> identities = new HashSet<>();
        for (TwinNodeLayoutRequest request : requests) {
            String identity = request.nodeType() + "\u0000" + request.nodeId();
            if (!identities.add(identity)) throw new AppException(ErrorCode.TWIN_LAYOUT_DUPLICATE_ENTRY);
            if (request.roomId() != null && !rooms.containsKey(request.roomId())) {
                throw new AppException(ErrorCode.TWIN_LAYOUT_ROOM_INVALID);
            }
            if (request.nodeType() == TwinNodeType.DEVICE) validateDevice(homeId, request.nodeId());
            else validateSensor(homeId, request.nodeId());
        }
    }

    private void validateDevice(UUID homeId, String nodeId) {
        UUID deviceId;
        try {
            deviceId = UUID.fromString(nodeId);
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.TWIN_LAYOUT_DEVICE_INVALID);
        }
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.TWIN_LAYOUT_DEVICE_INVALID));
        if (!homeId.equals(device.getHome().getId())) throw new AppException(ErrorCode.TWIN_LAYOUT_DEVICE_INVALID);
    }

    private void validateSensor(UUID homeId, String nodeId) {
        int separator = nodeId.indexOf(':');
        if (separator <= 0 || separator == nodeId.length() - 1) {
            throw new AppException(ErrorCode.TWIN_LAYOUT_SENSOR_INVALID);
        }
        UUID deviceId;
        try {
            deviceId = UUID.fromString(nodeId.substring(0, separator));
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.TWIN_LAYOUT_SENSOR_INVALID);
        }
        String metricType = nodeId.substring(separator + 1);
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new AppException(ErrorCode.TWIN_LAYOUT_SENSOR_INVALID));
        if (!homeId.equals(device.getHome().getId())
                || sensorReadingRepository.findFirstByDeviceIdAndMetricTypeOrderByRecordedAtDescIdDesc(deviceId, metricType).isEmpty()
                || !TwinSnapshotMapper.sensorId(deviceId, metricType).equals(nodeId)) {
            throw new AppException(ErrorCode.TWIN_LAYOUT_SENSOR_INVALID);
        }
    }

    private void validateRoomGeometry(TwinRoomLayoutRequest request) {
        if (request.floor() == null || request.floor() < 1 || request.floor() > 100) {
            throw new AppException(ErrorCode.TWIN_LAYOUT_GEOMETRY_INVALID);
        }
        validateCoordinate(request.x());
        validateCoordinate(request.y());
        validateDimension(request.width());
        validateDimension(request.height());
        if (request.x().add(request.width()).compareTo(BigDecimal.ONE) > 0
                || request.y().add(request.height()).compareTo(BigDecimal.ONE) > 0) {
            throw new AppException(ErrorCode.TWIN_LAYOUT_GEOMETRY_INVALID);
        }
    }

    private void validateNodeGeometry(TwinNodeLayoutRequest request) {
        validateCoordinate(request.x());
        validateCoordinate(request.y());
    }

    private void validateCoordinate(BigDecimal value) {
        if (value == null || value.scale() > 3 || value.precision() > 10
                || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new AppException(ErrorCode.TWIN_LAYOUT_GEOMETRY_INVALID);
        }
    }

    private void validateDimension(BigDecimal value) {
        if (value == null || value.scale() > 3 || value.precision() > 10
                || value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new AppException(ErrorCode.TWIN_LAYOUT_GEOMETRY_INVALID);
        }
    }

    private TwinLayoutResponse toResponse(TwinLayout layout) {
        List<TwinRoomLayout> storedRooms = roomLayoutRepository.findByLayoutIdOrderByRoomId(layout.getId());
        Map<UUID, Room> liveRooms = new HashMap<>();
        roomRepository.findAllById(storedRooms.stream().map(TwinRoomLayout::getRoomId).toList()).forEach(room -> {
            if (layout.getHomeId().equals(room.getHome().getId())) liveRooms.put(room.getId(), room);
        });
        List<TwinRoomLayoutResponse> rooms = storedRooms.stream()
                .filter(row -> liveRooms.containsKey(row.getRoomId()))
                .map(row -> new TwinRoomLayoutResponse(row.getRoomId(), row.getFloor(), row.getX(), row.getY(), row.getWidth(), row.getHeight()))
                .toList();
        List<TwinNodeLayoutResponse> nodes = nodeLayoutRepository.findByLayoutIdOrderByNodeTypeAscNodeIdAsc(layout.getId()).stream()
                .filter(row -> isLiveNode(layout.getHomeId(), row))
                .map(row -> new TwinNodeLayoutResponse(row.getNodeType(), row.getNodeId(),
                        row.getRoomId() == null || liveRooms.containsKey(row.getRoomId()) ? row.getRoomId() : null,
                        row.getX(), row.getY()))
                .toList();
        return new TwinLayoutResponse(layout.getHomeId(), layout.getRevision(), rooms, nodes,
                TwinArchitectureValidation.prune(layout.getArchitecture(),
                        rooms.stream().map(TwinRoomLayoutResponse::roomId).collect(java.util.stream.Collectors.toSet()),
                        nodes.stream().map(n -> n.nodeType() + ":" + n.nodeId()).collect(java.util.stream.Collectors.toSet()),
                        rooms.stream().map(r -> (int) r.floor()).collect(java.util.stream.Collectors.toSet())));
    }

    private boolean isLiveNode(UUID homeId, TwinNodeLayout row) {
        if (row.getNodeType() == TwinNodeType.DEVICE) {
            try {
                Device device = deviceRepository.findById(UUID.fromString(row.getNodeId())).orElse(null);
                return device != null && homeId.equals(device.getHome().getId());
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }
        int separator = row.getNodeId().indexOf(':');
        if (separator <= 0 || separator == row.getNodeId().length() - 1) return false;
        try {
            UUID deviceId = UUID.fromString(row.getNodeId().substring(0, separator));
            String metricType = row.getNodeId().substring(separator + 1);
            Device device = deviceRepository.findById(deviceId).orElse(null);
            return device != null && homeId.equals(device.getHome().getId())
                    && sensorReadingRepository.findFirstByDeviceIdAndMetricTypeOrderByRecordedAtDescIdDesc(deviceId, metricType).isPresent()
                    && TwinSnapshotMapper.sensorId(deviceId, metricType).equals(row.getNodeId());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
