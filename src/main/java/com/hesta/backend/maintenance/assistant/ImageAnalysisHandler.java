package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.ai.AiProperties;
import com.hesta.backend.maintenance.playbook.PlaybookDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Kiểm định cứng kết quả phân tích ảnh từ LLM:
 * - Bảng tĩnh RetakeReason -> câu hướng dẫn tiếng Việt cố định (KHÔNG dùng text tự do của LLM cho lời khuyên).
 * - Ngưỡng hazard bất đối xứng (mặc định >= 0.40 CAUTION, >= 0.70 URGENT).
 * - Ưu tiên telemetry khi mâu thuẫn (không bao giờ kết luận "thiết bị bình thường" nếu đang offline/có anomaly).
 * - Lọc đầu ra observations (bỏ URL/SĐT/từ khóa tháo mở điện).
 */
@Component
@RequiredArgsConstructor
public class ImageAnalysisHandler {

    private static final Map<ImageAnalysisModels.RetakeReason, String> RETAKE_ADVICE = Map.of(
            ImageAnalysisModels.RetakeReason.BLURRY, "Ảnh bị mờ hoặc rung tay. Bạn vui lòng giữ chắc điện thoại, chạm lấy nét vào thân thiết bị rồi chụp lại.",
            ImageAnalysisModels.RetakeReason.DARK, "Ảnh hơi tối nên khó quan sát chi tiết ngoài vỏ. Bạn hãy bật thêm đèn phòng hoặc chụp ở nơi đủ sáng hơn.",
            ImageAnalysisModels.RetakeReason.TOO_FAR, "Ảnh chụp hơi xa thiết bị. Bạn vui lòng đứng gần hơn sao cho thiết bị nằm rõ trong khung hình.",
            ImageAnalysisModels.RetakeReason.TOO_CLOSE, "Ảnh chụp quá sát nên bị thiếu toàn cảnh vỏ thiết bị. Bạn hãy lùi lại một chút để thấy rõ tổng thể mặt ngoài.",
            ImageAnalysisModels.RetakeReason.GLARE, "Ảnh bị lóa sáng/chói đèn. Bạn hãy đổi nhẹ góc chụp hoặc tắt đèn flash để thấy rõ bề mặt thiết bị.",
            ImageAnalysisModels.RetakeReason.WRONG_SUBJECT, "Ảnh chưa cho thấy đúng thiết bị đang kiểm tra. Bạn vui lòng hướng camera vào đúng thiết bị cần hỗ trợ.",
            ImageAnalysisModels.RetakeReason.NEED_INDICATOR, "Chưa nhìn rõ đèn báo trạng thái trên thiết bị. Bạn hãy chụp rõ phần đèn LED/màn hình báo trạng thái bên ngoài.",
            ImageAnalysisModels.RetakeReason.NEED_LABEL, "Chưa nhìn rõ tem thông tin mặt ngoài. Bạn hãy chụp rõ tem model/vỏ ngoài nếu dễ tiếp cận (không tháo thiết bị).",
            ImageAnalysisModels.RetakeReason.NONE, "Ảnh chưa đủ rõ để nhận diện chi tiết. Bạn vui lòng chụp lại rõ nét, đủ sáng và chỉ chụp mặt ngoài thiết bị."
    );

    private static final Map<ImageAnalysisModels.ImageQuality, ImageAnalysisModels.RetakeReason> QUALITY_TO_REASON = Map.of(
            ImageAnalysisModels.ImageQuality.BLURRY, ImageAnalysisModels.RetakeReason.BLURRY,
            ImageAnalysisModels.ImageQuality.TOO_DARK, ImageAnalysisModels.RetakeReason.DARK,
            ImageAnalysisModels.ImageQuality.TOO_FAR, ImageAnalysisModels.RetakeReason.TOO_FAR,
            ImageAnalysisModels.ImageQuality.TOO_CLOSE, ImageAnalysisModels.RetakeReason.TOO_CLOSE,
            ImageAnalysisModels.ImageQuality.GLARE, ImageAnalysisModels.RetakeReason.GLARE,
            ImageAnalysisModels.ImageQuality.PARTIAL, ImageAnalysisModels.RetakeReason.TOO_CLOSE
    );

    private final AiProperties aiProperties;
    private final TextInterpretationHandler outputFilter;

