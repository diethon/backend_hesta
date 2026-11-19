package com.hesta.backend.dto.access;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hesta.backend.enums.AccessScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateInvitationRequest {

    private String email;

    @JsonProperty("scope")
    @JsonAlias({"scope", "accessScope"})
    private AccessScope accessScope;

    private List<MemberRoomAccessDto> rooms;

    private List<MemberDeviceAccessDto> devices;

    private InvitationAccessPayload access;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvitationAccessPayload {
        @JsonProperty("scope")
        @JsonAlias({"scope", "accessScope"})
        private AccessScope scope;

        private List<MemberRoomAccessDto> rooms;
        private List<MemberDeviceAccessDto> devices;
    }

    public AccessScope getEffectiveScope() {
        if (access != null && access.getScope() != null) {
            return access.getScope();
        }
        return accessScope != null ? accessScope : AccessScope.ALL;
    }

    public List<MemberRoomAccessDto> getEffectiveRooms() {
        if (access != null && access.getRooms() != null) {
            return access.getRooms();
        }
        return rooms;
    }

    public List<MemberDeviceAccessDto> getEffectiveDevices() {
        if (access != null && access.getDevices() != null) {
            return access.getDevices();
        }
        return devices;
    }
}
