package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.ai.AiProperties;
import com.hesta.backend.enums.MaintenanceChatState;
import com.hesta.backend.maintenance.playbook.PlaybookDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Kiểm định cứng đầu ra từ LLM (enum, symptomId thuộc playbook, ngưỡng confidence,
 * giới hạn ASK_CLARIFY liên tiếp, và bộ lọc đầu ra cấm URL/SĐT/thao tác điện).
 */
@Component
@RequiredArgsConstructor
public class TextInterpretationHandler {

    public static final String DEFAULT_CLARIFY_QUESTION =
            "Bạn có thể mô tả rõ hơn biểu hiện bên ngoài hoặc chọn triệu chứng gần nhất bên dưới không?";

    private static final Pattern URL_PATTERN = Pattern.compile("(https?://|www\\.)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)(?:0|\\+84)[\\d.\\-\\s]{8,13}(?!\\d)");

    private final AiProperties aiProperties;

    public TextInterpretationModels.HandledDecision evaluate(
            TextInterpretationModels.RawInterpretation raw,
            MaintenanceChatState state,
            PlaybookDefinition playbook,
            String currentSymptomId,
            int consecutiveClarifies) {

        if (raw == null || raw.action() == null || raw.confidence() == null) {
            return TextInterpretationModels.HandledDecision.fallback(null);
        }
        double confidence = raw.confidence();
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            return TextInterpretationModels.HandledDecision.fallback(null);
        }

        String safeDetails = sanitizeDetails(raw.extractedDetails());

        // Lớp an toàn 2: bất kỳ safetySignals nào hoặc action SAFETY_HAZARD -> nhánh an toàn ngay
        if (raw.action() == TextInterpretationModels.Action.SAFETY_HAZARD
                || (raw.safetySignals() != null && !raw.safetySignals().isEmpty())) {
            return new TextInterpretationModels.HandledDecision(
                    TextInterpretationModels.DecisionType.SAFETY_HAZARD,
                    null,
                    null,
                    safeDetails,
                    raw.safetySignals() != null ? raw.safetySignals() : List.of(),
                    true
            );
        }

        double threshold = aiProperties.getTextInterpreter().getConfidenceThreshold();
        int maxClarifies = aiProperties.getTextInterpreter().getMaxConsecutiveClarifies();