    public ImageAnalysisModels.HandledImageDecision evaluate(
            ImageAnalysisModels.RawImageAnalysis raw,
            PlaybookDefinition playbook,
            String deviceStatus,
            boolean hasOpenAnomaly,
            int retakeCount) {

        if (raw == null
                || raw.imageQuality() == null
                || raw.showsExpectedDevice() == null
                || raw.confidence() == null
                || raw.hazardConfidence() == null
                || Double.isNaN(raw.confidence()) || raw.confidence() < 0.0 || raw.confidence() > 1.0
                || Double.isNaN(raw.hazardConfidence()) || raw.hazardConfidence() < 0.0 || raw.hazardConfidence() > 1.0) {
            return ImageAnalysisModels.HandledImageDecision.fallback();
        }

        // 1. Quyền riêng tư: chứa mặt người hoặc giấy tờ cá nhân -> từ chối kết quả, gợi ý xóa ảnh
        if (raw.containsPeopleOrDocuments()) {
            return new ImageAnalysisModels.HandledImageDecision(
                    ImageAnalysisModels.DecisionType.PRIVACY_REJECT_PEOPLE_OR_DOCS,
                    raw.imageQuality(),
                    List.of(),
                    null, null, List.of(),
                    "Ảnh có chứa người hoặc giấy tờ/thông tin cá nhân nên hệ thống không sử dụng kết quả phân tích. Bạn vui lòng xóa ảnh này khỏi hội thoại và chụp lại chỉ tập trung vào vỏ ngoài thiết bị.",
                    false,
                    true
            );
        }

        List<String> safeObservations = filterObservations(raw.observations());
        AiProperties.ImageAnalyzer cfg = aiProperties.getImageAnalyzer();

        // 2. Dấu hiệu nguy hiểm (ngưỡng bất đối xứng: thấp hơn ngưỡng phân loại thường)
        List<ImageAnalysisModels.HazardSignal> hazards = raw.hazardSignals() != null ? raw.hazardSignals() : List.of();
        if (!hazards.isEmpty()) {
            if (raw.hazardConfidence() >= cfg.getHazardUrgentThreshold()) {
                return new ImageAnalysisModels.HandledImageDecision(
                        ImageAnalysisModels.DecisionType.HAZARD_URGENT,
                        raw.imageQuality(),
                        safeObservations,
                        null, null, hazards,
                        "Phát hiện dấu hiệu nguy hiểm rõ ràng trên hình ảnh thiết bị. Hãy ngưng sử dụng và ngắt nguồn an toàn ngay.",
                        false,
                        false
                );
            }
            if (raw.hazardConfidence() >= cfg.getHazardCautionThreshold()) {
                return new ImageAnalysisModels.HandledImageDecision(
                        ImageAnalysisModels.DecisionType.HAZARD_CAUTION,
                        raw.imageQuality(),
                        safeObservations,
                        null, null, hazards,
                        "Ảnh có dấu hiệu nghi ngờ mất an toàn bề mặt thiết bị.",
                        false,
                        false
                );
            }
        }

        // 3. Chất lượng ảnh kém / sai đối tượng / độ tin cậy thấp -> không kết luận, khuyên chụp lại bằng câu tĩnh
        boolean lowQuality = raw.imageQuality() != ImageAnalysisModels.ImageQuality.GOOD
                || raw.showsExpectedDevice() != ImageAnalysisModels.ShowsExpectedDevice.YES
                || raw.confidence() < cfg.getConfidenceThreshold();
        if (lowQuality) {
            if (retakeCount >= cfg.getMaxRetakes()) {
                return new ImageAnalysisModels.HandledImageDecision(
                        ImageAnalysisModels.DecisionType.RETAKE_LIMIT_ESCALATE,
                        raw.imageQuality(),
                        safeObservations,
                        null, null, List.of(),
                        "Ảnh vẫn chưa đủ rõ để trợ lý nhận diện tự động. Bạn có thể tạo yêu cầu bảo trì kèm ảnh này để kỹ thuật viên kiểm tra trực tiếp.",
                        false,
                        false
                );
            }
            ImageAnalysisModels.RetakeReason reason = resolveRetakeReason(raw);
            String advice = buildStaticRetakeAdvice(reason, playbook);
            return new ImageAnalysisModels.HandledImageDecision(
                    ImageAnalysisModels.DecisionType.RETAKE_NEEDED,
                    raw.imageQuality(),
                    safeObservations,
                    null, null, List.of(),
                    advice,
                    false,
                    false
            );
        }

        // 4. Validate suspectedSymptomId thuộc playbook
        PlaybookDefinition.Symptom matchedSymptom = null;
        if (raw.suspectedSymptomId() != null && playbook != null && playbook.getSymptoms() != null) {
            matchedSymptom = playbook.getSymptoms().stream()
                    .filter(s -> s.getId().equals(raw.suspectedSymptomId()))
                    .findFirst()
                    .orElse(null);
            if (matchedSymptom == null) {
                // symptomId lạ không thuộc playbook -> coi là lỗi phân tích, fallback
                return ImageAnalysisModels.HandledImageDecision.fallback();
            }
        }

        // 5. Ưu tiên telemetry khi mâu thuẫn (thiết bị offline/anomaly nhưng ảnh không thấy hư hại rõ)
        boolean telemetryBad = "OFFLINE".equalsIgnoreCase(deviceStatus)
                || "ERROR".equalsIgnoreCase(deviceStatus)
                || hasOpenAnomaly;
        if (telemetryBad && matchedSymptom == null) {
            return new ImageAnalysisModels.HandledImageDecision(
                    ImageAnalysisModels.DecisionType.TELEMETRY_MISMATCH_OBSERVATIONS,
                    raw.imageQuality(),
                    safeObservations,
                    null, null, List.of(),
                    "Ảnh không cho thấy hư hại rõ ràng bên ngoài, nhưng dữ liệu hệ thống ghi nhận thiết bị vẫn đang mất kết nối/có cảnh báo. Bạn hãy tiếp tục làm theo các bước kiểm tra nguồn điện và mạng Wi-Fi/gateway.",
                    true,
                    false
            );
        }

        // 6. Có triệu chứng nghi ngờ hợp lệ -> hỏi user xác nhận (KHÔNG tự chọn)
        if (matchedSymptom != null) {
            return new ImageAnalysisModels.HandledImageDecision(
                    ImageAnalysisModels.DecisionType.CONFIRM_SUSPECTED_SYMPTOM,
                    raw.imageQuality(),
                    safeObservations,
                    matchedSymptom.getId(),
                    matchedSymptom.getLabel(),
                    List.of(),
                    "Dựa trên quan sát ngoài vỏ (chưa được kiểm chứng), có phải thiết bị đang gặp tình trạng: \"" + matchedSymptom.getLabel() + "\"?",
                    false,
                    false
            );
        }

        return new ImageAnalysisModels.HandledImageDecision(
                ImageAnalysisModels.DecisionType.OBSERVATIONS_ONLY,
                raw.imageQuality(),
                safeObservations,
                null, null, List.of(),
                "Đã ghi nhận quan sát sơ bộ từ ảnh (chưa được kiểm chứng). Bạn hãy chọn triệu chứng bên dưới hoặc tiếp tục các bước kiểm tra.",
                false,
                false
        );
    }

