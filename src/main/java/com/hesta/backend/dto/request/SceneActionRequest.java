package com.hesta.backend.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SceneActionRequest {

    @NotNull(message = "DEVICE_NOT_FOUND")
    UUID targetDeviceId;

    @NotBlank(message = "SCENE_ACTION_INVALID")
    @Size(max = 50, message = "SCENE_ACTION_INVALID")
    String action;

    JsonNode value;

    @NotNull(message = "SCENE_ACTION_ORDER_INVALID")
    @Min(value = 0, message = "SCENE_ACTION_ORDER_INVALID")
    @Max(value = 32767, message = "SCENE_ACTION_ORDER_INVALID")
    Integer order;
}
