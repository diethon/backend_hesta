package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.response.MlModelStatusResponse;
import com.hesta.backend.dto.response.RecommendationResponse;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.HomeMember;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.HomeRole;
import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.enums.NotificationType;
import com.hesta.backend.repository.BehaviorEventRepository;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.service.MlServiceClient;
import com.hesta.backend.service.NotificationService;
import com.hesta.backend.service.RecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationSchedulerTest {

    @Mock HomeRepository homeRepository;
    @Mock HomeMemberRepository homeMemberRepository;
    @Mock RecommendationService recommendationService;
    @Mock MlServiceClient mlServiceClient;
    @Mock BehaviorEventRepository behaviorEventRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock NotificationService notificationService;

    @InjectMocks RecommendationScheduler scheduler;

    private final UUID homeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Home home = Home.builder().id(homeId).name("Nhà Thông Minh").build();
    private final User user = User.builder().id(userId).email("owner@hesta.com").build();
    private final HomeMember ownerMember = HomeMember.builder()
            .home(home)
            .user(user)
            .role(HomeRole.OWNER)
            .status(MemberStatus.ACTIVE)
            .build();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "autoGenerateEnabled", true);
        ReflectionTestUtils.setField(scheduler, "lookbackDays", 14);
        ReflectionTestUtils.setField(scheduler, "autoRetrainEnabled", true);
        ReflectionTestUtils.setField(scheduler, "minNewEvents", 100);
        ReflectionTestUtils.setField(scheduler, "notificationService", notificationService);
    }

    @Test
    void autoGenerateSucceedsAndNotifiesHomeOwner() {
        when(homeRepository.findAll()).thenReturn(List.of(home));
        when(jdbcTemplate.update(anyString(), eq("REC_GENERATE"), eq(homeId), eq(LocalDate.now()))).thenReturn(1);
        when(recommendationService.generateForSystem(eq(homeId), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(RecommendationResponse.builder().id(UUID.randomUUID()).build()));
        when(homeMemberRepository.findByHomeIdWithUser(homeId)).thenReturn(List.of(ownerMember));

        scheduler.runAutoGenerate();

        verify(recommendationService).generateForSystem(eq(homeId), any(OffsetDateTime.class), any(OffsetDateTime.class));
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationService).create(eventCaptor.capture());

        NotificationEvent sentEvent = eventCaptor.getValue();
        assertThat(sentEvent.userId()).isEqualTo(userId);
        assertThat(sentEvent.homeId()).isEqualTo(homeId);
        assertThat(sentEvent.type()).isEqualTo(NotificationType.AUTOMATION);
        assertThat(sentEvent.title()).isEqualTo("Gợi ý tự động hóa mới");
    }

    @Test
    void autoGenerateSkipsWhenAlreadyClaimedByAnotherNode() {
        when(homeRepository.findAll()).thenReturn(List.of(home));
        when(jdbcTemplate.update(anyString(), eq("REC_GENERATE"), eq(homeId), eq(LocalDate.now()))).thenReturn(0);

        scheduler.runAutoGenerate();

        verifyNoInteractions(recommendationService, notificationService);
    }

    @Test
    void autoRetrainSkipsWhenInsufficientNewEvents() {
        when(jdbcTemplate.update(anyString(), eq("REC_RETRAIN"), any(UUID.class), eq(LocalDate.now()))).thenReturn(1);
        when(behaviorEventRepository.countByOccurredAtAfter(any(OffsetDateTime.class))).thenReturn(45L);

        scheduler.runAutoRetrain();

        verify(behaviorEventRepository).countByOccurredAtAfter(any(OffsetDateTime.class));
        verifyNoInteractions(mlServiceClient);
    }

    @Test
    void autoRetrainTriggersWhenCriteriaMet() {
        when(jdbcTemplate.update(anyString(), eq("REC_RETRAIN"), any(UUID.class), eq(LocalDate.now()))).thenReturn(1);
        when(behaviorEventRepository.countByOccurredAtAfter(any(OffsetDateTime.class))).thenReturn(150L);
        when(mlServiceClient.triggerRetraining(isNull())).thenReturn(
                MlModelStatusResponse.builder().status("READY").version("rf-v2").accuracy(0.92).build()
        );

        scheduler.runAutoRetrain();

        verify(mlServiceClient).triggerRetraining(isNull());
    }
}
