package com.hesta.backend.enums;

/**
 * Vòng đời xử lý một lệnh giọng nói (Bảng: voice_commands.status).
 * - LISTENING, RECOGNIZED, CLARIFYING, EXECUTED, FAILED, REJECTED.
 */
public enum VoiceCommandStatus {
    LISTENING, RECOGNIZED, CLARIFYING, EXECUTED, FAILED, REJECTED
}
