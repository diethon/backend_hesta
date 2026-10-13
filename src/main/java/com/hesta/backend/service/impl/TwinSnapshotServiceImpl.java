package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.TwinHomeSnapshotResponse;
import com.hesta.backend.entity.Home;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.RoomRepository;
import com.hesta.backend.repository.SensorReadingRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.TwinSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TwinSnapshotServiceImpl implements TwinSnapshotService {
    private final HomeAuthorizationService homeAuthorizationService;
    private final RoomRepository roomRepository;
    private final DeviceRepository deviceRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final TwinSnapshotMapper mapper;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TwinHomeSnapshotResponse getSnapshot(UUID userId, UUID homeId) {
        Home home = homeAuthorizationService.requireAccess(userId, homeId);
        return mapper.home(home, roomRepository.findByHomeId(homeId),
                deviceRepository.findByHomeIdOrderByIdAsc(homeId),
                sensorReadingRepository.findLatestByHomeId(homeId));
    }
}
