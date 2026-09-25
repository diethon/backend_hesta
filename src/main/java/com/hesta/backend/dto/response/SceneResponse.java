package com.hesta.backend.dto.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SceneResponse {
    UUID id;
    UUID homeId;
    String name;
    String icon;
    String description;
    boolean enabled;
    @Builder.Default
    List<SceneActionResponse> actions = new ArrayList<>();
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;
}
