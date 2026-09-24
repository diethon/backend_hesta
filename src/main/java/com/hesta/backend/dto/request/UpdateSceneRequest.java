package com.hesta.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UpdateSceneRequest {

    @NotBlank(message = "SCENE_NAME_INVALID")
    @Size(max = 150, message = "SCENE_NAME_INVALID")
    String name;

    @Size(max = 2000, message = "SCENE_DESCRIPTION_INVALID")
    String description;

    @NotNull(message = "SCENE_ENABLED_REQUIRED")
    Boolean enabled;

    @Valid
    List<SceneActionRequest> actions;
}
