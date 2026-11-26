package com.hesta.backend.maintenance.assistant;

import java.util.List;

public final class ImageAnalysisModels {

    private ImageAnalysisModels() {
    }

    public enum ImageQuality {
        GOOD, BLURRY, TOO_DARK, TOO_FAR, TOO_CLOSE, GLARE, PARTIAL
    }

    public enum ShowsExpectedDevice {
        YES, NO, UNSURE
    }

    public enum HazardSignal {
        BURN_MARKS, MELTED, SWOLLEN, WATER_DAMAGE, EXPOSED_WIRES, SMOKE_RESIDUE, VISIBLE_SPARK
    }

    public enum RetakeReason {
        NONE, BLURRY, DARK, TOO_FAR, TOO_CLOSE, GLARE, WRONG_SUBJECT, NEED_INDICATOR, NEED_LABEL
    }

    public record RawImageAnalysis(
            ImageQuality imageQuality,
            ShowsExpectedDevice showsExpectedDevice,
            List<String> observations,
            String suspectedSymptomId,
            Double confidence,
            List<HazardSignal> hazardSignals,
            Double hazardConfidence,
            RetakeReason retakeReason,
            boolean containsPeopleOrDocuments,
            int totalTokens
    ) {
    }

    public enum DecisionType {
        PRIVACY_REJECT_PEOPLE_OR_DOCS,
        HAZARD_URGENT,
        HAZARD_CAUTION,
        RETAKE_NEEDED,
        RETAKE_LIMIT_ESCALATE,
        TELEMETRY_MISMATCH_OBSERVATIONS,
        CONFIRM_SUSPECTED_SYMPTOM,
        OBSERVATIONS_ONLY,
        FALLBACK_ATTACH_ONLY
    }

    public record HandledImageDecision(
            DecisionType decision,
            ImageQuality imageQuality,
            List<String> observations,
            String suspectedSymptomId,
            String suspectedSymptomLabel,
            List<HazardSignal> hazardSignals,
            String staticAdvice,
            boolean telemetryMismatch,
            boolean suggestDeleteAttachment
    ) {
        public static HandledImageDecision fallback() {
            return new HandledImageDecision(
                    DecisionType.FALLBACK_ATTACH_ONLY,
                    null, List.of(), null, null, List.of(),
                    "Hiện chưa thể phân tích tự động ảnh này. Ảnh vẫn đã được lưu để bạn đính kèm cho kỹ thuật viên xem khi tạo yêu cầu bảo trì.",
                    false, false
            );
        }
    }
}
