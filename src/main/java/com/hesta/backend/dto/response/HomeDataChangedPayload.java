package com.hesta.backend.dto.response;

/**
 * Body of SCENE_CHANGED / AUTOMATION_RULE_CHANGED. It only names what changed; clients refetch through the
 * normal authorised API, so a member with limited access never receives data over the shared home topic.
 *
 * @param entity the changed entity type, e.g. {@code Scene}, {@code AutomationExecution}
 */
public record HomeDataChangedPayload(String entity) {
}
