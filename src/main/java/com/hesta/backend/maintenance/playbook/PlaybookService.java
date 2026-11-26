package com.hesta.backend.maintenance.playbook;

import com.hesta.backend.enums.MaintenancePriority;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Nạp và kiểm định các playbook chẩn đoán bảo trì từ classpath:playbooks/*.yaml khi khởi động (fail-fast).
 */
@Service
@Slf4j
public class PlaybookService {

    public static final int MAX_STEPS_PER_SYMPTOM = 4;
    private static final String GENERIC_ID = "GENERIC";

    private final Map<String, PlaybookDefinition> byId = new LinkedHashMap<>();
    private final Map<String, PlaybookDefinition> byDeviceType = new HashMap<>();

    public PlaybookService() {
        loadFromPattern("classpath*:playbooks/*.yaml");
    }

    PlaybookService(String resourcePattern) {
        loadFromPattern(resourcePattern);
    }

    private void loadFromPattern(String pattern) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(pattern);
        } catch (IOException e) {
            throw new IllegalStateException("Không thể đọc thư mục playbooks: " + pattern, e);
        }
        if (resources.length == 0) {
            throw new IllegalStateException("Không tìm thấy file playbook nào theo mẫu: " + pattern);
        }

        LoaderOptions options = new LoaderOptions();
        for (Resource resource : resources) {
            PlaybookDefinition def = parseResource(resource, options);
            validate(def, resource.getFilename());
            String normalizedId = def.getId().trim().toUpperCase(Locale.ROOT);
            if (byId.put(normalizedId, def) != null) {
                throw new IllegalStateException("Trùng playbook id: " + normalizedId);
            }
            for (String type : def.getDeviceTypes()) {
                String key = type.trim().toUpperCase(Locale.ROOT);
                if (byDeviceType.put(key, def) != null) {
                    throw new IllegalStateException("Trùng deviceType " + key + " giữa các playbook");
                }
            }
        }
        if (!byId.containsKey(GENERIC_ID)) {
            throw new IllegalStateException("Thiếu playbook dự phòng bắt buộc: GENERIC");
        }
        log.info("Loaded {} maintenance diagnostic playbooks covering {} device types", byId.size(), byDeviceType.size());
    }

    private PlaybookDefinition parseResource(Resource resource, LoaderOptions options) {
        Yaml yaml = new Yaml(new Constructor(PlaybookDefinition.class, options));
        try (InputStream is = resource.getInputStream()) {
            PlaybookDefinition def = yaml.load(is);
            if (def == null) {
                throw new IllegalStateException("Playbook rỗng: " + resource.getFilename());
            }
            return def;
        } catch (IOException e) {
            throw new IllegalStateException("Lỗi đọc file playbook: " + resource.getFilename(), e);
        }
    }

    private void validate(PlaybookDefinition def, String filename) {
        requireText(def.getId(), filename + ": thiếu id");
        requireText(def.getLabel(), filename + ": thiếu label");
        if (def.getMinEscalationPriority() != null && !def.getMinEscalationPriority().isBlank()) {
            try {
                MaintenancePriority.valueOf(def.getMinEscalationPriority().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(filename + ": minEscalationPriority không hợp lệ: " + def.getMinEscalationPriority());
            }
        }
        if (def.getDeviceTypes() == null || def.getDeviceTypes().isEmpty()) {
            throw new IllegalStateException(filename + ": thiếu danh sách deviceTypes");
        }
        if (def.getImageHints() != null) {
            if (def.getImageHints().size() > 5) {
                throw new IllegalStateException(filename + ": imageHints tối đa 5 mục");
            }
            for (String hint : def.getImageHints()) {
                requireText(hint, filename + ": phần tử imageHints không được rỗng");
            }
        }
        if (def.getSafety() == null) {
            throw new IllegalStateException(filename + ": thiếu cấu hình safety");
        }
        requireText(def.getSafety().getTriggerId(), filename + ": thiếu safety.triggerId");
        requireText(def.getSafety().getLabel(), filename + ": thiếu safety.label");
        requireText(def.getSafety().getAdvice(), filename + ": thiếu safety.advice");
        if (def.getSafety().getKeywords() == null || def.getSafety().getKeywords().isEmpty()) {
            throw new IllegalStateException(filename + ": thiếu safety.keywords");
        }
        if (def.getSymptoms() == null || def.getSymptoms().isEmpty()) {
            throw new IllegalStateException(filename + ": thiếu danh sách symptoms");
        }
        Set<String> symptomIds = new HashSet<>();
        for (PlaybookDefinition.Symptom symptom : def.getSymptoms()) {
            requireText(symptom.getId(), filename + ": triệu chứng thiếu id");
            requireText(symptom.getLabel(), filename + ": triệu chứng thiếu label");
            requireText(symptom.getTicketDescription(), filename + ": triệu chứng thiếu ticketDescription");
            if (!symptomIds.add(symptom.getId().trim())) {
                throw new IllegalStateException(filename + ": trùng symptom id " + symptom.getId());
            }
            List<PlaybookDefinition.Step> steps = symptom.getSteps() != null ? symptom.getSteps() : List.of();
            if (steps.size() > MAX_STEPS_PER_SYMPTOM) {
                throw new IllegalStateException(filename + ": triệu chứng " + symptom.getId()
                        + " vượt quá tối đa " + MAX_STEPS_PER_SYMPTOM + " bước");
            }
            for (PlaybookDefinition.Step step : steps) {
                requireText(step.getId(), filename + ": bước thiếu id");
                requireText(step.getTitle(), filename + ": bước thiếu title");
                requireText(step.getInstruction(), filename + ": bước thiếu instruction");
            }
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(message);
        }
    }

    public PlaybookDefinition getForDeviceType(String deviceType) {
        if (deviceType != null && !deviceType.isBlank()) {
            PlaybookDefinition match = byDeviceType.get(deviceType.trim().toUpperCase(Locale.ROOT));
            if (match != null) {
                return match;
            }
        }
        return byId.get(GENERIC_ID);
    }

    public Collection<PlaybookDefinition> getAll() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public boolean matchesSafetyKeyword(PlaybookDefinition playbook, String text) {
        if (playbook == null || playbook.getSafety() == null || text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return playbook.getSafety().getKeywords().stream()
                .filter(Objects::nonNull)
                .map(k -> k.toLowerCase(Locale.ROOT))
                .anyMatch(lower::contains);
    }
}
