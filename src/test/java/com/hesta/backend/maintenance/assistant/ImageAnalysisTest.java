package com.hesta.backend.maintenance.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.ai.AiProperties;
import com.hesta.backend.ai.LlmClient;
import com.hesta.backend.ai.LlmResponse;
import com.hesta.backend.maintenance.playbook.PlaybookDefinition;
import com.hesta.backend.maintenance.playbook.PlaybookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageAnalysisTest {

    @Mock private LlmClient llmClient;

    private AiProperties aiProperties;
    private PlaybookService playbookService;
    private ImageSanitizer sanitizer;
    private MaintenanceImageAnalyzer analyzer;
    private ImageAnalysisHandler handler;
    private PlaybookDefinition lightPlaybook;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.setEnabled(true);
        playbookService = new PlaybookService();
        sanitizer = new ImageSanitizer();
        TextInterpretationHandler textHandler = new TextInterpretationHandler(aiProperties);
        analyzer = new MaintenanceImageAnalyzer(llmClient, aiProperties, new ObjectMapper());
        handler = new ImageAnalysisHandler(aiProperties, textHandler);
        lightPlaybook = playbookService.getForDeviceType("LIGHT");
        when(llmClient.isEnabled()).thenReturn(true);
    }

    @Test
    void sanitizerStripsFakeExifGpsAndResizesLargeImageInMemory() throws Exception {
        // Create a 2000x1000 JPEG and inject a fake APP1 EXIF GPS marker right after SOI (FF D8)
        BufferedImage img = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, 2000, 1000);
        g.dispose();

        ByteArrayOutputStream rawBaos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", rawBaos);
        byte[] cleanJpeg = rawBaos.toByteArray();

        byte[] fakeExifPayload = "Exif\0\0FAKE_GPS_21.0285N_105.8542E".getBytes(StandardCharsets.US_ASCII);
        int segLen = fakeExifPayload.length + 2;
        ByteArrayOutputStream withExif = new ByteArrayOutputStream();
        withExif.write(cleanJpeg[0]); // 0xFF
        withExif.write(cleanJpeg[1]); // 0xD8
        withExif.write(0xFF);
        withExif.write(0xE1); // APP1 EXIF marker
        withExif.write((segLen >> 8) & 0xFF);
        withExif.write(segLen & 0xFF);
        withExif.write(fakeExifPayload);
        withExif.write(cleanJpeg, 2, cleanJpeg.length - 2);

        byte[] inputWithExif = withExif.toByteArray();
        assertThat(new String(inputWithExif, StandardCharsets.ISO_8859_1)).contains("FAKE_GPS_21.0285N");

        Optional<ImageSanitizer.SanitizedImage> sanitizedOpt = sanitizer.sanitize(inputWithExif, 1280);
        assertThat(sanitizedOpt).isPresent();
        ImageSanitizer.SanitizedImage sanitized = sanitizedOpt.get();
        assertThat(sanitized.width()).isEqualTo(1280);
        assertThat(sanitized.height()).isEqualTo(640);
        assertThat(sanitized.mimeType()).isEqualTo("image/jpeg");
        assertThat(new String(sanitized.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("FAKE_GPS_21.0285N");
    }

    @Test
    void handlerRejectsPeopleOrDocumentsAndSuggestsDelete() {
        var raw = new ImageAnalysisModels.RawImageAnalysis(
                ImageAnalysisModels.ImageQuality.GOOD, ImageAnalysisModels.ShowsExpectedDevice.YES,
                List.of("Thấy đèn"), null, 0.9, List.of(), 0.0,
                ImageAnalysisModels.RetakeReason.NONE, true, 100);
        var decision = handler.evaluate(raw, lightPlaybook, "OFFLINE", false, 0);
        assertThat(decision.decision()).isEqualTo(ImageAnalysisModels.DecisionType.PRIVACY_REJECT_PEOPLE_OR_DOCS);
        assertThat(decision.suggestDeleteAttachment()).isTrue();
        assertThat(decision.observations()).isEmpty();
    }

    @Test
    void handlerAppliesAsymmetricHazardThresholds() {
        var cautionRaw = new ImageAnalysisModels.RawImageAnalysis(
                ImageAnalysisModels.ImageQuality.GOOD, ImageAnalysisModels.ShowsExpectedDevice.YES,
                List.of("Có vết sạm nhỏ trên nhựa"), null, 0.7,
                List.of(ImageAnalysisModels.HazardSignal.BURN_MARKS), 0.45,
                ImageAnalysisModels.RetakeReason.NONE, false, 100);
        assertThat(handler.evaluate(cautionRaw, lightPlaybook, "OFFLINE", false, 0).decision())
                .isEqualTo(ImageAnalysisModels.DecisionType.HAZARD_CAUTION);

        var urgentRaw = new ImageAnalysisModels.RawImageAnalysis(
                ImageAnalysisModels.ImageQuality.GOOD, ImageAnalysisModels.ShowsExpectedDevice.YES,
                List.of("Nhựa chảy biến dạng rõ rệt"), null, 0.85,
                List.of(ImageAnalysisModels.HazardSignal.MELTED), 0.85,
                ImageAnalysisModels.RetakeReason.NONE, false, 100);
        assertThat(handler.evaluate(urgentRaw, lightPlaybook, "OFFLINE", false, 0).decision())
                .isEqualTo(ImageAnalysisModels.DecisionType.HAZARD_URGENT);
    }

    @Test
    void handlerEnforcesStaticRetakeAdviceAndTwoRetakeLimit() {
        var blurryRaw = new ImageAnalysisModels.RawImageAnalysis(
                ImageAnalysisModels.ImageQuality.BLURRY, ImageAnalysisModels.ShowsExpectedDevice.YES,
                List.of(), null, 0.4, List.of(), 0.0,
                ImageAnalysisModels.RetakeReason.BLURRY, false, 80);

        var retake1 = handler.evaluate(blurryRaw, lightPlaybook, "OFFLINE", false, 0);
        assertThat(retake1.decision()).isEqualTo(ImageAnalysisModels.DecisionType.RETAKE_NEEDED);
        assertThat(retake1.staticAdvice()).contains("Ảnh bị mờ");

        var retakeLimit = handler.evaluate(blurryRaw, lightPlaybook, "OFFLINE", false, 2);
        assertThat(retakeLimit.decision()).isEqualTo(ImageAnalysisModels.DecisionType.RETAKE_LIMIT_ESCALATE);
    }

    @Test
    void handlerPrioritizesTelemetryOnMismatchAndFiltersUnsafeObservations() {
        var normalLookingOffline = new ImageAnalysisModels.RawImageAnalysis(
                ImageAnalysisModels.ImageQuality.GOOD, ImageAnalysisModels.ShowsExpectedDevice.YES,
                List.of("Đèn báo tắt", "Hãy tháo vỏ kiểm tra dây 220V", "Xem thêm tại https://evil.com"),
                null, 0.85, List.of(), 0.0,
                ImageAnalysisModels.RetakeReason.NONE, false, 90);

        var decision = handler.evaluate(normalLookingOffline, lightPlaybook, "OFFLINE", true, 0);
        assertThat(decision.decision()).isEqualTo(ImageAnalysisModels.DecisionType.TELEMETRY_MISMATCH_OBSERVATIONS);
        assertThat(decision.telemetryMismatch()).isTrue();
        assertThat(decision.staticAdvice()).doesNotContain("bình thường");
        assertThat(decision.observations()).containsExactly("Đèn báo tắt");
    }

    @Test
    void handlerRejectsUnknownSymptomAndAsksConfirmationForValidSymptom() {
        var unknownSym = new ImageAnalysisModels.RawImageAnalysis(
                ImageAnalysisModels.ImageQuality.GOOD, ImageAnalysisModels.ShowsExpectedDevice.YES,
                List.of("Vỡ chao đèn"), "UNKNOWN_SYM", 0.9, List.of(), 0.0,
                ImageAnalysisModels.RetakeReason.NONE, false, 90);
        assertThat(handler.evaluate(unknownSym, lightPlaybook, "ONLINE", false, 0).decision())
                .isEqualTo(ImageAnalysisModels.DecisionType.FALLBACK_ATTACH_ONLY);

        var validSym = new ImageAnalysisModels.RawImageAnalysis(
                ImageAnalysisModels.ImageQuality.GOOD, ImageAnalysisModels.ShowsExpectedDevice.YES,
                List.of("Chao đèn bị nứt vỡ"), "LIGHT_PHYSICAL_DAMAGE", 0.9, List.of(), 0.0,
                ImageAnalysisModels.RetakeReason.NONE, false, 90);
        var confirm = handler.evaluate(validSym, lightPlaybook, "ONLINE", false, 0);
        assertThat(confirm.decision()).isEqualTo(ImageAnalysisModels.DecisionType.CONFIRM_SUSPECTED_SYMPTOM);
        assertThat(confirm.suspectedSymptomId()).isEqualTo("LIGHT_PHYSICAL_DAMAGE");
    }

    @Test
    void analyzerFallsBackOnMalformedJsonOrInvalidEnum() {
        when(llmClient.generate(any())).thenReturn(new LlmResponse("{bad json", List.of(), "STOP", "gemini-3.5-flash-lite", 10));
        assertThat(analyzer.analyze(new MaintenanceImageAnalyzer.AnalyzeInput(
                UUID.randomUUID(), "LIGHT", null, "OFFLINE", null, "", null, lightPlaybook,
                new byte[]{1, 2, 3}, "image/jpeg", null))).isEmpty();

        when(llmClient.generate(any())).thenReturn(new LlmResponse(
                "{\"imageQuality\":\"WEIRD_ENUM\",\"showsExpectedDevice\":\"YES\",\"observations\":[],\"confidence\":0.9,\"hazardSignals\":[],\"hazardConfidence\":0,\"retakeReason\":\"NONE\",\"containsPeopleOrDocuments\":false}",
                List.of(), "STOP", "gemini-3.5-flash-lite", 10));
        assertThat(analyzer.analyze(new MaintenanceImageAnalyzer.AnalyzeInput(
                UUID.randomUUID(), "LIGHT", null, "OFFLINE", null, "", null, lightPlaybook,
                new byte[]{1, 2, 3}, "image/jpeg", null))).isEmpty();
    }
}
