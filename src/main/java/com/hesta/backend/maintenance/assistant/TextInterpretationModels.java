package com.hesta.backend.maintenance.assistant;

import java.util.List;

public final class TextInterpretationModels {

    private TextInterpretationModels() {
    }

    public enum Action {
        SELECT_SYMPTOM,
        ANSWER_STEP_RESOLVED,
        ANSWER_STEP_NOT_RESOLVED,
        ASK_CLARIFY,
        ESCALATE_REQUEST,
        SAFETY_HAZARD,
        OUT_OF_SCOPE
    }

    public record RawInterpretation(
            Action action,
            String symptomId,
            Double confidence,
            String clarifyQuestion,
            String extractedDetails,
            List<String> safetySignals,
            int totalTokens
    ) {
    }

    public enum DecisionType {
        SELECT_SYMPTOM,
        CONFIRM_SWITCH_SYMPTOM,
        ANSWER_STEP_RESOLVED,
        ANSWER_STEP_NOT_RESOLVED,
        ASK_CLARIFY,
        ESCALATE_REQUEST,
        SAFETY_HAZARD,
        OUT_OF_SCOPE,
        FALLBACK_QUICK_REPLY
    }

    public record HandledDecision(
            DecisionType decision,
            String symptomId,
            String clarifyQuestion,
            String extractedDetails,
            List<String> safetySignals,
            boolean aiGenerated
    ) {
        public static HandledDecision fallback(String extractedDetails) {
            return new HandledDecision(DecisionType.FALLBACK_QUICK_REPLY, null, null, extractedDetails, List.of(), false);
        }
    }
}
