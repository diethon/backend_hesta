package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.entity.Device;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.NluService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class NluServiceImpl implements NluService {

    private final DeviceCommandService deviceCommandService;
    private final com.hesta.backend.repository.DeviceRepository deviceRepository;

    /**
     * Chuẩn hóa chuỗi tiếng Việt (xóa dấu, đưa về chữ thường) để so sánh dễ hơn
     */
    private String normalizeText(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text.toLowerCase().trim(), Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    /**
     * Thuật toán Levenshtein Distance tính khoảng cách chỉnh sửa giữa 2 chuỗi
     */
    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            for (int j = 0; j <= b.length(); j++) {
                if (i == 0) dp[i][j] = j;
                else if (j == 0) dp[i][j] = i;
                else {
                    dp[i][j] = Math.min(dp[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1),
                            Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));
                }
            }
        }
        return dp[a.length()][b.length()];
    }

    /**
     * Fuzzy match: Kiểm tra xem target (tên thiết bị) có nằm trong source (câu lệnh) hay không
     * Hỗ trợ sai chính tả 1-2 ký tự (VD: "đnè" -> "đèn")
     */
    private boolean fuzzyMatch(String source, String target) {
        if (source == null || target == null || target.isEmpty()) return false;
        
        String normSource = normalizeText(source);
        String normTarget = normalizeText(target);
        
        if (normSource.contains(normTarget)) return true;

        String[] sourceTokens = normSource.split("\\s+");
        String[] targetTokens = normTarget.split("\\s+");

        int matchCount = 0;
        for (String tToken : targetTokens) {
            boolean found = false;
            for (String sToken : sourceTokens) {
                if (sToken.equals(tToken)) {
                    found = true;
                    break;
                }
                // Cho phép sai số 1 ký tự đối với từ >= 3 ký tự (chống sai chính tả)
                if (sToken.length() >= 3 && tToken.length() >= 3 && levenshteinDistance(sToken, tToken) <= 1) {
                    found = true;
                    break;
                }
            }
            if (found) matchCount++;
        }
        
        // Cần khớp tỷ lệ cao (ví dụ >= 75% số từ của thiết bị)
        return (double) matchCount / targetTokens.length >= 0.75;
    }

    @Override
    public String extractIntent(String text) {
        String normText = normalizeText(text);
        if (normText.matches(".*\\b(bat|mo|turn on|sang)\\b.*")) return "TURN_ON";
        if (normText.matches(".*\\b(tat|dong|turn off|toi)\\b.*")) return "TURN_OFF";
        if (normText.matches(".*\\b(tang|giam|chinh|set)\\b.*")) return "ADJUST";
        return "UNKNOWN_INTENT";
    }

    @Override
    public CompletableFuture<CommandResult> processNaturalLanguageCommand(String text, UUID userId) {
        String intent = extractIntent(text);
        
        log.info("NLU Processed - Intent: {}, Raw Text: {}", intent, text);
        
        if ("UNKNOWN_INTENT".equals(intent)) {
            return CompletableFuture.completedFuture(
                CommandResult.builder().success(false).message("Xin lỗi, tôi không nhận diện được lệnh. Bạn muốn bật hay tắt thiết bị nào?").build()
            );
        }
        
        List<Device> allDevices = deviceRepository.findAll();
        List<Device> matchingDevices = new ArrayList<>();
        
        // Edge AI Heuristic Matching
        for (Device d : allDevices) {
            String devName = d.getName() != null ? d.getName() : "";
            String roomName = d.getRoom() != null && d.getRoom().getName() != null ? d.getRoom().getName() : "";
            
            boolean matchDeviceName = fuzzyMatch(text, devName);
            boolean matchRoomName = fuzzyMatch(text, roomName);
            
            if (matchDeviceName) {
                // Nếu người dùng có nhắc đến tên một phòng bất kỳ trong nhà, phải đảm bảo thiết bị thuộc phòng đó
                boolean userMentionedSomeRoom = false;
                for (Device checkRoom : allDevices) {
                    if (checkRoom.getRoom() != null && fuzzyMatch(text, checkRoom.getRoom().getName())) {
                        userMentionedSomeRoom = true;
                        break;
                    }
                }
                
                if (!userMentionedSomeRoom || matchRoomName) {
                    matchingDevices.add(d);
                }
            }
        }
        
        if (matchingDevices.isEmpty()) {
            return CompletableFuture.completedFuture(
                CommandResult.builder().success(false).message("Không tìm thấy thiết bị nào khớp với lệnh của bạn. Vui lòng thử lại.").build()
            );
        }
        
        // Ambiguity Detection
        if (matchingDevices.size() > 1) {
            String options = matchingDevices.get(0).getName() + " và " + matchingDevices.get(1).getName();
            return CompletableFuture.completedFuture(
                CommandResult.builder()
                    .success(false)
                    .message("Hệ thống tìm thấy " + matchingDevices.size() + " thiết bị trùng khớp (ví dụ: " + options + "). Bạn muốn điều khiển cái nào?")
                    .build()
            );
        }
        
        Device target = matchingDevices.get(0);
        DeviceAction action = "TURN_OFF".equals(intent) ? DeviceAction.TURN_OFF : DeviceAction.TURN_ON;
        
        // Tách tham số nâng cao bằng Regex
        Map<String, Object> params = new HashMap<>();
        String normText = normalizeText(text);
        Pattern pattern = Pattern.compile("(\\d+)\\s*(%|do|so)");
        Matcher matcher = pattern.matcher(normText);
        if (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                String unit = matcher.group(2);
                if ("%".equals(unit)) params.put("brightness", value);
                else if ("do".equals(unit)) params.put("temperature", value);
                else if ("so".equals(unit)) params.put("speed", value);
            } catch (NumberFormatException ignored) {}
        }
        
        return deviceCommandService.sendCommand(target.getId(), action, params, StateChangeSource.VOICE);
    }
}