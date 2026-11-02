package com.hesta.backend.dto.request;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Versioned view geometry only; no Room/Device domain fields or runtime state. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TwinArchitectureRequest(Integer version, Map<UUID, RoomGeometry> rooms,
                                      Map<String, Double> nodeRotations, Map<Integer, FloorGeometry> floors) {
    public record Point(double x, double y) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoomGeometry(Shape shape, List<Point> points, Double widthMeters, Double depthMeters,
                               Double floorHeightMeters, Double wallThicknessMeters,
                               List<ObjectPlacement> objects, Boolean autoFurniture) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ObjectPlacement(String id, Kind kind, double x, double z, double width,
                                  double depth, double height, double rotation, String model, Double sill) {}
    public record FloorGeometry(double elevation, double height, double slabThickness) {}
    public enum Shape { RECTANGLE, L_SHAPE, U_SHAPE, CUSTOM }
    public enum Kind { WALL, DOOR, WINDOW, TABLE, SOFA, BED, CABINET, CHAIR, DESK, TV_STAND, WARDROBE,
        KITCHEN_COUNTER, SINK, TOILET, SHOWER, BATHTUB, PLANT, LAMP, REFRIGERATOR }
}
