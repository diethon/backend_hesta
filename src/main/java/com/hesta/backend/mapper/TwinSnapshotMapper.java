package com.hesta.backend.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.response.*;
import com.hesta.backend.entity.*;
import com.hesta.backend.service.TwinHealthStatusResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.Clock;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class TwinSnapshotMapper {
    private final ObjectMapper objectMapper;
    private final TwinHealthStatusResolver healthResolver;
    private final Clock clock;

    public TwinDeviceSnapshotResponse device(Device device) {
        return device(device, clock.instant());
    }

    private TwinDeviceSnapshotResponse device(Device device, Instant evaluatedAt) {
        return new TwinDeviceSnapshotResponse(device.getId(), roomId(device), device.getName(),
                device.getDeviceType(), device.getIcon(), device.getStatus(),
                objectMapper.valueToTree(device.getCurrentState()), device.getLastSeen(),
                healthResolver.resolve(device.getLastSeen(), evaluatedAt));
    }

    public TwinSensorSnapshotResponse sensor(SensorReading reading) {
        return sensor(reading, clock.instant());
    }

    private TwinSensorSnapshotResponse sensor(SensorReading reading, Instant evaluatedAt) {
        Device device = reading.getDevice();
        return new TwinSensorSnapshotResponse(sensorId(device.getId(), reading.getMetricType()),
                roomId(device), device.getId(), reading.getMetricType(), reading.getValue(),
                reading.getUnit(), reading.getRecordedAt(), healthResolver.resolve(reading.getRecordedAt(), evaluatedAt));
    }

    public static String sensorId(UUID deviceId, String metricType) {
        return deviceId + ":" + metricType;
    }

    public TwinHomeSnapshotResponse home(Home home, List<Room> rooms,
                                          List<Device> devices, List<SensorReading> readings) {
        Instant evaluatedAt = clock.instant();
        List<TwinDeviceSnapshotResponse> deviceNodes = devices.stream().map(device -> device(device, evaluatedAt))
                .sorted(Comparator.comparing(TwinDeviceSnapshotResponse::deviceId)).toList();
        List<TwinSensorSnapshotResponse> sensorNodes = readings.stream().map(reading -> sensor(reading, evaluatedAt))
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
