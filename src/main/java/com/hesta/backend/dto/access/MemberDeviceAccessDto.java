package com.hesta.backend.dto.access;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hesta.backend.enums.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemberDeviceAccessDto {
    private UUID deviceId;
    private String deviceName;
    private UUID roomId;
    private String roomName;

    private AccessLevel roomLevel;
    private AccessLevel effectiveLevel;
    private Boolean isOverride;

    @JsonProperty("level")
    @JsonAlias({"accessLevel", "level"})
    private AccessLevel level;

    @JsonProperty("accessLevel")
    public AccessLevel getAccessLevel() {
        return level != null ? level : effectiveLevel;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.level = accessLevel;
        if (this.effectiveLevel == null) {
            this.effectiveLevel = accessLevel;
        }
    }

    public static class MemberDeviceAccessDtoBuilder {
        public MemberDeviceAccessDtoBuilder accessLevel(AccessLevel accessLevel) {
            this.level = accessLevel;
            if (this.effectiveLevel == null) {
                this.effectiveLevel = accessLevel;
            }
            return this;
        }
    }
}
