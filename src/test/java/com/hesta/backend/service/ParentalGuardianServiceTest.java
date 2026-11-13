package com.hesta.backend.service;

import com.hesta.backend.dto.command.NotificationEvent;
import com.hesta.backend.dto.request.CreateParentalGuardianRequest;
import com.hesta.backend.dto.request.GuardianSimulateEventRequest;
import com.hesta.backend.dto.response.ParentalGuardianResponse;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.ParentalGuardianRule;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.GuardianStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.ParentalGuardianLogRepository;
import com.hesta.backend.repository.ParentalGuardianRuleRepository;
import com.hesta.backend.service.impl.ParentalGuardianServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ParentalGuardianServiceTest {

    @Mock ParentalGuardianRuleRepository ruleRepository;
    @Mock ParentalGuardianLogRepository logRepository;
    @Mock DeviceRepository deviceRepository;
    @Mock HomeAuthorizationService authorization;
    @Mock DeviceCommandService deviceCommandService;
    @Mock NotificationService notificationService;

    @InjectMocks ParentalGuardianServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final UUID ruleId = UUID.randomUUID();

    private Home home;
    private Device tvDevice;
    private ParentalGuardianRule rule;

    @BeforeEach
    void setUp() {
        User owner = User.builder().id(userId).email("owner@hesta.vn").build();
        home = Home.builder().id(homeId).createdBy(owner).name("My Home").build();
        Room room = Room.builder().id(UUID.randomUUID()).home(home).name("Living room").build();
        tvDevice = Device.builder()
                .id(deviceId)
                .name("Smart TV Phòng Khách")
                .deviceType("LIGHT")
                .room(room)
                .build();

        rule = ParentalGuardianRule.builder()
                .id(ruleId)
                .home(home)
                .targetDevice(tvDevice)
                .name("Giới hạn xem TV khi vắng nhà")
                .enabled(true)
                .parentAway(false)
                .allowedDurationMinutes(45)
                .warningMinutesBefore(5)
                .status(GuardianStatus.IDLE)
                .autoLockout(true)
                .build();
    }

    @Test
    @DisplayName("Tạo quy tắc Giám hộ thành công")
    void testCreateRule() {
        when(authorization.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.of(tvDevice));
        when(ruleRepository.save(any(ParentalGuardianRule.class))).thenAnswer(i -> {
            ParentalGuardianRule r = i.getArgument(0);
            r.setId(ruleId);
            return r;
        });

        CreateParentalGuardianRequest request = CreateParentalGuardianRequest.builder()
                .name("Giới hạn xem TV khi vắng nhà")
                .targetDeviceId(deviceId)
                .allowedDurationMinutes(45)
                .warningMinutesBefore(5)
                .autoLockout(true)
                .build();

        ParentalGuardianResponse response = service.createRule(userId, homeId, request);

        assertThat(response).isNotNull();
        assertThat(response.getName()).isEqualTo("Giới hạn xem TV khi vắng nhà");
        assertThat(response.getAllowedDurationMinutes()).isEqualTo(45);
        assertThat(response.getStatus()).isEqualTo(GuardianStatus.IDLE);
        verify(ruleRepository).save(any(ParentalGuardianRule.class));
        verify(logRepository).save(any());
    }

    @Test
    @DisplayName("Kích hoạt chế độ vắng nhà (Armed) khi phụ huynh ra ngoài")
    void testSetParentAway() {
        when(authorization.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(ruleRepository.findByIdAndHomeId(ruleId, homeId)).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(ParentalGuardianRule.class))).thenAnswer(i -> i.getArgument(0));

        ParentalGuardianResponse response = service.setParentAway(userId, homeId, ruleId, true);

        assertThat(response.isParentAway()).isTrue();
        verify(notificationService).receiveEvent(any(NotificationEvent.class));
        verify(logRepository).save(any());
    }

    @Test
    @DisplayName("Phụ huynh về nhà giữa phiên -> dừng đếm giờ, không ngắt điện sau đó")
    void testParentReturnStopsCountdown() {
        rule.setParentAway(true);
        rule.setStatus(GuardianStatus.COUNTDOWN);
        rule.setSessionStartedAt(java.time.OffsetDateTime.now());
        rule.setSessionExpiresAt(java.time.OffsetDateTime.now().plusMinutes(10));
        when(authorization.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(ruleRepository.findByIdAndHomeId(ruleId, homeId)).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(ParentalGuardianRule.class))).thenAnswer(i -> i.getArgument(0));

        ParentalGuardianResponse response = service.setParentAway(userId, homeId, ruleId, false);

        assertThat(response.getStatus()).isEqualTo(GuardianStatus.IDLE);
        assertThat(rule.getSessionExpiresAt()).isNull();
    }

    @Test
    @DisplayName("Khi phụ huynh vắng nhà và con bật TV -> Bắt đầu đếm ngược thời gian cho phép")
    void testOnDeviceStateChanged_StartsCountdown() {
        rule.setParentAway(true);
        rule.setStatus(GuardianStatus.IDLE);
        when(ruleRepository.findAllByTargetDeviceIdAndEnabledTrue(deviceId)).thenReturn(List.of(rule));
        when(ruleRepository.save(any(ParentalGuardianRule.class))).thenAnswer(i -> i.getArgument(0));

        service.onDeviceStateChanged(homeId, deviceId, Map.of("power", true));

        assertThat(rule.getStatus()).isEqualTo(GuardianStatus.COUNTDOWN);
        assertThat(rule.getSessionStartedAt()).isNotNull();
        assertThat(rule.getSessionExpiresAt()).isNotNull();
        verify(notificationService).receiveEvent(any(NotificationEvent.class));
        verify(logRepository).save(any());
    }

    @Test
    @DisplayName("Khi đang bị khóa cưỡng chế mà con cố tình bật lại -> Tự động cắt nguồn ngay lập tức (Chống lách luật)")
    void testOnDeviceStateChanged_BlocksTamper() {
        rule.setParentAway(true);
        rule.setStatus(GuardianStatus.LOCKED_OUT);
        when(ruleRepository.findAllByTargetDeviceIdAndEnabledTrue(deviceId)).thenReturn(List.of(rule));

        service.onDeviceStateChanged(homeId, deviceId, Map.of("power", true));

        // Cưỡng chế phát lệnh TURN_OFF
        verify(deviceCommandService).sendCommand(eq(deviceId), eq("TURN_OFF"), eq(Map.of()), eq(StateChangeSource.AUTOMATION));
        verify(notificationService).receiveEvent(any(NotificationEvent.class));
        verify(logRepository).save(any());
    }

    @Test
    @DisplayName("Khi hết giờ sử dụng -> Tự động chuyển LOCKED_OUT và phát lệnh cắt nguồn TV")
    void testCheckDueSessions_LockoutEnforced() {
        OffsetDateTime past = OffsetDateTime.now().minusMinutes(1);
        rule.setParentAway(true);
        rule.setStatus(GuardianStatus.COUNTDOWN);
        rule.setSessionStartedAt(past.minusMinutes(45));
        rule.setSessionExpiresAt(past);

        when(ruleRepository.findAllByEnabledTrueAndStatusIn(anyList())).thenReturn(List.of(rule));
        when(ruleRepository.save(any(ParentalGuardianRule.class))).thenAnswer(i -> i.getArgument(0));

        service.checkDueSessions();

        assertThat(rule.getStatus()).isEqualTo(GuardianStatus.LOCKED_OUT);
        verify(deviceCommandService).sendCommand(eq(deviceId), eq("TURN_OFF"), eq(Map.of()), eq(StateChangeSource.AUTOMATION));
        verify(notificationService).receiveEvent(any(NotificationEvent.class));
        verify(logRepository).save(any());
    }

    @Test
    @DisplayName("Phụ huynh duyệt gia hạn thêm 15 phút")
    void testGrantExtension() {
        rule.setStatus(GuardianStatus.LOCKED_OUT);
        when(authorization.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(ruleRepository.findByIdAndHomeId(ruleId, homeId)).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(ParentalGuardianRule.class))).thenAnswer(i -> i.getArgument(0));

        ParentalGuardianResponse response = service.grantExtension(userId, homeId, ruleId, 15);

        assertThat(response.getStatus()).isEqualTo(GuardianStatus.COUNTDOWN);
        verify(deviceCommandService).sendCommand(eq(deviceId), eq("TURN_ON"), eq(Map.of()), eq(StateChangeSource.MANUAL));
        verify(notificationService).receiveEvent(any(NotificationEvent.class));
    }

    @Test
    @DisplayName("Mô phỏng demo: Bố mẹ ra khỏi nhà, bé bật TV, hết giờ và chống lách luật")
    void testSimulate() {
        when(authorization.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(ruleRepository.findByIdAndHomeId(ruleId, homeId)).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(ParentalGuardianRule.class))).thenAnswer(i -> i.getArgument(0));

        // 1. Phụ huynh ra khỏi nhà
        ParentalGuardianResponse r1 = service.simulate(userId, homeId, ruleId,
                GuardianSimulateEventRequest.builder().eventType("PARENT_DEPARTURE").build());
        assertThat(r1.isParentAway()).isTrue();

        // 2. Bé bật TV
        ParentalGuardianResponse r2 = service.simulate(userId, homeId, ruleId,
                GuardianSimulateEventRequest.builder().eventType("DEVICE_POWER_ON").build());
        assertThat(r2.getStatus()).isEqualTo(GuardianStatus.COUNTDOWN);

        // 3. Hết giờ ngay lập tức
        ParentalGuardianResponse r3 = service.simulate(userId, homeId, ruleId,
                GuardianSimulateEventRequest.builder().eventType("FAST_FORWARD_EXPIRE").build());
        assertThat(r3.getStatus()).isEqualTo(GuardianStatus.LOCKED_OUT);
        verify(deviceCommandService).sendCommand(eq(deviceId), eq("TURN_OFF"), eq(Map.of()), eq(StateChangeSource.AUTOMATION));

        // 4. Bé cố tình bật lại -> cưỡng chế ngắt
        ParentalGuardianResponse r4 = service.simulate(userId, homeId, ruleId,
                GuardianSimulateEventRequest.builder().eventType("TAMPER_POWER_ON").build());
        assertThat(r4.getStatus()).isEqualTo(GuardianStatus.LOCKED_OUT);
    }
}