    private ImageAnalysisModels.RetakeReason resolveRetakeReason(ImageAnalysisModels.RawImageAnalysis raw) {
        if (raw.showsExpectedDevice() == ImageAnalysisModels.ShowsExpectedDevice.NO) {
            return ImageAnalysisModels.RetakeReason.WRONG_SUBJECT;
        }
        if (raw.retakeReason() != null && raw.retakeReason() != ImageAnalysisModels.RetakeReason.NONE) {
            return raw.retakeReason();
        }
        return QUALITY_TO_REASON.getOrDefault(raw.imageQuality(), ImageAnalysisModels.RetakeReason.NONE);
    }

    private String buildStaticRetakeAdvice(ImageAnalysisModels.RetakeReason reason, PlaybookDefinition playbook) {
        String base = RETAKE_ADVICE.getOrDefault(reason, RETAKE_ADVICE.get(ImageAnalysisModels.RetakeReason.NONE));
        if (playbook != null && playbook.getImageHints() != null && !playbook.getImageHints().isEmpty()) {
            String hints = playbook.getImageHints().stream()
                    .limit(3)
                    .map(h -> "• " + h)
                    .collect(Collectors.joining("\n"));
            return base + "\nGợi ý góc chụp ngoài vỏ:\n" + hints;
        }
        return base;
    }

    private List<String> filterObservations(List<String> rawObservations) {
        if (rawObservations == null || rawObservations.isEmpty()) {
            return List.of();
        }
        List<String> clean = new ArrayList<>();
        for (String obs : rawObservations) {
            if (obs == null || obs.isBlank()) continue;
            String trimmed = obs.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").trim();
            if (trimmed.length() > 180) {
                trimmed = trimmed.substring(0, 180);
            }
            String filtered = outputFilter.filterClarifyQuestion(trimmed);
            if (!TextInterpretationHandler.DEFAULT_CLARIFY_QUESTION.equals(filtered)) {
                clean.add(filtered);
            }
            if (clean.size() >= 4) break;
        }
        return clean;
    }
}
