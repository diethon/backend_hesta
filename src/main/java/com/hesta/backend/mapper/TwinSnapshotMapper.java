package com.hesta.backend.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.response.*;
import com.hesta.backend.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TwinSnapshotMapper {
    private final ObjectMapper objectMapper;

    public TwinDeviceSnapshotResponse device(Device device) {
        return new TwinDeviceSnapshotResponse(device.getId(), roomId(device), device.getName(),
                device.getDeviceType(), device.getIcon(), device.getStatus(),
                objectMapper.valueToTree(device.getCurrentState()), device.getLastSeen());
    }

    public TwinSensorSnapshotResponse sensor(SensorReading reading) {
        Device device = reading.getDevice();
        return new TwinSensorSnapshotResponse(device.getId() + ":" + reading.getMetricType(),
                roomId(device), device.getId(), reading.getMetricType(), reading.getValue(),
                reading.getUnit(), reading.getRecordedAt());
    }

    public TwinHomeSnapshotResponse home(Home home, List<Room> rooms,
                                          List<Device> devices, List<SensorReading> readings) {
        List<TwinDeviceSnapshotResponse> deviceNodes = devices.stream().map(this::device)
                .sorted(Comparator.comparing(TwinDeviceSnapshotResponse::deviceId)).toList();
        List<TwinSensorSnapshotResponse> sensorNodes = readings.stream().map(this::sensor)
                .sorted(Comparator.comparing(TwinSensorSnapshotResponse::sensorId)).toList();
        Map<UUID, List<TwinDeviceSnapshotResponse>> devicesByRoom = deviceNodes.stream()
                .filter(node -> node.roomId() != null)
                .collect(Collectors.groupingBy(TwinDeviceSnapshotResponse::roomId));
        Map<UUID, List<TwinSensorSnapshotResponse>> sensorsByRoom = sensorNodes.stream()
                .filter(node -> node.roomId() != null)
                .collect(Collectors.groupingBy(TwinSensorSnapshotResponse::roomId));
        return new TwinHomeSnapshotResponse(home.getId(), home.getName(), rooms.stream()
                .sorted(Comparator.comparing(Room::getId))
                .map(room -> new TwinRoomSnapshotResponse(room.getId(), home.getId(), room.getName(),
                        room.getIcon(), devicesByRoom.getOrDefault(room.getId(), List.of()),
                        sensorsByRoom.getOrDefault(room.getId(), List.of())))
                .toList(), deviceNodes.stream().filter(node -> node.roomId() == null).toList(),
                sensorNodes.stream().filter(node -> node.roomId() == null).toList());
    }

    private UUID roomId(Device device) {
        return device.getRoom() == null ? null : device.getRoom().getId();
    }
}
