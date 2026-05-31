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
 * @param projectItemId GitHub Projects v2 item node ID；Telegram 任務允許為 null
 * @param telegramChatId Telegram chat ID；非 Telegram 來源任務允許為 null
 * @param deliveryId GitHub X-GitHub-Delivery ID；非 GitHub 來源任務允許為 null
 * @param createdAt    Gateway 建立任務的 UTC 時間
 */
public record DevTask(
        UUID taskId,
        TaskSource source,
        TargetEngine targetEngine,
        String payload,
        String projectItemId,
        String telegramChatId,
        String deliveryId,
        Instant createdAt
) {

    /**
     * 建立新任務，統一由 Gateway 產生 UUID 與時間戳。
     */
    public static DevTask create(TaskSource source, TargetEngine targetEngine, String payload) {
        return create(source, targetEngine, payload, null, null, null);
    }

    /**
     * 建立帶有 GitHub Projects v2 item ID 的任務。
     *
     * <p>此 overload 主要供 GitHub Webhook ingress 使用；Telegram 指令一開始通常沒有
     * 對應看板卡片，因此可繼續使用不帶 {@code projectItemId} 的 {@link #create(TaskSource, TargetEngine, String)}。</p>
     */
    public static DevTask create(TaskSource source, TargetEngine targetEngine, String payload, String projectItemId) {
        return create(source, targetEngine, payload, projectItemId, null, null);
    }

    /**
     * 建立帶有 GitHub delivery id 的任務，用於 webhook 冪等去重。
     */
    public static DevTask createGitHubTask(
            TaskSource source,
            TargetEngine targetEngine,
            String payload,
            String projectItemId,
            String deliveryId
    ) {
        return create(source, targetEngine, payload, projectItemId, null, deliveryId);
    }

    /**
     * 建立帶有 Telegram chat ID 的任務，讓 Orchestrator 完成後可以回報終點狀態。
     */
    public static DevTask create(
            TaskSource source,
            TargetEngine targetEngine,
            String payload,
            String projectItemId,
            String telegramChatId
    ) {
        return create(source, targetEngine, payload, projectItemId, telegramChatId, null);
    }

    public static DevTask create(
            TaskSource source,
            TargetEngine targetEngine,
            String payload,
            String projectItemId,
            String telegramChatId,
            String deliveryId
    ) {
        return new DevTask(
                UUID.randomUUID(),
                source,
                targetEngine,
                payload,
                projectItemId,
                telegramChatId,
                deliveryId,
                Instant.now()
        );
    }
}
