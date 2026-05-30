package com.agentic.gateway.orchestrator;

import com.agentic.gateway.config.OrchestratorProperties;
import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.agent.AgentExecutionRegistry;
import com.agentic.gateway.orchestrator.agent.AgentExecutionResult;
import com.agentic.gateway.orchestrator.agent.AgentExecutionService;
import com.agentic.gateway.orchestrator.git.GitSyncService;
import com.agentic.gateway.orchestrator.github.GitHubProjectSyncService;
import com.agentic.gateway.orchestrator.ollama.OllamaNoiseReducer;
import com.agentic.gateway.orchestrator.ollama.TaskSplitterService;
import com.agentic.gateway.orchestrator.persistence.DevTaskRecordService;
import com.agentic.gateway.orchestrator.telegram.TelegramNotifierService;
import com.agentic.gateway.orchestrator.test.TestExecutionResult;
import com.agentic.gateway.orchestrator.test.TestRunnerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Java Orchestrator 核心編排器。
 *
 * <p>狀態流轉：RECEIVED → PLANNING → IN_PROGRESS → RUNNING → VERIFYING → SUCCESS，
 * 失敗時進入 RETRYING 並走 Karpathy Loop。VERIFYING 階段由 {@link TestRunnerService}
 * 在獨立 Maven 容器內執行測試，通過後才允許 commit/push。每次狀態切換皆持久化至資料庫。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowOrchestrator {

    private static final int MAX_RETRIES = 3;
    private static final int LOG_SUMMARY_LIMIT = 4_000;
    private static final String RETRY_SPEC_PREFIX = "\n[前次嘗試失敗，請修正以下錯誤]: ";
    private static final String TELEGRAM_SUCCESS_MESSAGE = "✅ 任務執行成功！代碼已推送到遠端倉庫。測試全數通過。";
    private static final String TELEGRAM_FAILED_MESSAGE = "❌ 任務執行失敗。已放棄重試。請查看 GitHub 看板與地端 Log。";

    private final OrchestratorProperties orchestratorProperties;
    private final GitHubProjectSyncService gitHubProjectSyncService;
    private final GitSyncService gitSyncService;
    private final AgentExecutionRegistry agentExecutionRegistry;
    private final OllamaNoiseReducer ollamaNoiseReducer;
    private final TaskSplitterService taskSplitterService;
    private final TelegramNotifierService telegramNotifierService;
    private final TestRunnerService testRunnerService;
    private final DevTaskRecordService devTaskRecordService;

    /**
     * 非同步派發開發任務，與 JMS listener 執行緒分離。
     */
    @Async("orchestratorTaskExecutor")
    public void processTaskAsync(String taskId) {
        processTask(taskId);
    }

    /**
     * 依 taskId 從資料庫載入任務並處理；所有狀態切換同步寫入資料庫。
     */
    public void processTask(String taskId) {
        DevTask task = devTaskRecordService.loadDevTask(taskId);

        log.info("Orchestrator received DevTask. taskId={}, source={}, targetEngine={}",
                task.taskId(), task.source(), task.targetEngine());
        log.info("DevTask payload preview. taskId={}, payload={}", task.taskId(), truncateLog(task.payload()));

        Optional<String> projectItemId = Optional.ofNullable(task.projectItemId())
                .filter(id -> !id.isBlank());
        if (projectItemId.isEmpty()) {
            log.info("[INFO] Task source is Telegram, skipping GitHub project card sync.");
        }

        transition(taskId, task, projectItemId, TaskState.RECEIVED);
        transition(taskId, task, projectItemId, TaskState.PLANNING);
        String plannedSpec = taskSplitterService.splitTask(task.payload());
        log.info("Task split plan generated. taskId={}, plan=\n{}", task.taskId(), truncateLog(plannedSpec));

        transition(taskId, task, projectItemId, TaskState.IN_PROGRESS);

        // 第一次嘗試前重置為遠端乾淨基線；後續 retry 保留髒工作區讓開發引擎接續修正。
        gitSyncService.syncRepository();

        DevTask currentAttempt = taskWithPlannedSpec(task, plannedSpec);
        int retryCount = 0;

        AgentExecutionService agentExecutionService = agentExecutionRegistry.resolve(task.targetEngine());

        while (true) {
            int attemptNumber = retryCount + 1;
            transition(taskId, task, projectItemId, TaskState.RUNNING);
            log.info("Starting agent attempt. taskId={}, targetEngine={}, engine={}, attempt={}, maxRetries={}",
                    task.taskId(), task.targetEngine(), agentExecutionService.engineName(), attemptNumber, MAX_RETRIES);

            AgentExecutionResult sandboxResult = agentExecutionService.runAgent(currentAttempt);

            if (!sandboxResult.isSuccess()) {
                if (retryCount >= MAX_RETRIES) {
                    transition(taskId, task, projectItemId, TaskState.FAILED);
                    log.error("DevTask failed after maximum retries. taskId={}, attempts={}, lastExitCode={}, timedOut={}",
                            task.taskId(), attemptNumber, sandboxResult.exitCode(), sandboxResult.timedOut());
                    return;
                }

                retryCount++;
                devTaskRecordService.updateRetryCount(taskId, retryCount);
                transition(taskId, task, projectItemId, TaskState.RETRYING);

                String noiseReducedLog = ollamaNoiseReducer.reduceNoise(sandboxResult.logs());
                currentAttempt = taskWithRetryContext(currentAttempt, noiseReducedLog);
                log.warn("Agent attempt failed; retry scheduled. taskId={}, engine={}, nextAttempt={}, exitCode={}, timedOut={}, summary={}",
                        task.taskId(), sandboxResult.engine(), retryCount + 1, sandboxResult.exitCode(),
                        sandboxResult.timedOut(), noiseReducedLog);
                continue;
            }

            boolean hasDiff = gitSyncService.hasChanges(orchestratorProperties.workspace().containerPath());
            if (!hasDiff) {
                String outputSummary = truncateLog(sandboxResult.logs());
                log.warn("[交付失敗] taskId={}, 沙盒回傳成功但工作區無任何代碼變更 (git diff 為空)！AI 輸出摘要:\n{}",
                        task.taskId(), outputSummary);
                transition(taskId, task, projectItemId, TaskState.FAILED);
                return;
            }

            transition(taskId, task, projectItemId, TaskState.VERIFYING);
            log.info("[測試驗證] taskId={} Agent 已產生變更，啟動獨立 TestRunner 容器執行 mvn test。", task.taskId());

            TestExecutionResult testResult = testRunnerService.runTests(task);

            if (testResult.isSuccess()) {
                log.info("[交付核實] taskId={} 測試通過。Agent 輸出摘要:\n{}",
                        task.taskId(), truncateLog(sandboxResult.logs()));

                try {
                    gitSyncService.commitAndPush(task.taskId().toString());
                } catch (Exception ex) {
                    log.error("[交付失敗] taskId={} 測試已通過，但 commit/push 失敗。", task.taskId(), ex);
                    transition(taskId, task, projectItemId, TaskState.FAILED);
                    return;
                }

                transition(taskId, task, projectItemId, TaskState.SUCCESS);
                log.info("Orchestrator finished DevTask successfully. taskId={}, attempt={}, agentExitCode={}, testExitCode={}",
                        task.taskId(), attemptNumber, sandboxResult.exitCode(), testResult.exitCode());
                return;
            }

            log.warn("[測試失敗] taskId={}, testExitCode={}, timedOut={}, mavenLogSummary=\n{}",
                    task.taskId(), testResult.exitCode(), testResult.timedOut(), truncateLog(testResult.logs()));

            if (retryCount >= MAX_RETRIES) {
                transition(taskId, task, projectItemId, TaskState.FAILED);
                log.error("DevTask failed after maximum retries. taskId={}, attempts={}, lastTestExitCode={}, timedOut={}",
                        task.taskId(), attemptNumber, testResult.exitCode(), testResult.timedOut());
                return;
            }

            retryCount++;
            devTaskRecordService.updateRetryCount(taskId, retryCount);
            transition(taskId, task, projectItemId, TaskState.RETRYING);

            String noiseReducedLog = ollamaNoiseReducer.reduceNoise(testResult.logs());
            currentAttempt = taskWithRetryContext(currentAttempt, noiseReducedLog);
            log.warn("TestRunner attempt failed; retry scheduled. taskId={}, nextAttempt={}, exitCode={}, timedOut={}, summary={}",
                    task.taskId(), retryCount + 1, testResult.exitCode(), testResult.timedOut(), noiseReducedLog);
        }
    }

    private void transition(String taskId, DevTask task, Optional<String> projectItemId, TaskState nextState) {
        devTaskRecordService.updateState(taskId, nextState);
        log.info("Task state changed. taskId={}, state={}", task.taskId(), nextState);
        projectItemId.ifPresent(itemId -> gitHubProjectSyncService.updateCardStatus(itemId, nextState));

        if (nextState == TaskState.SUCCESS) {
            telegramNotifierService.sendMessage(task.telegramChatId(), TELEGRAM_SUCCESS_MESSAGE);
        } else if (nextState == TaskState.FAILED) {
            telegramNotifierService.sendMessage(task.telegramChatId(), TELEGRAM_FAILED_MESSAGE);
        }
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
