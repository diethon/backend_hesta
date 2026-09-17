package com.hesta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.mapper.TwinSnapshotMapper;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.impl.TwinSnapshotServiceImpl;
import com.hesta.backend.support.TwinFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwinSnapshotServiceTest {
    @Mock HomeRepository homeRepository;
    @Mock HomeMemberRepository homeMemberRepository;
    @Mock RoomRepository roomRepository;
    @Mock DeviceRepository deviceRepository;
    @Mock SensorReadingRepository sensorReadingRepository;
    private final TwinFixtures fixture = new TwinFixtures();
    private final UUID userId = TwinFixtures.id(500);
    private TwinSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new TwinSnapshotServiceImpl(new HomeAuthorizationService(homeRepository, homeMemberRepository),
                roomRepository, deviceRepository, sensorReadingRepository, new TwinSnapshotMapper(new ObjectMapper()));
    }

    @ParameterizedTest
    @EnumSource(HomeRole.class)
    void getSnapshot_activeHomeMember_returnsMappedSnapshot(HomeRole role) {
        when(homeRepository.findById(fixture.home.getId())).thenReturn(Optional.of(fixture.home));
        when(homeMemberRepository.findByHomeIdAndUserId(fixture.home.getId(), userId))
                .thenReturn(Optional.of(HomeMember.builder().role(role).status(MemberStatus.ACTIVE).build()));
        when(roomRepository.findByHomeId(fixture.home.getId())).thenReturn(fixture.rooms);
        when(deviceRepository.findByHomeIdOrderByIdAsc(fixture.home.getId())).thenReturn(fixture.devices);
        when(sensorReadingRepository.findLatestByHomeId(fixture.home.getId())).thenReturn(fixture.readings);

        var result = service.getSnapshot(userId, fixture.home.getId());

        assertThat(result.rooms()).hasSize(2);
        assertThat(result.rooms().get(1).sensors()).hasSize(2);
        verify(sensorReadingRepository).findLatestByHomeId(fixture.home.getId());
    }

    @Test
    void getSnapshot_nonMember_deniesAccessBeforeReadingNodes() {
        when(homeRepository.findById(fixture.home.getId())).thenReturn(Optional.of(fixture.home));
        when(homeMemberRepository.findByHomeIdAndUserId(fixture.home.getId(), userId)).thenReturn(Optional.empty());
        assertDenied(userId, ErrorCode.UNAUTHORIZED);
    }

    @Test
    void getSnapshot_disabledOwner_deniesAccessBeforeReadingNodes() {
        when(homeRepository.findById(fixture.home.getId())).thenReturn(Optional.of(fixture.home));
        when(homeMemberRepository.findByHomeIdAndUserId(fixture.home.getId(), userId))
                .thenReturn(Optional.of(HomeMember.builder().role(HomeRole.OWNER).status(MemberStatus.DISABLED).build()));
        assertDenied(userId, ErrorCode.UNAUTHORIZED);
    }

    @Test
    void getSnapshot_anonymous_deniesAccess() {
        assertDenied(null, ErrorCode.UNAUTHENTICATED);
        verifyNoInteractions(homeRepository, homeMemberRepository);
    }

    @Test
    void getSnapshot_missingHome_returnsNotFound() {
        when(homeRepository.findById(fixture.home.getId())).thenReturn(Optional.empty());
        assertDenied(userId, ErrorCode.HOME_NOT_FOUND);
    }

    private void assertDenied(UUID caller, ErrorCode code) {
        assertThatThrownBy(() -> service.getSnapshot(caller, fixture.home.getId()))
                .isInstanceOfSatisfying(AppException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        verifyNoInteractions(roomRepository, deviceRepository, sensorReadingRepository);
    }
}
