package com.hesta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.enums.EdgeNodeStatus;
import com.hesta.backend.ota.*;
import com.hesta.backend.repository.EdgeNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtaCampaignServiceTest {

    @Mock
    private EdgeNodeRepository edgeNodeRepository;

    @Mock
    private MqttGateway mqttGateway;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OtaCampaignServiceImpl otaCampaignService;

    private FirmwarePackageDto firmwarePackage;
    private EdgeNode node1;
    private EdgeNode node2;

    @BeforeEach
    void setUp() {
        firmwarePackage = FirmwarePackageDto.builder()
                .deviceType("ESP32_RELAY")
                .version("v2.1.0")
                .fileUrl("https://firmware.hesta.vn/releases/v2.1.0.bin")
                .checksumSha256("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                .build();
        otaCampaignService.registerPackage(firmwarePackage);

        node1 = EdgeNode.builder().id(UUID.randomUUID()).nodeCode("node-01").status(EdgeNodeStatus.ONLINE).build();
        node2 = EdgeNode.builder().id(UUID.randomUUID()).nodeCode("node-02").status(EdgeNodeStatus.ONLINE).build();
    }

    @Test
    @DisplayName("Should create and start Canary OTA Campaign")
    void testStartOtaCampaign() {
        when(edgeNodeRepository.findAll()).thenReturn(List.of(node1, node2));

        CreateOtaCampaignRequest req = CreateOtaCampaignRequest.builder()
                .name("Nâng cấp firmware Node Relay")
                .firmwarePackageId(firmwarePackage.getId())
                .failureThresholdPercent(20.0)
                .rolloutStrategy("CANARY")
                .build();

        OtaCampaignDto campaign = otaCampaignService.createAndStartCampaign(req);

        assertNotNull(campaign.getId());
        assertEquals("RUNNING_CANARY_10", campaign.getStatus());
        assertEquals(2, campaign.getTotalNodes());
    }

    @Test
    @DisplayName("Should trigger Auto-Abort when failure rate exceeds 20%")
    void testAutoAbortOnFailureThreshold() {
        when(edgeNodeRepository.findAll()).thenReturn(List.of(node1, node2));

        CreateOtaCampaignRequest req = CreateOtaCampaignRequest.builder()
                .firmwarePackageId(firmwarePackage.getId())
                .failureThresholdPercent(20.0)
                .rolloutStrategy("CANARY")
                .build();

        OtaCampaignDto campaign = otaCampaignService.createAndStartCampaign(req);

        // 1st node fails, 2nd node fails -> failure rate = 100% > 20%
        otaCampaignService.handleNodeOtaResult("node-01", "v2.1.0", false, "CRC checksum mismatch");
        otaCampaignService.handleNodeOtaResult("node-02", "v2.1.0", false, "Flash write error");

        OtaCampaignDto aborted = otaCampaignService.getCampaign(campaign.getId());
        assertEquals("ABORTED_FAILURE_THRESHOLD", aborted.getStatus());
        assertTrue(aborted.getAbortReason().contains("vượt ngưỡng an toàn"));
    }

    @Test
    @DisplayName("Should support manual cancellation by admin")
    void testManualAbort() {
        when(edgeNodeRepository.findAll()).thenReturn(List.of(node1));

        CreateOtaCampaignRequest req = CreateOtaCampaignRequest.builder()
                .firmwarePackageId(firmwarePackage.getId())
                .build();

        OtaCampaignDto campaign = otaCampaignService.createAndStartCampaign(req);
        OtaCampaignDto cancelled = otaCampaignService.abortCampaign(campaign.getId(), "Phát hiện sự cố phần cứng");

        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals("Phát hiện sự cố phần cứng", cancelled.getAbortReason());
    }
}
