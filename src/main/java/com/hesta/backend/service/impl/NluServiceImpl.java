package com.hesta.backend.service.impl;

import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.enums.DeviceAction;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.NluService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
        return "UNKNOWN_INTENT";
    }

    @Override
    public Map<String, String> extractEntities(String text) {
        Map<String, String> entities = new HashMap<>();
        String lowerText = text.toLowerCase();
        if (lowerText.contains("đèn")) entities.put("deviceType", "LIGHT");
        if (lowerText.contains("quạt")) entities.put("deviceType", "FAN");
        if (lowerText.contains("phòng khách")) entities.put("room", "LIVING_ROOM");
        return entities;
    }

    @Override
    public CompletableFuture<CommandResult> processNaturalLanguageCommand(String text, UUID userId) {
        String intent = extractIntent(text);
        Map<String, String> entities = extractEntities(text);
        
        log.info("NLU Processed - Intent: {}, Entities: {}", intent, entities);
        
        if ("UNKNOWN_INTENT".equals(intent)) {
            return CompletableFuture.completedFuture(
                CommandResult.builder().success(false).message("Could not understand intent").build()
            );
        }
        
        // Real DB lookup (Basic logic)
        java.util.List<com.hesta.backend.entity.Device> allDevices = deviceRepository.findAll();
        UUID targetDeviceId = null;
        for (com.hesta.backend.entity.Device d : allDevices) {
            String name = d.getName() != null ? d.getName().toLowerCase() : "";
            String rName = d.getRoom() != null ? d.getRoom().getName().toLowerCase() : "";
            
            boolean matchType = !entities.containsKey("deviceType") || name.contains(entities.get("deviceType").toLowerCase()) || name.contains("đèn") || name.contains("quạt");
            boolean matchRoom = !entities.containsKey("room") || rName.contains(entities.get("room").toLowerCase()) || rName.contains("khách");
            
            if (matchType && matchRoom) {
                targetDeviceId = d.getId();
                break;
            }
        }
        
        if (targetDeviceId == null) {
            return CompletableFuture.completedFuture(
                CommandResult.builder().success(false).message("Không tìm thấy thiết bị phù hợp").build()
            );
        }
        
        DeviceAction action = "TURN_ON".equals(intent) ? DeviceAction.TURN_ON : DeviceAction.TURN_OFF;
        return deviceCommandService.sendCommand(targetDeviceId, action, new HashMap<>(), StateChangeSource.VOICE);
    }
}
