package com.hesta.backend.maintenance.playbook;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PlaybookDefinition {

    private String id;
    private String label;
    private String openingWarning;
    private String minEscalationPriority;
    private List<String> deviceTypes = new ArrayList<>();
    private List<String> imageHints = new ArrayList<>();
    private EscalateWhen escalateWhen;
    private SafetyRule safety;
    private List<Symptom> symptoms = new ArrayList<>();

    @Data
    public static class EscalateWhen {
        private int offlineHoursAfterSteps = 1;
        private String note;
    }

    @Data
    public static class SafetyRule {
        private String triggerId;
        private String label;
        private String advice;
        private List<String> keywords = new ArrayList<>();
    }

    @Data
    public static class Symptom {
        private String id;
        private String label;
        private String ticketDescription;
        private boolean hardwareOnly = false;
        private List<Step> steps = new ArrayList<>();
    }

    @Data
    public static class Step {
        private String id;
        private String title;
        private String instruction;
    }
}
