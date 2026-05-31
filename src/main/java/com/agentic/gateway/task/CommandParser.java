package com.agentic.gateway.task;

import com.agentic.gateway.dto.TargetEngine;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 將使用者輸入轉成 Gateway 內部標準指令。
 *
 * <p>目前支援純文字，以及 /claude、/local 這類帶前綴指令。
 * 未知前綴不會被拒絕，而是交給 DEFAULT 引擎處理，避免 Gateway 綁死下游能力。</p>
 */
@Component
public class CommandParser {

    public ParsedCommand parse(String rawText) {
        String normalizedText = rawText == null ? "" : rawText.trim();
        if (normalizedText.isEmpty()) {
            return new ParsedCommand(TargetEngine.DEFAULT, "");
        }

        if (!normalizedText.startsWith("/")) {
            return new ParsedCommand(TargetEngine.DEFAULT, normalizedText);
        }

        String[] parts = normalizedText.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String payload = parts.length > 1 ? parts[1].trim() : "";

        TargetEngine targetEngine = switch (command) {
            case "/claude" -> TargetEngine.CLAUDE;
            case "/local" -> TargetEngine.LOCAL;
            default -> TargetEngine.DEFAULT;
        };

        return new ParsedCommand(targetEngine, payload);
    }
}
