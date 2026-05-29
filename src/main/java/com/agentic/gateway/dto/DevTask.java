package com.agentic.gateway.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 標準化開發任務。
 *
 * @param taskId       任務唯一識別碼
 * @param source       任務來源，例如 Telegram 或 GitHub
 * @param targetEngine 目標執行引擎
 * @param payload      使用者指令、Issue 標題或 Issue URL 等任務內容
 * @param createdAt    Gateway 建立任務的 UTC 時間
 */
public record DevTask(
        UUID taskId,
        TaskSource source,
        TargetEngine targetEngine,
        String payload,
        Instant createdAt
) {

    /**
     * 建立新任務，統一由 Gateway 產生 UUID 與時間戳。
     */
    public static DevTask create(TaskSource source, TargetEngine targetEngine, String payload) {
        return new DevTask(UUID.randomUUID(), source, targetEngine, payload, Instant.now());
    }
}