        return switch (raw.action()) {
            case SELECT_SYMPTOM -> handleSelectSymptom(raw, state, playbook, currentSymptomId, confidence, threshold, safeDetails);
            case ANSWER_STEP_RESOLVED -> {
                if (state != MaintenanceChatState.GUIDED_STEP || confidence < threshold) {
                    yield TextInterpretationModels.HandledDecision.fallback(safeDetails);
                }
                yield new TextInterpretationModels.HandledDecision(
                        TextInterpretationModels.DecisionType.ANSWER_STEP_RESOLVED, null, null, safeDetails, List.of(), true);
            }
            case ANSWER_STEP_NOT_RESOLVED -> {
                if (state != MaintenanceChatState.GUIDED_STEP || confidence < threshold) {
                    yield TextInterpretationModels.HandledDecision.fallback(safeDetails);
                }
                yield new TextInterpretationModels.HandledDecision(
                        TextInterpretationModels.DecisionType.ANSWER_STEP_NOT_RESOLVED, null, null, safeDetails, List.of(), true);
            }
            case ESCALATE_REQUEST -> {
                if (confidence < threshold) {
                    yield TextInterpretationModels.HandledDecision.fallback(safeDetails);
                }
                yield new TextInterpretationModels.HandledDecision(
                        TextInterpretationModels.DecisionType.ESCALATE_REQUEST, null, null, safeDetails, List.of(), true);
            }
            case OUT_OF_SCOPE -> {
                if (confidence < threshold) {
                    yield TextInterpretationModels.HandledDecision.fallback(safeDetails);
                }
                yield new TextInterpretationModels.HandledDecision(
                        TextInterpretationModels.DecisionType.OUT_OF_SCOPE, null, null, null, List.of(), true);
            }
            case ASK_CLARIFY -> {
                if (consecutiveClarifies >= maxClarifies) {
                    yield TextInterpretationModels.HandledDecision.fallback(safeDetails);
                }
                String filtered = filterClarifyQuestion(raw.clarifyQuestion());
                yield new TextInterpretationModels.HandledDecision(
                        TextInterpretationModels.DecisionType.ASK_CLARIFY, null, filtered, safeDetails, List.of(), true);
            }
            case SAFETY_HAZARD -> new TextInterpretationModels.HandledDecision(
                    TextInterpretationModels.DecisionType.SAFETY_HAZARD, null, null, safeDetails, List.of(), true);
        };
    }

    private TextInterpretationModels.HandledDecision handleSelectSymptom(
            TextInterpretationModels.RawInterpretation raw,
            MaintenanceChatState state,
            PlaybookDefinition playbook,
            String currentSymptomId,
            double confidence,
            double threshold,
            String safeDetails) {
        if (playbook == null || playbook.getSymptoms() == null || raw.symptomId() == null) {
            return TextInterpretationModels.HandledDecision.fallback(safeDetails);
        }
        Set<String> validIds = playbook.getSymptoms().stream()
                .map(PlaybookDefinition.Symptom::getId)
                .collect(Collectors.toSet());
        if (!validIds.contains(raw.symptomId())) {
            return TextInterpretationModels.HandledDecision.fallback(safeDetails);
        }
        if (confidence < threshold) {
            return TextInterpretationModels.HandledDecision.fallback(safeDetails);
        }
        // Đang ở GUIDED_STEP mà user mô tả triệu chứng mới khác hẳn -> hỏi xác nhận đổi triệu chứng, không tự đổi
        if (state == MaintenanceChatState.GUIDED_STEP
                && currentSymptomId != null
                && !currentSymptomId.equals(raw.symptomId())) {
            return new TextInterpretationModels.HandledDecision(
                    TextInterpretationModels.DecisionType.CONFIRM_SWITCH_SYMPTOM,
                    raw.symptomId(),
                    null,
                    safeDetails,
                    List.of(),
                    true);
        }
        if (state != MaintenanceChatState.SELECT_SYMPTOM && state != MaintenanceChatState.SHOW_STATUS) {
            return TextInterpretationModels.HandledDecision.fallback(safeDetails);
        }
        return new TextInterpretationModels.HandledDecision(
                TextInterpretationModels.DecisionType.SELECT_SYMPTOM,
                raw.symptomId(),
                null,
                safeDetails,
                List.of(),
                true);
    }

    public String filterClarifyQuestion(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return DEFAULT_CLARIFY_QUESTION;
        }
        String trimmed = candidate.trim();
        int maxLen = aiProperties.getTextInterpreter().getMaxClarifyChars();
        if (trimmed.length() > maxLen) {
            return DEFAULT_CLARIFY_QUESTION;
        }
        if (URL_PATTERN.matcher(trimmed).find() || PHONE_PATTERN.matcher(trimmed).find()) {
            return DEFAULT_CLARIFY_QUESTION;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String normalized = SafetySignalDetector.normalizeVietnamese(trimmed);
        for (String forbidden : aiProperties.getTextInterpreter().getForbiddenOutputPhrases()) {
            if (forbidden == null || forbidden.isBlank()) continue;
            if (lower.contains(forbidden.toLowerCase(Locale.ROOT))
                    || normalized.contains(SafetySignalDetector.normalizeVietnamese(forbidden))) {
                return DEFAULT_CLARIFY_QUESTION;
            }
        }
        return trimmed;
    }

    private static String sanitizeDetails(String details) {
        if (details == null || details.isBlank()) {
            return null;
        }
        String trimmed = details.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
    }
}
