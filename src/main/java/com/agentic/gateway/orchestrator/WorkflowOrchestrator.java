package com.agentic.gateway.orchestrator;

import com.agentic.gateway.config.OrchestratorProperties;
import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.agent.AgentExecutionResult;
import com.agentic.gateway.orchestrator.agent.AgentExecutionService;
import com.agentic.gateway.orchestrator.git.GitSyncService;
import com.agentic.gateway.orchestrator.github.GitHubProjectSyncService;
import com.agentic.gateway.orchestrator.ollama.OllamaNoiseReducer;
import com.agentic.gateway.orchestrator.ollama.TaskSplitterService;
import com.agentic.gateway.orchestrator.telegram.TelegramCompletionNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Java Orchestrator 核心編排器。
 *
 * <p>目前版本只建立最小狀態機骨架：接到 {@link DevTask} 後依序模擬
 * RECEIVED -> IN_PROGRESS -> RUNNING -> VERIFYING -> SUCCESS 的狀態流轉，
 * 並在每次狀態切換時嘗試同步 GitHub Projects v2 看板狀態。後續要接入 JGit、
 * Aider Docker 沙盒、TestRunner 與 Karpathy Loop 時，應把實際動作掛在這個服務內，
 * 而不是放回 Gateway ingress 層。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowOrchestrator {

    private static final int MAX_RETRIES = 3;
    private static final int LOG_SUMMARY_LIMIT = 4_000;
    private static final String RETRY_SPEC_PREFIX = "\n[前次嘗試失敗，請修正以下錯誤]: ";

    private final OrchestratorProperties orchestratorProperties;
    private final GitHubProjectSyncService gitHubProjectSyncService;
    private final GitSyncService gitSyncService;
    private final AgentExecutionService agentExecutionService;
    private final OllamaNoiseReducer ollamaNoiseReducer;
    private final TaskSplitterService taskSplitterService;
    private final TelegramCompletionNotifier telegramCompletionNotifier;

    /**
     * 處理單一開發任務。
     *
     * <p>此方法目前是同步方法，讓 JMS consumer 可以清楚判斷「已成功進入 Orchestrator
     * 控管」後再 acknowledge。未來若改為長時間非同步 workflow，建議先將任務持久化到
     * Orchestrator 自己的狀態儲存，再 ack JMS 訊息。</p>
     */
    public void processTask(DevTask task) {
        log.info("Orchestrator received DevTask. taskId={}, source={}, targetEngine={}",
                task.taskId(), task.source(), task.targetEngine());
        log.info("DevTask payload preview. taskId={}, payload={}", task.taskId(), truncateLog(task.payload()));

        Optional<String> projectItemId = Optional.ofNullable(task.projectItemId())
                .filter(id -> !id.isBlank());
        if (projectItemId.isEmpty()) {
            log.info("[INFO] Task source is Telegram, skipping GitHub project card sync.");
        }

        transition(task, projectItemId, TaskState.RECEIVED);
        transition(task, projectItemId, TaskState.PLANNING);
        String plannedSpec = taskSplitterService.splitTask(task.payload());
        log.info("Task split plan generated. taskId={}, plan=\n{}", task.taskId(), truncateLog(plannedSpec));

        transition(task, projectItemId, TaskState.IN_PROGRESS);

        // 第一次嘗試前重置為遠端乾淨基線；後續 retry 保留髒工作區讓開發引擎接續修正。
        gitSyncService.syncRepository();

        DevTask currentAttempt = taskWithPlannedSpec(task, plannedSpec);
        int retryCount = 0;

        while (true) {
            int attemptNumber = retryCount + 1;
            transition(task, projectItemId, TaskState.RUNNING);
            log.info("Starting agent attempt. taskId={}, engine={}, attempt={}, maxRetries={}",
                    task.taskId(), agentExecutionService.engineName(), attemptNumber, MAX_RETRIES);

            AgentExecutionResult sandboxResult = agentExecutionService.runAgent(currentAttempt);
            transition(task, projectItemId, TaskState.VERIFYING);

            if (sandboxResult.isSuccess()) {
                boolean hasDiff = gitSyncService.hasChanges(orchestratorProperties.workspace().containerPath());
                if (!hasDiff) {
                    String outputSummary = truncateLog(sandboxResult.logs());
                    log.warn("[交付失敗] taskId={}, 沙盒回傳成功但工作區無任何代碼變更 (git diff 為空)！AI 輸出摘要:\n{}",
                            task.taskId(), outputSummary);
                    transition(task, projectItemId, TaskState.FAILED);
                    sendTelegramReport(
                            task.telegramChatId(),
                            "任務執行結束，但 Agent 未偵測到任何實質程式碼修改，交付中止。\n\nAgent 輸出摘要:\n"
                                    + outputSummary
                    );
                    return;
                }

                log.info("[交付核實] taskId={} 成功。AI 輸出摘要:\n{}",
                        task.taskId(), truncateLog(sandboxResult.logs()));

                try {
                    gitSyncService.commitAndPush(task.taskId().toString());
                } catch (Exception ex) {
                    log.error("[交付失敗] taskId={} 代碼已有變更，但 commit/push 失敗。", task.taskId(), ex);
                    transition(task, projectItemId, TaskState.FAILED);
                    sendTelegramReport(
                            task.telegramChatId(),
                            "任務已產生程式碼修改，但自動 commit/push 失敗，交付中止。"
                    );
                    return;
                }

                transition(task, projectItemId, TaskState.SUCCESS);
                log.info("Orchestrator finished DevTask successfully. taskId={}, attempt={}, exitCode={}",
                        task.taskId(), attemptNumber, sandboxResult.exitCode());
                sendTelegramReport(task.telegramChatId(), "任務交付成功！代碼已自動推送到遠端倉庫。");
                return;
            }

            if (retryCount >= MAX_RETRIES) {
                transition(task, projectItemId, TaskState.FAILED);
                log.error("DevTask failed after maximum retries. taskId={}, attempts={}, lastExitCode={}, timedOut={}",
                        task.taskId(), attemptNumber, sandboxResult.exitCode(), sandboxResult.timedOut());
                sendTelegramReport(
                        task.telegramChatId(),
                        "任務執行失敗，Agent 已達最大重試次數。請查看 Orchestrator log 取得詳細原因。"
                );
                return;
            }

            retryCount++;
            transition(task, projectItemId, TaskState.RETRYING);

            String noiseReducedLog = ollamaNoiseReducer.reduceNoise(sandboxResult.logs());
            currentAttempt = taskWithRetryContext(currentAttempt, noiseReducedLog);
            log.warn("Agent attempt failed; retry scheduled. taskId={}, engine={}, nextAttempt={}, exitCode={}, timedOut={}, summary={}",
                    task.taskId(), sandboxResult.engine(), retryCount + 1, sandboxResult.exitCode(), sandboxResult.timedOut(), noiseReducedLog);
        }
    }

    private void transition(DevTask task, Optional<String> projectItemId, TaskState nextState) {
        log.info("Task state changed. taskId={}, state={}", task.taskId(), nextState);
        projectItemId.ifPresent(itemId -> gitHubProjectSyncService.updateCardStatus(itemId, nextState));
    }

    private void sendTelegramReport(String telegramChatId, String message) {
        telegramCompletionNotifier.sendReport(telegramChatId, message);
    }

    private String truncateLog(String logs) {
        if (logs == null || logs.isBlank()) {
            return "(empty)";
        }
        String normalized = logs.trim();
        if (normalized.length() <= LOG_SUMMARY_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, LOG_SUMMARY_LIMIT) + "\n... (truncated)";
    }

    private DevTask taskWithPlannedSpec(DevTask task, String plannedSpec) {
        String originalPayload = task.payload() == null ? "" : task.payload();
        String effectivePlan = plannedSpec == null || plannedSpec.isBlank() ? originalPayload : plannedSpec;
        String optimizedPayload = """
                你必須直接修改 repository 內的檔案，不要只輸出規劃，也不要要求使用者再次確認。

                原始需求：
                %s

                地端架構師拆解後執行規格：
                %s
                """.formatted(originalPayload, effectivePlan);

        return new DevTask(
                task.taskId(),
                task.source(),
                task.targetEngine(),
                optimizedPayload,
                task.projectItemId(),
                task.telegramChatId(),
                task.createdAt()
        );
    }

    private DevTask taskWithRetryContext(DevTask task, String noiseReducedLog) {
        String originalPayload = task.payload() == null ? "" : task.payload();
        String retryPayload = originalPayload + RETRY_SPEC_PREFIX + noiseReducedLog;
        return new DevTask(
                task.taskId(),
                task.source(),
                task.targetEngine(),
                retryPayload,
                task.projectItemId(),
                task.telegramChatId(),
                task.createdAt()
        );
    }

}
