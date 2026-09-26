package com.hesta.backend.service;

import com.hesta.backend.dto.command.CommandResult;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface NluService {
    String extractIntent(String text);
    CompletableFuture<CommandResult> processNaturalLanguageCommand(String text, java.util.UUID userId);
}
