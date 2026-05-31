package com.agentic.gateway.orchestrator.events;

import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.TaskState;

/**
 * 任務狀態已成功寫入資料庫後發布的事件。
 *
 * <p>事件監聽器只能執行通知、看板同步等 best-effort 副作用，不可反向影響核心狀態機。</p>
 */
public record TaskStateChangedEvent(
        String taskId,
        DevTask task,
        TaskState state,
        String commitSha,
        String resultSummary
) {
}
