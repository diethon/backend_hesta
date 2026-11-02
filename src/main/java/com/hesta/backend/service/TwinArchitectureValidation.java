package com.hesta.backend.service;

import com.hesta.backend.dto.request.TwinArchitectureRequest;
import com.hesta.backend.dto.request.TwinRoomLayoutRequest;
import com.hesta.backend.dto.request.TwinNodeLayoutRequest;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Same limits for controller and direct service callers; checked before any writes. */
public final class TwinArchitectureValidation {
    private TwinArchitectureValidation() {}
    public static void validate(TwinArchitectureRequest a, List<TwinRoomLayoutRequest> rooms, List<TwinNodeLayoutRequest> nodes) {
        if (a == null) return; // Legacy PUT omits geometry and preserves the stored document.
        require(Integer.valueOf(1).equals(a.version()) && a.rooms() != null && a.rooms().size() <= 500);
        Set<UUID> ids = rooms.stream().map(TwinRoomLayoutRequest::roomId).collect(Collectors.toSet());
        require(ids.containsAll(a.rooms().keySet()));
        Set<String> objectIds = new HashSet<>();
        a.rooms().forEach((id, room) -> {
            require(room != null && room.shape() != null && room.points() != null
                    && room.points().size() >= 3 && room.points().size() <= 64);
            double area = 0;
            for (int i = 0; i < room.points().size(); i++) {
                var p = room.points().get(i); var q = room.points().get((i + 1) % room.points().size());
                require(p != null && q != null && range(p.x(), 0, 1) && range(p.y(), 0, 1));
                area += p.x() * q.y() - q.x() * p.y();
            }
            require(Math.abs(area) > .000001);
            require(optional(room.widthMeters(), .01, 100) && optional(room.depthMeters(), .01, 100)
                    && optional(room.floorHeightMeters(), .5, 10) && optional(room.wallThicknessMeters(), .01, 1));
            require(room.objects() == null || room.objects().size() <= 250);
            if (room.objects() != null) for (var o : room.objects()) {
                require(o != null && o.id() != null && !o.id().isBlank() && o.id().length() <= 150 && objectIds.add(o.id()) && o.kind() != null
                        && range(o.x(), 0, 100) && range(o.z(), 0, 100) && range(o.width(), .001, 100)
                        && range(o.depth(), .001, 100) && range(o.height(), .001, 20) && range(o.rotation(), -360, 360)
                        && optional(o.sill(), 0, 10) && (o.model() == null || Set.of("armchair", "coffee_table", "nightstand", "office_chair", "shelf", "washbasin").contains(o.model())));
            }
        });
        if (a.nodeRotations() != null) {
            Set<String> identities = nodes.stream().map(n -> n.nodeType() + ":" + n.nodeId()).collect(Collectors.toSet());
            require(a.nodeRotations().size() <= nodes.size() && identities.containsAll(a.nodeRotations().keySet()));
            a.nodeRotations().values().forEach(angle -> require(angle != null && range(angle, -360, 360)));
        }
        if (a.floors() != null) {
            Set<Integer> levels = rooms.stream().map(TwinRoomLayoutRequest::floor).collect(Collectors.toSet());
            require(a.floors().size() <= 100 && levels.containsAll(a.floors().keySet()));
            a.floors().values().forEach(f -> require(f != null && range(f.elevation(), 0, 1000) && range(f.height(), .5, 10) && range(f.slabThickness(), .05, 1)));
        }
    }
    /** Removed domain objects cannot leave dangling architecture in a legacy PUT or GET. */
    public static TwinArchitectureRequest prune(TwinArchitectureRequest a, Set<UUID> rooms, Set<String> nodes, Set<Integer> floors) {
        if (a == null) return null;
        return new TwinArchitectureRequest(a.version(), filter(a.rooms(), rooms), filter(a.nodeRotations(), nodes), filter(a.floors(), floors));
    }
    private static <K,V> Map<K,V> filter(Map<K,V> input, Set<K> keys) {
        return input == null ? null : input.entrySet().stream().filter(e -> keys.contains(e.getKey())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    private static boolean optional(Double n, double min, double max) { return n == null || range(n, min, max); }
    private static boolean range(double n, double min, double max) { return Double.isFinite(n) && n >= min && n <= max; }
    private static void require(boolean valid) { if (!valid) throw new AppException(ErrorCode.TWIN_LAYOUT_GEOMETRY_INVALID); }
}
