package com.hesta.backend.maintenance.assistant;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Lớp an toàn 1 (chạy TRƯỚC và độc lập hoàn toàn với LLM).
 * Hỗ trợ phát hiện dấu hiệu nguy hiểm điện/nhiệt/cháy/nước/gas bằng tiếng Việt có dấu và không dấu.
 */
@Component
public class SafetySignalDetector {

    private record SignalRule(String label, Pattern pattern) {
    }

    private static final List<SignalRule> RULES = List.of(
            rule("mùi khét", "\\b(mui\\s+khet|khet\\s+let|co\\s+khet|khet)\\b"),
            rule("cháy / bốc khói", "\\b(chay\\s+no|boc\\s+khoi|boc\\s+chay|chay\\s+xem|chay\\s+den|bi\\s+chay|toa\\s+khoi|co\\s+khoi|khoi\\s+den|khoi\\s+trang)\\b"),
            rule("tia lửa", "\\b(tia\\s+lua|xet\\s+lua|ban\\s+tia\\s+lua|toe\\s+lua|phat\\s+lua)\\b"),
            rule("tiếng nổ / chập điện", "\\b(chap\\s+dien|chap\\s+mach|chap\\s+chay|no\\s+bup|no\\s+to|phat\\s+no|bi\\s+no)\\b"),
            rule("rò điện / giật điện", "\\b(ro\\s+dien|ro\\s+ri\\s+dien|nhiem\\s+dien|giat\\s+dien|bi\\s+giat|te\\s+giat|cham\\s+vao\\s+bi\\s+giat)\\b"),
            rule("nóng bất thường / nóng bỏng", "\\b(nong\\s+bong|nong\\s+ran|nong\\s+bat\\s+thuong|nong\\s+ruc|qua\\s+nong|chay\\s+tay)\\b"),
            rule("phồng / biến dạng", "\\b(phong\\s+pin|bi\\s+phong|phong\\s+len|chay\\s+nhua|chay\\s+vo)\\b"),
            rule("nước vào thiết bị", "\\b(nuoc\\s+vao|bi\\s+vao\\s+nuoc|vao\\s+nuoc|ngap\\s+nuoc|dinh\\s+nuoc|uoc\\s+nuoc|do\\s+nuoc\\s+vao)\\b"),
            rule("rò khí gas", "\\b(ro\\s+gas|ro\\s+ri\\s+gas|mui\\s+gas|xi\\s+gas)\\b")
    );

    private static SignalRule rule(String label, String regex) {
        return new SignalRule(label, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    public List<String> detect(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }
        String normalized = normalizeVietnamese(rawText);
        List<String> matched = new ArrayList<>();
        for (SignalRule rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                matched.add(rule.label());
            }
        }
        return matched;
    }

    public static String normalizeVietnamese(String input) {
        if (input == null) {
            return "";
        }
        String replaced = input.replace('đ', 'd').replace('Đ', 'D');
        String decomposed = Normalizer.normalize(replaced, Normalizer.Form.NFD);
        String stripped = decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
