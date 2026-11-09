package com.hesta.backend.dto.access;

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
public class MemberAccessDetailResponse {
    private UUID userId;
    private UUID memberId;
    private String fullName;
    private String role;
    private AccessScope accessScope;
    private List<MemberRoomAccessDto> rooms;
    private List<MemberDeviceAccessDto> devices;
    private Long version;
}

