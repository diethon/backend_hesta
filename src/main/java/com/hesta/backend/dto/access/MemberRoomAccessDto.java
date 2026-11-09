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
public class MemberRoomAccessDto {
    private UUID roomId;
    private String roomName;

    @JsonProperty("level")
    @JsonAlias({"accessLevel", "level"})
    private AccessLevel level;

    @JsonProperty("accessLevel")
    public AccessLevel getAccessLevel() {
        return level;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.level = accessLevel;
    }

    public static class MemberRoomAccessDtoBuilder {
        public MemberRoomAccessDtoBuilder accessLevel(AccessLevel accessLevel) {
            this.level = accessLevel;
            return this;
        }
    }
}
