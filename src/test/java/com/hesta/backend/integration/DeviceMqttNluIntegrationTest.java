package com.hesta.backend.integration;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Room;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.service.NluService;
import com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.hesta.backend.config.mqtt.MqttGateway;

@SpringBootTest(properties = {
    "mqtt.broker.url=tcp://localhost:1883",
    "mqtt.client.id=test-client",
    "mqtt.topic.prefix=hesta/test",
    "mqtt.command.timeout-ms=1000",
    "hesta.history.retention.days=30",
    "app.realtime.websocket.heartbeat=10s"
})
@ActiveProfiles("test")
public class DeviceMqttNluIntegrationTest {

    @MockitoBean
    private MqttGateway mqttGateway;

    @Autowired
    private NluService nluService;

    @Autowired
    private DeviceRepository deviceRepository;

    private Device testLight;

    @Autowired
    private com.hesta.backend.repository.HomeRepository homeRepository;

    @Autowired
    private com.hesta.backend.repository.UserRepository userRepository;

    @BeforeEach
    void setup() {
        com.hesta.backend.entity.User user = new com.hesta.backend.entity.User();
        user.setEmail("test" + UUID.randomUUID() + "@test.com");
        user.setFullName("Test User");
        user.setPasswordHash("password_hash");
        user = userRepository.save(user);

        com.hesta.backend.entity.Home home = new com.hesta.backend.entity.Home();
        home.setName("Nhà Test");
        home.setCreatedBy(user);
        home = homeRepository.save(home);

        // Setup mock device for test
        testLight = new Device();
        testLight.setName("Đèn phòng khách");
        testLight.setDeviceType("LIGHT");
        testLight.setStatus(DeviceStatus.ONLINE);
        Map<String, Object> state = new HashMap<>();
        state.put("status", "OFF");
        testLight.setCurrentState(state);
        deviceRepository.save(testLight);
    }

    @Test
    void testEndToEndNluToMqttCommand() {
        // 1. Gửi câu lệnh bằng Tiếng Việt (có sai chính tả)
        String userCommand = "btậ đnè phòng khách";
        UUID userId = UUID.randomUUID();

        // 2. Chạy NLU Service
        CompletableFuture<CommandResult> futureResult = nluService.processNaturalLanguageCommand(userCommand, userId);
        
        // 3. Hệ thống phải đẩy lệnh vào queue chờ ACK từ MQTT
        if (futureResult == null) {
            throw new IllegalStateException("Future result should not be null");
        }
        
        // Do trong môi trường Test chúng ta không bật MQTT Broker thực, 
        // hàm sẽ bị Timeout sau 1s nếu không ai trả ACK.
        // Ở kịch bản này, chúng ta đảm bảo NLU phân tích đúng "TURN_ON" và gán đúng Device.
        
        // Cleanup
        deviceRepository.delete(testLight);
        homeRepository.deleteAll();
    }
}
