package com.hesta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.ota.*;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.EdgeNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtaCampaignServiceTest {

    @Mock private EdgeNodeRepository edgeNodeRepository;
    @Mock private OtaFirmwarePackageRepository packageRepository;
    @Mock private OtaCampaignRepository campaignRepository;
    @Mock private OtaCampaignNodeRepository campaignNodeRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private MqttGateway mqttGateway;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OtaCampaignServiceImpl otaCampaignService;

    @TempDir
    Path tempStorageDir;

    private OtaFirmwarePackageEntity savedPackage;
    private EdgeNode node1;
    private EdgeNode node2;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(otaCampaignService, "storageDir", tempStorageDir.toString());
        ReflectionTestUtils.setField(otaCampaignService, "topicPrefix", "hesta/nodes");
        ReflectionTestUtils.setField(otaCampaignService, "mqttGateway", mqttGateway);
        ReflectionTestUtils.setField(otaCampaignService, "deviceRepository", deviceRepository);

        savedPackage = OtaFirmwarePackageEntity.builder()
                .id(UUID.randomUUID())
                .deviceType("ESP32_RELAY")
                .version("v2.1.0")
                .fileUrl("http://localhost:8080/api/v1/ota/packages/1/download")
                .checksumSha256("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .fileSizeBytes(1024L)
                .uploaded(true)
                .build();

        node1 = EdgeNode.builder().id(UUID.randomUUID()).nodeCode("node-01").deviceType("ESP32_RELAY").status(EdgeNodeStatus.ONLINE).build();
        node2 = EdgeNode.builder().id(UUID.randomUUID()).nodeCode("node-02").deviceType("ESP32_RELAY").status(EdgeNodeStatus.ONLINE).build();
    }

    @Test
    @DisplayName("Should upload .bin file, compute real SHA-256, and generate download URL")
    void testUploadFirmwareBinary() throws Exception {
        byte[] fwBytes = "HESTA-ESP32-FIRMWARE-IMAGE-V2.1.0".getBytes(StandardCharsets.UTF_8);
        String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fwBytes));
        MockMultipartFile file = new MockMultipartFile("file", "relay_v2.1.0.bin", "application/octet-stream", fwBytes);

        when(packageRepository.save(any(OtaFirmwarePackageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        FirmwarePackageDto result = otaCampaignService.uploadPackage(
                file, "ESP32_RELAY", "v2.1.0", "Fix relay debounce", "http://192.168.1.10:8080");

        assertTrue(result.isUploaded());
        assertEquals(expectedSha, result.getChecksumSha256());
        assertEquals(fwBytes.length, result.getFileSizeBytes());
        assertEquals("http://192.168.1.10:8080/api/v1/ota/packages/" + result.getId() + "/download", result.getFileUrl());
    }

    @Test
    @DisplayName("Should reject campaign creation when no ONLINE nodes match target deviceType")
    void testRejectWhenNoMatchingOnlineNodes() {
        EdgeNode offlineNode = EdgeNode.builder().id(UUID.randomUUID()).nodeCode("node-off").deviceType("ESP32_RELAY").status(EdgeNodeStatus.OFFLINE).build();
        EdgeNode acNode = EdgeNode.builder().id(UUID.randomUUID()).nodeCode("node-ac").deviceType("AIR_CONDITIONER").status(EdgeNodeStatus.ONLINE).build();

        when(packageRepository.findById(savedPackage.getId())).thenReturn(Optional.of(savedPackage));
        when(campaignRepository.existsActiveCampaignForDeviceType("ESP32_RELAY")).thenReturn(false);
        when(edgeNodeRepository.findAll()).thenReturn(List.of(offlineNode, acNode));
        when(deviceRepository.findByNodeIdOrNodeCode(any(), anyString())).thenReturn(List.of());

        CreateOtaCampaignRequest req = CreateOtaCampaignRequest.builder()
                .firmwarePackageId(savedPackage.getId())
                .rolloutStrategy("CANARY")
                .build();

        AppException ex = assertThrows(AppException.class, () -> otaCampaignService.createAndStartCampaign(req));
        assertEquals(ErrorCode.OTA_NO_TARGET_NODES, ex.getErrorCode());
    }

    @Test
    @DisplayName("Should dispatch Canary Wave 1 via MQTT and advance to Wave 2 dispatching remaining node")
    void testCanaryWaveAdvancementDispatchesMqtt() {
        when(packageRepository.findById(savedPackage.getId())).thenReturn(Optional.of(savedPackage));
        when(campaignRepository.existsActiveCampaignForDeviceType("ESP32_RELAY")).thenReturn(false);
        when(edgeNodeRepository.findAll()).thenReturn(List.of(node1, node2));
        when(campaignRepository.save(any(OtaCampaignEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        List<OtaCampaignNodeEntity> storedNodes = new ArrayList<>();
        when(campaignNodeRepository.saveAll(any())).thenAnswer(inv -> {
            List<OtaCampaignNodeEntity> list = inv.getArgument(0);
            storedNodes.clear();
            storedNodes.addAll(list);
            return list;
        });

        CreateOtaCampaignRequest req = CreateOtaCampaignRequest.builder()
                .name("Canary Relay")
                .firmwarePackageId(savedPackage.getId())
                .failureThresholdPercent(20.0)
                .rolloutStrategy("CANARY")
                .build();

        OtaCampaignDto campaign = otaCampaignService.createAndStartCampaign(req);
        assertEquals("RUNNING_CANARY_10", campaign.getStatus());
        assertEquals(2, campaign.getTotalNodes());

        // Wave 1 should dispatch only 1 node (10% ceil(2*0.1)=1 -> node-01) across dual topics (nodeCode + nodeId)
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(mqttGateway, times(2)).sendToMqtt(topicCaptor.capture(), eq(1), anyString());
        assertTrue(topicCaptor.getAllValues().containsAll(List.of(
                "hesta/nodes/node-01/ota/notify",
                "hesta/nodes/" + node1.getId() + "/ota/notify"
        )));

        // Simulate Wave 1 success from node-01 -> must advance to Wave 2 (node-02) and send MQTT notify to node-02!
        OtaCampaignEntity entity = OtaCampaignEntity.builder()
                .id(campaign.getId())
                .name(campaign.getName())
                .firmwarePackageId(savedPackage.getId())
                .deviceType("ESP32_RELAY")
                .version("v2.1.0")
                .status("RUNNING_CANARY_10")
                .rolloutStrategy("CANARY")
                .failureThresholdPercent(20.0)
                .currentWave(1)
                .totalNodes(2)
                .build();

        when(campaignRepository.findByIdForUpdate(campaign.getId())).thenReturn(Optional.of(entity));
        when(campaignNodeRepository.findByCampaignIdOrderByWaveAscNodeCodeAsc(campaign.getId())).thenReturn(storedNodes);
        when(edgeNodeRepository.findById(node1.getId())).thenReturn(Optional.of(node1));

        otaCampaignService.handleNodeOtaResult("node-01", campaign.getId().toString(), "v2.1.0", "SUCCESS", 100, null);

        assertEquals("v2.1.0", node1.getFirmwareVersion());
        verify(mqttGateway, times(1)).sendToMqtt(eq("hesta/nodes/node-02/ota/notify"), eq(1), anyString());
        verify(mqttGateway, times(1)).sendToMqtt(eq("hesta/nodes/" + node2.getId() + "/ota/notify"), eq(1), anyString());
    }

    @Test
    @DisplayName("Should target only explicitly selected ONLINE nodes when targetNodeIds is provided")
    void testTargetedOtaCampaignSelection() {
        EdgeNode extraNode = EdgeNode.builder().id(UUID.randomUUID()).nodeCode("node-03").deviceType("LIGHT").status(EdgeNodeStatus.ONLINE).build();
        when(packageRepository.findById(savedPackage.getId())).thenReturn(Optional.of(savedPackage));
        when(campaignRepository.existsActiveCampaignForDeviceType("ESP32_RELAY")).thenReturn(false);
        when(edgeNodeRepository.findAll()).thenReturn(List.of(node1, node2, extraNode));
        when(campaignRepository.save(any(OtaCampaignEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(campaignNodeRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateOtaCampaignRequest req = CreateOtaCampaignRequest.builder()
                .name("Targeted Single Node")
                .firmwarePackageId(savedPackage.getId())
                .rolloutStrategy("IMMEDIATE")
                .targetNodeIds(List.of(node2.getId()))
                .build();

        OtaCampaignDto campaign = otaCampaignService.createAndStartCampaign(req);
        assertEquals(1, campaign.getTotalNodes());
        verify(mqttGateway, times(1)).sendToMqtt(eq("hesta/nodes/node-02/ota/notify"), eq(1), anyString());
        verify(mqttGateway, times(1)).sendToMqtt(eq("hesta/nodes/" + node2.getId() + "/ota/notify"), eq(1), anyString());
        verify(mqttGateway, never()).sendToMqtt(eq("hesta/nodes/node-01/ota/notify"), eq(1), anyString());
        verify(mqttGateway, never()).sendToMqtt(eq("hesta/nodes/" + node1.getId() + "/ota/notify"), eq(1), anyString());
    }
}
