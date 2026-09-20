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

    @Override
    public String extractIntent(String text) {
        String lowerText = text.toLowerCase();
        if (lowerText.contains("bật") || lowerText.contains("mở") || lowerText.contains("turn on")) return "TURN_ON";
        if (lowerText.contains("tắt") || lowerText.contains("đóng") || lowerText.contains("turn off")) return "TURN_OFF";
        if (lowerText.contains("tăng") || lowerText.contains("giảm") || lowerText.contains("chỉnh")) return "ADJUST";
        return "UNKNOWN_INTENT";
    }

        @Override
    public CompletableFuture<CommandResult> processNaturalLanguageCommand(String text, UUID userId) {
        String intent = extractIntent(text);
        String lowerText = text.toLowerCase();
        
        log.info("NLU Processed - Intent: {}, Raw Text: {}", intent, lowerText);
        
        if ("UNKNOWN_INTENT".equals(intent)) {
            return CompletableFuture.completedFuture(
                CommandResult.builder().success(false).message("Xin lỗi, tôi không hiểu ý bạn. Bạn muốn bật hay tắt thiết bị nào?").build()
            );
        }
        
        List<Device> allDevices = deviceRepository.findAll();
        List<Device> matchingDevices = new ArrayList<>();
        
        // DYNAMIC MATCHING (No hardcoding)
        for (Device d : allDevices) {
            String devName = d.getName() != null ? d.getName().toLowerCase() : "";
            String roomName = d.getRoom() != null && d.getRoom().getName() != null ? d.getRoom().getName().toLowerCase() : "";
            
            // Tách tên thành các từ khóa để so khớp (ví dụ "Đèn trần" -> check "đèn" và "trần")
            // Hoặc check nguyên chuỗi
            boolean matchDeviceName = !devName.isEmpty() && lowerText.contains(devName);
            boolean matchRoomName = !roomName.isEmpty() && lowerText.contains(roomName);
            
            // Mở rộng: Nếu text chứa 1 phần tên thiết bị (vd: "đèn" trùng với "Đèn trần")
            if (!matchDeviceName && !devName.isEmpty()) {
                String[] words = devName.split(" ");
                for (String word : words) {
                    if (word.length() > 2 && lowerText.contains(word)) {
                        matchDeviceName = true;
                        break;
                    }
                }
            }
            
            if (matchDeviceName) {
                // Nếu user nói tên phòng, phải khớp phòng
                boolean requiresRoom = false;
                for (Device checkRoom : allDevices) {
                    if (checkRoom.getRoom() != null && lowerText.contains(checkRoom.getRoom().getName().toLowerCase())) {
                        requiresRoom = true;
                        break;
                    }
                }
                
                if (!requiresRoom || matchRoomName) {
                    matchingDevices.add(d);
                }
            }
        }
        
        if (matchingDevices.isEmpty()) {
            return CompletableFuture.completedFuture(
                CommandResult.builder().success(false).message("Không tìm thấy thiết bị nào phù hợp với yêu cầu của bạn.").build()
            );
        }
        
        // Ambiguity Detection
        if (matchingDevices.size() > 1) {
            String options = matchingDevices.get(0).getName() + " và " + matchingDevices.get(1).getName();
            return CompletableFuture.completedFuture(
                CommandResult.builder()
                    .success(false)
                    .message("Hệ thống tìm thấy " + matchingDevices.size() + " thiết bị khớp (ví dụ: " + options + "). Bạn muốn điều khiển cái nào?")
                    .build()
            );
        }
        
        Device target = matchingDevices.get(0);
        DeviceAction action = "TURN_OFF".equals(intent) ? DeviceAction.TURN_OFF : DeviceAction.TURN_ON;
        
        // Extract parameters (Con số)
        Map<String, Object> params = new HashMap<>();
        Pattern pattern = Pattern.compile("(\\\\d+)\\\\s*(%|độ|số)");
        Matcher matcher = pattern.matcher(lowerText);
        if (matcher.find()) {
            String value = matcher.group(1);
            String unit = matcher.group(2);
            if ("%".equals(unit)) params.put("brightness", Integer.parseInt(value));
            else if ("độ".equals(unit)) params.put("temperature", Integer.parseInt(value));
            else if ("số".equals(unit)) params.put("speed", Integer.parseInt(value));
        }
        
        return deviceCommandService.sendCommand(target.getId(), action, params, StateChangeSource.VOICE);
    }
}