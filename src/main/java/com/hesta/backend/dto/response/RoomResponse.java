package com.hesta.backend.dto.response;

import com.hesta.backend.entity.Room;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomResponse {
    private UUID id;
    private UUID homeId;
    private String name;
    private String icon;
    private BigDecimal layoutX;
    private BigDecimal layoutY;
    private int deviceCount;
    private OffsetDateTime createdAt;

    public static RoomResponse fromEntity(Room room, int deviceCount) {
        if (room == null) return null;
        return RoomResponse.builder()
                .id(room.getId())
                .homeId(room.getHome() != null ? room.getHome().getId() : null)
                .name(room.getName())
                .icon(room.getIcon())
                .layoutX(room.getLayoutX())
                .layoutY(room.getLayoutY())
                .deviceCount(deviceCount)
                .createdAt(room.getCreatedAt())
                .build();
    }
}
