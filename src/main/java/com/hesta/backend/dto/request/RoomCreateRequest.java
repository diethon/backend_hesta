package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomCreateRequest {
    @NotBlank(message = "ROOM_NAME_INVALID")
    @Size(max = 100, message = "ROOM_NAME_INVALID")
    private String name;

    @Size(max = 50)
    private String icon;

    private BigDecimal layoutX;
    private BigDecimal layoutY;
}
