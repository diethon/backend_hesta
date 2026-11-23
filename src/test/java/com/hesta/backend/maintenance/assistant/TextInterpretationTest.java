package com.hesta.backend.maintenance.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.ai.AiProperties;
import com.hesta.backend.ai.LlmClient;
import com.hesta.backend.ai.LlmRequest;
import com.hesta.backend.ai.LlmResponse;
import com.hesta.backend.enums.MaintenanceChatState;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.maintenance.playbook.PlaybookDefinition;
import com.hesta.backend.maintenance.playbook.PlaybookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TextInterpretationTest {

    @Mock private LlmClient llmClient;

    private AiProperties aiProperties;
    private PlaybookService playbookService;
    private SafetySignalDetector safetyDetector;
    private TextInterpretationHandler handler;
    private MaintenanceTextInterpreter interpreter;
    private MaintenanceTicketSummarizer summarizer;
    private PlaybookDefinition lightPlaybook;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.setEnabled(true);
        playbookService = new PlaybookService();
        safetyDetector = new SafetySignalDetector();
        handler = new TextInterpretationHandler(aiProperties);
        interpreter = new MaintenanceTextInterpreter(llmClient, aiProperties, new ObjectMapper());
        summarizer = new MaintenanceTicketSummarizer(llmClient, aiProperties, handler);
        lightPlaybook = playbookService.getForDeviceType("LIGHT");
        when(llmClient.isEnabled()).thenReturn(true);
    }

    @Test
    void safetyDetectorCatchesAccentedAndUnaccentedVietnamese() {
        assertThat(safetyDetector.detect("hình như có mùi khét")).isNotEmpty();
        assertThat(safetyDetector.detect("o cam bi xet tia lua va nong bong")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(safetyDetector.detect("cham vao bi giat dien")).isNotEmpty();
        assertThat(safetyDetector.detect("den phong khach bat khong sang")).isEmpty();
    }

    @Test
    void handlerRejectsUnknownSymptomLowConfidenceAndClarifyLimit() {
        // Unknown symptomId -> fallback
        var unknownSym = new TextInterpretationModels.RawInterpretation(
                TextInterpretationModels.Action.SELECT_SYMPTOM, "FAKE_SYMPTOM", 0.95, null, null, List.of(), 50);
        assertThat(handler.evaluate(unknownSym, MaintenanceChatState.SELECT_SYMPTOM, lightPlaybook, null, 0).decision())
                .isEqualTo(TextInterpretationModels.DecisionType.FALLBACK_QUICK_REPLY);

        // Low confidence < 0.6 -> fallback
        var lowConf = new TextInterpretationModels.RawInterpretation(
                TextInterpretationModels.Action.SELECT_SYMPTOM, "LIGHT_OFFLINE", 0.45, null, null, List.of(), 50);
        assertThat(handler.evaluate(lowConf, MaintenanceChatState.SELECT_SYMPTOM, lightPlaybook, null, 0).decision())
                .isEqualTo(TextInterpretationModels.DecisionType.FALLBACK_QUICK_REPLY);

        // ASK_CLARIFY allowed up to 2 consecutive times, 3rd falls back
        var clarify = new TextInterpretationModels.RawInterpretation(
                TextInterpretationModels.Action.ASK_CLARIFY, null, 0.8, "Đèn có nhấp nháy không?", null, List.of(), 50);
        assertThat(handler.evaluate(clarify, MaintenanceChatState.SELECT_SYMPTOM, lightPlaybook, null, 1).decision())
                .isEqualTo(TextInterpretationModels.DecisionType.ASK_CLARIFY);
        assertThat(handler.evaluate(clarify, MaintenanceChatState.SELECT_SYMPTOM, lightPlaybook, null, 2).decision())
                .isEqualTo(TextInterpretationModels.DecisionType.FALLBACK_QUICK_REPLY);
    }

    @Test
    void outputFilterBlocksUrlsPhonesAndElectricalDisassemblyInstructions() {
        assertThat(handler.filterClarifyQuestion("Bạn thử mở nắp tháo vỏ kiểm tra dây 220V nhé"))
                .isEqualTo(TextInterpretationHandler.DEFAULT_CLARIFY_QUESTION);
        assertThat(handler.filterClarifyQuestion("Xem hướng dẫn tại https://example.com"))
                .isEqualTo(TextInterpretationHandler.DEFAULT_CLARIFY_QUESTION);
        assertThat(handler.filterClarifyQuestion("Gọi số 0987654321 để được sửa"))
                .isEqualTo(TextInterpretationHandler.DEFAULT_CLARIFY_QUESTION);
        assertThat(handler.filterClarifyQuestion("Đèn có nhấp nháy hay tắt hẳn?"))
                .isEqualTo("Đèn có nhấp nháy hay tắt hẳn?");
    }

    @Test
    void handlerDetectsSymptomSwitchInGuidedStepAndLlmSafetySignals() {
        var switchSym = new TextInterpretationModels.RawInterpretation(
                TextInterpretationModels.Action.SELECT_SYMPTOM, "LIGHT_FLICKER_OR_COLOR", 0.9, null, null, List.of(), 50);
        assertThat(handler.evaluate(switchSym, MaintenanceChatState.GUIDED_STEP, lightPlaybook, "LIGHT_OFFLINE", 0).decision())
                .isEqualTo(TextInterpretationModels.DecisionType.CONFIRM_SWITCH_SYMPTOM);

        var llmSafety = new TextInterpretationModels.RawInterpretation(
                TextInterpretationModels.Action.SELECT_SYMPTOM, "LIGHT_OFFLINE", 0.8, null, null, List.of("mùi khét"), 50);
        assertThat(handler.evaluate(llmSafety, MaintenanceChatState.SELECT_SYMPTOM, lightPlaybook, null, 0).decision())
                .isEqualTo(TextInterpretationModels.DecisionType.SAFETY_HAZARD);
    }

    @Test
    void interpreterWrapsUntrustedInputTruncatesAndFallsBackOnMalformedJsonOrTimeout() {
        ArgumentCaptor<LlmRequest> reqCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        when(llmClient.generate(reqCaptor.capture())).thenReturn(new LlmResponse(
                "{\"action\":\"SELECT_SYMPTOM\",\"symptomId\":\"LIGHT_OFFLINE\",\"confidence\":0.88,\"safetySignals\":[]}",
                List.of(), "STOP", "gemini-2.5-flash", 95));

        String longInput = "a".repeat(650);
        Optional<TextInterpretationModels.RawInterpretation> parsed = interpreter.interpret(
                new MaintenanceTextInterpreter.InterpretInput(
                        UUID.randomUUID(), MaintenanceChatState.SELECT_SYMPTOM,
                        "LIGHT", "L1", "Phòng khách", "OFFLINE", "2 giờ", "",
                        lightPlaybook, null, "Chọn triệu chứng", List.of(), longInput));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().totalTokens()).isEqualTo(95);
        String sentPrompt = reqCaptor.getValue().messages().get(0).text();
        assertThat(sentPrompt).contains("<UNTRUSTED_USER_MESSAGE>");
        assertThat(sentPrompt).doesNotContain("a".repeat(550));

        // Broken JSON -> empty (fallback)
        when(llmClient.generate(any())).thenReturn(new LlmResponse("{broken json", List.of(), "STOP", "gemini-2.5-flash", 10));
        assertThat(interpreter.interpret(new MaintenanceTextInterpreter.InterpretInput(
                UUID.randomUUID(), MaintenanceChatState.SELECT_SYMPTOM,
                "LIGHT", null, null, "OFFLINE", null, "", lightPlaybook, null, null, List.of(), "hi"))).isEmpty();

        // Timeout / 429 exception -> empty (fallback)
        when(llmClient.generate(any())).thenThrow(new AppException(ErrorCode.AI_SERVICE_RATE_LIMITED));
        assertThat(interpreter.interpret(new MaintenanceTextInterpreter.InterpretInput(
                UUID.randomUUID(), MaintenanceChatState.SELECT_SYMPTOM,
                "LIGHT", null, null, "OFFLINE", null, "", lightPlaybook, null, null, List.of(), "hi"))).isEmpty();
    }

    @Test
    void ticketSummarizerRespectsFeatureFlagAndOutputFilter() {
        assertThat(summarizer.isEnabled()).isFalse();
        aiProperties.getTicketSummary().setEnabled(true);
        assertThat(summarizer.isEnabled()).isTrue();

        when(llmClient.generate(any())).thenReturn(new LlmResponse(
                "Đèn trần phòng khách mất kết nối 2 giờ, đã thử khởi động lại nguồn nhưng chưa khắc phục.",
                List.of(), "STOP", "gemini-2.5-flash", 60));
        var ok = summarizer.summarize(UUID.randomUUID(), "- Thiết bị: Đèn trần");
        assertThat(ok).isPresent();
        assertThat(ok.get().totalTokens()).isEqualTo(60);

        // Unsafe phrase blocked
        when(llmClient.generate(any())).thenReturn(new LlmResponse(
                "Khách nên tháo vỏ và đấu nối lại dây 220V.", List.of(), "STOP", "gemini-2.5-flash", 40));
        assertThat(summarizer.summarize(UUID.randomUUID(), "- Thiết bị: Đèn trần")).isEmpty();
    }
}
