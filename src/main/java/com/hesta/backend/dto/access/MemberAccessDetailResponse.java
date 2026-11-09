package com.hesta.backend.dto.access;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hesta.backend.enums.AccessScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemberAccessDetailResponse {
    @JsonProperty("memberUserId")
    @JsonAlias({"memberUserId", "userId"})
    private UUID memberUserId;

    @JsonProperty("userId")
    public UUID getUserId() {
        return memberUserId;
    }

    public void setUserId(UUID userId) {
        this.memberUserId = userId;
    }

    private UUID memberId;
    private String fullName;
    private String role;

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

    private String version;
    private boolean readOnly;

    private List<MemberRoomAccessDto> rooms;
    private List<MemberDeviceAccessDto> devices;

    public static class MemberAccessDetailResponseBuilder {
        public MemberAccessDetailResponseBuilder userId(UUID userId) {
            this.memberUserId = userId;
            return this;
        }

        public MemberAccessDetailResponseBuilder accessScope(AccessScope accessScope) {
            this.scope = accessScope;
            return this;
        }

        public MemberAccessDetailResponseBuilder version(Long versionLong) {
            this.version = versionLong != null ? String.valueOf(versionLong) : null;
            return this;
        }

        public MemberAccessDetailResponseBuilder version(String versionStr) {
            this.version = versionStr;
            return this;
        }
    }
}
