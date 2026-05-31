package com.agentic.gateway.orchestrator.events;

import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.TaskState;
import com.agentic.gateway.orchestrator.github.GitHubProjectSyncService;
import com.agentic.gateway.orchestrator.telegram.TelegramNotifierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 任務狀態變更後的 best-effort 通知處理器。
 *
 * <p>GitHub Projects 與 Telegram 都是外部副作用；任何錯誤只記錄 log，
 * 不可阻斷或回滾已寫入資料庫的核心狀態。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskNotificationListener {

    private static final String TELEGRAM_FAILED_MESSAGE = "❌ 任務執行失敗。已放棄重試。請查看 GitHub 看板與地端 Log。";

    private final GitHubProjectSyncService gitHubProjectSyncService;
    private final TelegramNotifierService telegramNotifierService;

    @Async("gatewayTaskExecutor")
    @EventListener
    public void onTaskStateChanged(TaskStateChangedEvent event) {
        syncGitHubProject(event);
        notifyTelegramTerminalState(event);
    }

    private void syncGitHubProject(TaskStateChangedEvent event) {
        Optional<String> projectItemId = Optional.ofNullable(event.task().projectItemId())
                .filter(id -> !id.isBlank());
        if (projectItemId.isEmpty()) {
            return;
        }

        try {
            gitHubProjectSyncService.updateCardStatus(projectItemId.get(), event.state());
        } catch (Exception ex) {
            log.warn("Best-effort GitHub Project status sync failed. taskId={}, state={}, projectItemId={}",
                    event.taskId(), event.state(), projectItemId.get(), ex);
        }
    }

    private void notifyTelegramTerminalState(TaskStateChangedEvent event) {
        TaskState state = event.state();
        if (state != TaskState.SUCCESS && state != TaskState.FAILED) {
            return;
        }

        DevTask task = event.task();
        String message = state == TaskState.SUCCESS ? buildSuccessMessage(event) : TELEGRAM_FAILED_MESSAGE;
        try {
            telegramNotifierService.sendMessage(task.telegramChatId(), message);
        } catch (Exception ex) {
            log.warn("Best-effort Telegram terminal notification failed. taskId={}, state={}, chatId={}",
                    event.taskId(), state, task.telegramChatId(), ex);
        }
    }

    private String buildSuccessMessage(TaskStateChangedEvent event) {
        String commitSha = event.commitSha() == null || event.commitSha().isBlank()
                ? "(unknown)"
                : event.commitSha();
        return """
                ✅ 任務交付成功！
                📝 Commit SHA: %s
                🔗 請至 GitHub 檢查最新代碼。
                """.formatted(commitSha);
    }
}
