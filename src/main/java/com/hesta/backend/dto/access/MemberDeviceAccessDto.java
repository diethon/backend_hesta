package com.hesta.backend.dto.access;

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
public class MemberDeviceAccessDto {
    private UUID deviceId;
    private String deviceName;
    private UUID roomId;
    private String roomName;
    private AccessLevel accessLevel;
}

