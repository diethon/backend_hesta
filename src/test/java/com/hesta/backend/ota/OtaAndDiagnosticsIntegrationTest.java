package com.hesta.backend.ota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.config.mqtt.MqttMessageReceiver;
import com.hesta.backend.diagnostics.MqttDiagnosticsService;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.enums.PlatformRole;
import com.hesta.backend.repository.EdgeNodeRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OtaAndDiagnosticsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OtaCampaignService otaCampaignService;
    @Autowired private OtaCampaignRepository campaignRepository;
    @Autowired private OtaCampaignNodeRepository campaignNodeRepository;
    @Autowired private OtaFirmwarePackageRepository packageRepository;
    @Autowired private EdgeNodeRepository edgeNodeRepository;
    @Autowired private HomeRepository homeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MqttMessageReceiver mqttMessageReceiver;
    @Autowired private MqttDiagnosticsService diagnosticsService;

    @MockitoBean
    private MqttGateway mqttGateway;

    @TempDir
    static Path tempDir;

    private UsernamePasswordAuthenticationToken adminAuth;
    private UsernamePasswordAuthenticationToken userAuth;
    private Home testHome;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(otaCampaignService, "storageDir", tempDir.toString());
        ReflectionTestUtils.setField(diagnosticsService, "pingTimeoutMs", 1500L);
        ReflectionTestUtils.setField(diagnosticsService, "mqttGateway", mqttGateway);
        ReflectionTestUtils.setField(otaCampaignService, "mqttGateway", mqttGateway);

        campaignNodeRepository.deleteAll();
        campaignRepository.deleteAll();
        packageRepository.deleteAll();
        edgeNodeRepository.deleteAll();

        User owner = userRepository.save(User.builder()
                .email("ota-owner-" + UUID.randomUUID() + "@hesta.vn")
                .fullName("Owner")
                .platformRole(PlatformRole.USER)
                .status(AccountStatus.ACTIVE)
                .build());
        testHome = homeRepository.save(Home.builder()
                .name("OTA Test Home")
                .createdBy(owner)
                .build());

        CustomUserDetails adminDetails = new CustomUserDetails(
                UUID.randomUUID(), "admin@hesta.vn", "", AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        adminAuth = new UsernamePasswordAuthenticationToken(adminDetails, null, adminDetails.getAuthorities());

        CustomUserDetails userDetails = new CustomUserDetails(
                owner.getId(), owner.getEmail(), "", AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        userAuth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    @Test
    @DisplayName("Full Flow: Upload .bin -> Anonymous ESP32 Download -> 10-node Canary 10/50/100% -> EdgeNode firmware updated")
    void fullCanaryOtaRolloutEndToEnd() throws Exception {
        List<EdgeNode> nodes = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            nodes.add(edgeNodeRepository.save(EdgeNode.builder()
                    .home(testHome)
                    .nodeCode(String.format("ESP32-RELAY-%02d", i))
                    .status(EdgeNodeStatus.ONLINE)
                    .deviceType("ESP32_RELAY")
                    .firmwareVersion("v1.0.0")
                    .build()));
        }

        byte[] binary = "ESP32_FIRMWARE_BINARY_CONTENT_V2_2_0".getBytes(StandardCharsets.UTF_8);
        String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        MockMultipartFile file = new MockMultipartFile("file", "relay_v2.2.0.bin", "application/octet-stream", binary);

        // Non-admin rejected
        mockMvc.perform(multipart("/api/v1/admin/ota/packages/upload")
                        .file(file)
                        .param("deviceType", "ESP32_RELAY")
                        .param("version", "v2.2.0")
                        .with(authentication(userAuth)))
                .andExpect(status().isForbidden());

        // Admin upload
        MvcResult uploadRes = mockMvc.perform(multipart("/api/v1/admin/ota/packages/upload")
                        .file(file)
                        .param("deviceType", "ESP32_RELAY")
                        .param("version", "v2.2.0")
                        .param("releaseNotes", "Release build")
                        .with(authentication(adminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.checksumSha256").value(expectedSha))
                .andExpect(jsonPath("$.result.uploaded").value(true))
                .andReturn();

        JsonNode pkgJson = objectMapper.readTree(uploadRes.getResponse().getContentAsString()).get("result");
        String packageId = pkgJson.get("id").asText();

        // ESP32 downloads binary without JWT (permitAll)
        mockMvc.perform(get("/api/v1/ota/packages/" + packageId + "/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Firmware-Sha256", expectedSha))
                .andExpect(content().bytes(binary));

        // Track MQTT notify dispatches across waves
        Map<Integer, List<String>> dispatchedTopics = new HashMap<>();
        List<String> waveTopics = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            String topic = inv.getArgument(0);
            if (topic.endsWith("/ota/notify")) {
                waveTopics.add(topic);
            }
            return null;
        }).when(mqttGateway).sendToMqtt(anyString(), anyInt(), anyString());

        // Start CANARY campaign
        CreateOtaCampaignRequest req = CreateOtaCampaignRequest.builder()
                .name("Relay v2.2.0 Release")
                .firmwarePackageId(UUID.fromString(packageId))
                .rolloutStrategy("CANARY")
                .failureThresholdPercent(20.0)
                .build();

        MvcResult campRes = mockMvc.perform(post("/api/v1/admin/ota/campaigns")
                        .with(authentication(adminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("RUNNING_CANARY_10"))
                .andExpect(jsonPath("$.result.totalNodes").value(10))
                .andReturn();

        String campaignId = objectMapper.readTree(campRes.getResponse().getContentAsString()).get("result").get("id").asText();

        // Wave 1: ceil(10 * 0.10) = 1 node dispatched
        assertEquals(1, waveTopics.size());
        dispatchedTopics.put(1, new ArrayList<>(waveTopics));
        waveTopics.clear();

        // Duplicate intermediate + terminal messages via MqttMessageReceiver -> advances to Wave 2 (4 more nodes = 50% cumulative)
        String wave1Node = dispatchedTopics.get(1).get(0).split("/")[2];
        mqttMessageReceiver.handleMessage(MessageBuilder.withPayload(
                        objectMapper.writeValueAsString(Map.of("campaignId", campaignId, "version", "v2.2.0", "status", "DOWNLOADING")))
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + wave1Node + "/ota/status")
                .build());
        mqttMessageReceiver.handleMessage(MessageBuilder.withPayload(
                        objectMapper.writeValueAsString(Map.of("campaignId", campaignId, "version", "v2.2.0", "status", "SUCCESS")))
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + wave1Node + "/ota/status")
                .build());
        // Duplicate QoS-1 delivery must be idempotent
        mqttMessageReceiver.handleMessage(MessageBuilder.withPayload(
                        objectMapper.writeValueAsString(Map.of("campaignId", campaignId, "version", "v2.2.0", "status", "SUCCESS")))
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + wave1Node + "/ota/status")
                .build());

        assertEquals(4, waveTopics.size());
        dispatchedTopics.put(2, new ArrayList<>(waveTopics));
        waveTopics.clear();

        OtaCampaignDto wave2State = otaCampaignService.getCampaign(UUID.fromString(campaignId));
        assertEquals("RUNNING_CANARY_50", wave2State.getStatus());
        assertEquals(1, wave2State.getSuccessNodes());

        // Report success from all 4 Wave 2 nodes -> advances to Wave 3 (5 remaining nodes = 100%)
        for (String topic : dispatchedTopics.get(2)) {
            String nc = topic.split("/")[2];
            mqttMessageReceiver.handleMessage(MessageBuilder.withPayload(
                            objectMapper.writeValueAsString(Map.of("campaignId", campaignId, "version", "v2.2.0", "status", "SUCCESS")))
                    .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nc + "/ota/status")
                    .build());
        }

        assertEquals(5, waveTopics.size());
        dispatchedTopics.put(3, new ArrayList<>(waveTopics));
        waveTopics.clear();
        assertEquals("RUNNING_FULL_100", otaCampaignService.getCampaign(UUID.fromString(campaignId)).getStatus());

        // Report success from Wave 3 nodes -> COMPLETED and all 10 EdgeNodes updated to v2.2.0
        for (String topic : dispatchedTopics.get(3)) {
            String nc = topic.split("/")[2];
            mqttMessageReceiver.handleMessage(MessageBuilder.withPayload(
                            objectMapper.writeValueAsString(Map.of("campaignId", campaignId, "version", "v2.2.0", "status", "SUCCESS")))
                    .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/" + nc + "/ota/status")
                    .build());
        }

        OtaCampaignDto finalDto = otaCampaignService.getCampaign(UUID.fromString(campaignId));
        assertEquals("COMPLETED", finalDto.getStatus());
        assertEquals(10, finalDto.getSuccessNodes());
        assertEquals(0, finalDto.getFailedNodes());
        for (EdgeNode en : edgeNodeRepository.findAll()) {
            assertEquals("v2.2.0", en.getFirmwareVersion());
        }
    }

    @Test
    @DisplayName("Canary Safety: Wave 1 failure (1/1 = 100% > 20%) aborts BEFORE Wave 2 dispatch")
    void canaryWave1FailureAutoAbortsBeforeWave2() {
        for (int i = 1; i <= 5; i++) {
            edgeNodeRepository.save(EdgeNode.builder()
                    .home(testHome)
                    .nodeCode("CANARY-NODE-" + i)
                    .status(EdgeNodeStatus.ONLINE)
                    .deviceType("ESP32_RELAY")
                    .build());
        }

        FirmwarePackageDto pkg = otaCampaignService.registerPackage(FirmwarePackageDto.builder()
                .deviceType("ESP32_RELAY")
                .version("v3.0.0")
                .fileUrl("https://firmware.hesta.vn/v3.0.0.bin")
                .checksumSha256("a".repeat(64))
                .build());

        OtaCampaignDto campaign = otaCampaignService.createAndStartCampaign(CreateOtaCampaignRequest.builder()
                .name("Faulty Firmware")
                .firmwarePackageId(pkg.getId())
                .failureThresholdPercent(20.0)
                .rolloutStrategy("CANARY")
                .build());

        // Only Wave 1 (1 node) was notified
        verify(mqttGateway, times(1)).sendToMqtt(eq("hesta/nodes/CANARY-NODE-1/ota/notify"), eq(1), anyString());

        otaCampaignService.handleNodeOtaResult("CANARY-NODE-1", campaign.getId().toString(), "v3.0.0", "FAILED", 0, "SHA256 mismatch");

        OtaCampaignDto aborted = otaCampaignService.getCampaign(campaign.getId());
        assertEquals("ABORTED_FAILURE_THRESHOLD", aborted.getStatus());
        // Must NEVER notify Wave 2 nodes!
        verify(mqttGateway, never()).sendToMqtt(contains("CANARY-NODE-2"), anyInt(), anyString());
    }

    @Test
    @DisplayName("Admin Diagnostics Ping Controller: Real PONG returns 200 OK; silent node returns 504 Gateway Timeout")
    void adminDiagnosticsPingIntegration() throws Exception {
        EdgeNode node = edgeNodeRepository.save(EdgeNode.builder()
                .home(testHome)
                .nodeCode("PING-NODE-01")
                .status(EdgeNodeStatus.ONLINE)
                .build());

        // 1. Silent node -> 504 Gateway Timeout (not fake 25ms)
        ReflectionTestUtils.setField(diagnosticsService, "pingTimeoutMs", 100L);
        mockMvc.perform(post("/api/v1/admin/diagnostics/nodes/" + node.getId() + "/ping")
                        .with(authentication(adminAuth)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.message", containsString("không phản hồi")));

        // 2. Responding node via MqttMessageReceiver /pong -> 200 OK with measured RTT
        ReflectionTestUtils.setField(diagnosticsService, "pingTimeoutMs", 2000L);
        doAnswer(inv -> {
            String topic = inv.getArgument(0);
            String payload = inv.getArgument(2);
            if (topic.endsWith("/ping")) {
                String pingId = objectMapper.readTree(payload).get("pingId").asText();
                new Thread(() -> mqttMessageReceiver.handleMessage(
                        MessageBuilder.withPayload("{\"pingId\":\"" + pingId + "\"}")
                                .setHeader(MqttHeaders.RECEIVED_TOPIC, "hesta/nodes/PING-NODE-01/pong")
                                .build())).start();
            }
            return null;
        }).when(mqttGateway).sendToMqtt(anyString(), anyInt(), anyString());

        mockMvc.perform(post("/api/v1/admin/diagnostics/nodes/" + node.getId() + "/ping")
                        .with(authentication(adminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.nodeCode").value("PING-NODE-01"))
                .andExpect(jsonPath("$.result.averageLatencyMs").isNumber());
    }

    @Test
    @DisplayName("Manual Abort & Stale Watchdog: abort broadcasts ota/abort to active nodes; watchdog times out stuck nodes")
    void manualAbortAndStaleWatchdog() throws Exception {
        for (int i = 1; i <= 5; i++) {
            edgeNodeRepository.save(EdgeNode.builder()
                    .home(testHome)
                    .nodeCode("WD-NODE-" + i)
                    .status(EdgeNodeStatus.ONLINE)
                    .deviceType("ESP32_RELAY")
                    .build());
        }

        FirmwarePackageDto pkg = otaCampaignService.registerPackage(FirmwarePackageDto.builder()
                .deviceType("ESP32_RELAY")
                .version("v4.0.0")
                .fileUrl("https://firmware.hesta.vn/v4.0.0.bin")
                .checksumSha256("b".repeat(64))
                .build());

        OtaCampaignDto camp = otaCampaignService.createAndStartCampaign(CreateOtaCampaignRequest.builder()
                .name("Watchdog Campaign")
                .firmwarePackageId(pkg.getId())
                .failureThresholdPercent(20.0)
                .rolloutStrategy("CANARY")
                .build());

        // Abort via HTTP API -> must send /ota/abort ONLY to Wave 1 (WD-NODE-1)
        mockMvc.perform(post("/api/v1/admin/ota/campaigns/" + camp.getId() + "/abort")
                        .param("reason", "Hủy test")
                        .with(authentication(adminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("CANCELLED"));

        verify(mqttGateway, times(1)).sendToMqtt(eq("hesta/nodes/WD-NODE-1/ota/abort"), eq(1), anyString());
        verify(mqttGateway, never()).sendToMqtt(contains("WD-NODE-2/ota/abort"), anyInt(), anyString());

        // Start a second IMMEDIATE campaign on a single node, backdate updatedAt, and run timeout watchdog
        FirmwarePackageDto pkg2 = otaCampaignService.registerPackage(FirmwarePackageDto.builder()
                .deviceType("ESP32_RELAY")
                .version("v4.0.1")
                .fileUrl("https://firmware.hesta.vn/v4.0.1.bin")
                .checksumSha256("c".repeat(64))
                .build());
        OtaCampaignDto camp2 = otaCampaignService.createAndStartCampaign(CreateOtaCampaignRequest.builder()
                .name("Stale Timeout Test")
                .firmwarePackageId(pkg2.getId())
                .rolloutStrategy("IMMEDIATE")
                .build());

        List<OtaCampaignNodeEntity> nodes2 = campaignNodeRepository.findByCampaignIdOrderByWaveAscNodeCodeAsc(camp2.getId());
        for (OtaCampaignNodeEntity n : nodes2) {
            n.setUpdatedAt(OffsetDateTime.now().minusMinutes(10));
        }
        campaignNodeRepository.saveAll(nodes2);

        // Trigger watchdog via Spring bean target
        OtaCampaignServiceImpl targetImpl = org.springframework.test.util.AopTestUtils.getTargetObject(otaCampaignService);
        targetImpl.timeoutStaleOtaNodes();

        OtaCampaignDto timedOut = otaCampaignService.getCampaign(camp2.getId());
        // Node 1 + Node 2 timeout -> 100% failure > 20% threshold -> triggers auto-abort and cancels remaining 3 nodes
        assertEquals(2, timedOut.getFailedNodes());
        assertEquals("ABORTED_FAILURE_THRESHOLD", timedOut.getStatus());
        long cancelledCount = timedOut.getNodeExecutions().stream()
                .filter(n -> "CANCELLED".equals(n.getStatus()))
                .count();
        assertEquals(3L, cancelledCount);
    }
}
