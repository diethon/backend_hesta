package com.hesta.backend.support;

import com.hesta.backend.entity.*;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.enums.DeviceType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TwinFixtures {
    public static final OffsetDateTime TIME = OffsetDateTime.parse("2026-09-17T09:00:00Z");
    public final Home home = Home.builder().id(id(1)).name("My Home").build();
    public final Room living = Room.builder().id(id(11)).home(home).name("Living Room").icon("sofa").build();
    public final Room bedroom = Room.builder().id(id(12)).home(home).name("Bedroom").icon("bed").build();
    public final Device light = device(21, living, "Ceiling light", DeviceType.LIGHT,
            Map.of("power", "ON", "brightness", 80));
    public final Device environment = device(22, bedroom, "Environment sensor", DeviceType.SENSOR,
            Map.of("temperature", 26.4, "humidity", 61));
    public final Device motion = device(23, living, "Motion sensor", DeviceType.SENSOR,
            Map.of("motion", true));
    public final List<Room> rooms = List.of(living, bedroom);
    public final List<Device> devices = List.of(light, environment, motion);
    public final List<SensorReading> readings = List.of(
            reading(101, environment, "TEMPERATURE", "26.400", "°C", TIME),
            reading(102, environment, "HUMIDITY", "61.000", "%", TIME),
            reading(103, motion, "MOTION", "1.000", "boolean", TIME));

    public Device device(int id, Room room, String name, DeviceType type, Map<String, Object> state) {
        return Device.builder().id(id(id)).home(home).room(room).name(name).deviceType(type)
                .status(DeviceStatus.ONLINE).currentState(state).lastSeen(TIME).build();
    }

    public static SensorReading reading(long id, Device device, String metric, String value,
                                         String unit, OffsetDateTime time) {
        return SensorReading.builder().id(id).device(device).metricType(metric)
                .value(new BigDecimal(value)).unit(unit).recordedAt(time).build();
    }

    public static UUID id(int value) {
        return UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", value));
    }
}
