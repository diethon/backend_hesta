package com.hesta.backend.dto.access;

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
    @NotNull(message = "accessScope is required")
    private AccessScope accessScope;

    private List<MemberRoomAccessDto> rooms;

    private List<MemberDeviceAccessDto> devices;

    private Long expectedVersion;
}

