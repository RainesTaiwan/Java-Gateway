package com.agentic.gateway.orchestrator.agent;

import java.util.Locale;

/**
 * 支援的開發引擎類型。
 */
public enum AgentEngine {
    CURSOR,
    AIDER;

    public static AgentEngine fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return CURSOR;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "cursor" -> CURSOR;
            case "aider" -> AIDER;
            default -> throw new IllegalStateException("不支援的 AGENT_ENGINE: " + value);
        };
    }
}
