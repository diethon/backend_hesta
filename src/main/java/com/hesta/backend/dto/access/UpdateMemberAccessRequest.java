package com.hesta.backend.dto.access;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hesta.backend.enums.AccessScope;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMemberAccessRequest {

    @NotNull(message = "scope is required")
    @JsonProperty("scope")
    @JsonAlias({"scope", "accessScope"})
    private AccessScope scope;

    @JsonProperty("accessScope")
    public AccessScope getAccessScope() {
        return scope;
    }

    public void setAccessScope(AccessScope accessScope) {
        this.scope = accessScope;
    }

    @JsonProperty("version")
    @JsonAlias({"version", "expectedVersion"})
    private String version;

    public String getExpectedVersion() {
        return version;
    }

    public void setExpectedVersion(String expectedVersion) {
        this.version = expectedVersion;
    }

    private List<MemberRoomAccessDto> rooms;

    private List<MemberDeviceAccessDto> devices;

    public static class UpdateMemberAccessRequestBuilder {
        public UpdateMemberAccessRequestBuilder accessScope(AccessScope accessScope) {
            this.scope = accessScope;
            return this;
        }

        public UpdateMemberAccessRequestBuilder expectedVersion(Long expectedVersion) {
            this.version = expectedVersion != null ? String.valueOf(expectedVersion) : null;
            return this;
        }

        public UpdateMemberAccessRequestBuilder expectedVersion(String expectedVersion) {
            this.version = expectedVersion;
            return this;
        }
    }
}
