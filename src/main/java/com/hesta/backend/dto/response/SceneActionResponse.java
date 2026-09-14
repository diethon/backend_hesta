package com.hesta.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SceneActionResponse {
    UUID id;
    UUID targetDeviceId;
    String targetDeviceName;
    String action;
    JsonNode value;
    int order;
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;
}
