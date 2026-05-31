package com.agentic.gateway.orchestrator;

import com.agentic.gateway.config.OrchestratorProperties;
import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.agent.AgentExecutionRegistry;
import com.agentic.gateway.orchestrator.agent.AgentExecutionResult;
import com.agentic.gateway.orchestrator.agent.AgentExecutionService;
import com.agentic.gateway.orchestrator.events.TaskStateChangedEvent;
import com.agentic.gateway.orchestrator.git.GitSyncService;
import com.agentic.gateway.orchestrator.ollama.OllamaNoiseReducer;
import com.agentic.gateway.orchestrator.ollama.TaskSplitterService;
import com.agentic.gateway.orchestrator.persistence.DevTaskRecordService;
import com.agentic.gateway.orchestrator.test.TestExecutionResult;
import com.agentic.gateway.orchestrator.test.TestRunnerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

    private final OrchestratorProperties orchestratorProperties;
    private final GitSyncService gitSyncService;
    private final AgentExecutionRegistry agentExecutionRegistry;
    private final OllamaNoiseReducer ollamaNoiseReducer;
    private final TaskSplitterService taskSplitterService;
    private final TestRunnerService testRunnerService;
    private final DevTaskRecordService devTaskRecordService;
    private final ApplicationEventPublisher eventPublisher;

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
        TaskState currentState = devTaskRecordService.loadState(taskId);
        if (isTerminal(currentState)) {
            log.info("Skip DevTask because it is already terminal. taskId={}, state={}", taskId, currentState);
            return;
        }

        log.info("Orchestrator received DevTask. taskId={}, source={}, targetEngine={}",
                task.taskId(), task.source(), task.targetEngine());
        log.info("DevTask payload preview. taskId={}, payload={}", task.taskId(), truncateLog(task.payload()));

        if (task.projectItemId() == null || task.projectItemId().isBlank()) {
            log.info("[INFO] Task source is Telegram, skipping GitHub project card sync.");
        }

        try {
            transition(taskId, task, TaskState.PLANNING);
            String plannedSpec = taskSplitterService.splitTask(task.payload());
            log.info("Task split plan generated. taskId={}, plan=\n{}", task.taskId(), truncateLog(plannedSpec));

            transition(taskId, task, TaskState.IN_PROGRESS);

            if (shouldSyncCleanBaseline(currentState)) {
                log.info("Syncing Git workspace to clean remote baseline. taskId={}, previousState={}",
                        task.taskId(), currentState);
                gitSyncService.syncRepository();
            } else {
                log.warn("Skip Git workspace sync for recovered task to preserve existing changes. taskId={}, previousState={}",
                        task.taskId(), currentState);
            }

            DevTask currentAttempt = taskWithPlannedSpec(task, plannedSpec);
            int retryCount = devTaskRecordService.loadRetryCount(taskId);

            AgentExecutionService agentExecutionService = agentExecutionRegistry.resolve(task.targetEngine());

            while (true) {
                int attemptNumber = retryCount + 1;
                transition(taskId, task, TaskState.RUNNING);
                log.info("Starting agent attempt. taskId={}, targetEngine={}, engine={}, attempt={}, maxRetries={}",
                        task.taskId(), task.targetEngine(), agentExecutionService.engineName(), attemptNumber, MAX_RETRIES);

                AgentExecutionResult sandboxResult = agentExecutionService.runAgent(currentAttempt);

                if (!sandboxResult.isSuccess()) {
                    if (retryCount >= MAX_RETRIES) {
                        transition(taskId, task, TaskState.FAILED);
                        log.error("DevTask failed after maximum retries. taskId={}, attempts={}, lastExitCode={}, timedOut={}",
                                task.taskId(), attemptNumber, sandboxResult.exitCode(), sandboxResult.timedOut());
                        return;
                    }

                    retryCount++;
                    devTaskRecordService.updateRetryCount(taskId, retryCount);
                    transition(taskId, task, TaskState.RETRYING);

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
                    transition(taskId, task, TaskState.FAILED);
                    return;
                }

                transition(taskId, task, TaskState.VERIFYING);
                log.info("[測試驗證] taskId={} Agent 已產生變更，啟動獨立 TestRunner 容器執行 mvn test。", task.taskId());

                TestExecutionResult testResult = testRunnerService.runTests(task);

                if (testResult.isSuccess()) {
                    log.info("[交付核實] taskId={} 測試通過。Agent 輸出摘要:\n{}",
                            task.taskId(), truncateLog(sandboxResult.logs()));

                    Optional<String> commitSha;
                    try {
                        commitSha = gitSyncService.commitAndPush(task.taskId().toString());
                    } catch (Exception ex) {
                        log.error("[交付失敗] taskId={} 測試已通過，但 commit/push 失敗。", task.taskId(), ex);
                        transition(taskId, task, TaskState.FAILED);
                        return;
                    }

                    if (commitSha.isEmpty()) {
                        log.error("[交付失敗] taskId={} push 未回傳 commit SHA，不允許切換 SUCCESS。", task.taskId());
                        transition(taskId, task, TaskState.FAILED);
                        return;
                    }

                    String deliverySummary = "agentExitCode=%d, testExitCode=%d, attempt=%d"
                            .formatted(sandboxResult.exitCode(), testResult.exitCode(), attemptNumber);
                    transition(taskId, task, TaskState.SUCCESS, commitSha.get(), deliverySummary);
                    log.info("Orchestrator finished DevTask successfully. taskId={}, attempt={}, agentExitCode={}, testExitCode={}, commitSha={}",
                            task.taskId(), attemptNumber, sandboxResult.exitCode(), testResult.exitCode(), commitSha.get());
                    return;
                }

                log.warn("[測試失敗] taskId={}, testExitCode={}, timedOut={}, mavenLogSummary=\n{}",
                        task.taskId(), testResult.exitCode(), testResult.timedOut(), truncateLog(testResult.logs()));

                if (retryCount >= MAX_RETRIES) {
                    transition(taskId, task, TaskState.FAILED);
                    log.error("DevTask failed after maximum retries. taskId={}, attempts={}, lastTestExitCode={}, timedOut={}",
                            task.taskId(), attemptNumber, testResult.exitCode(), testResult.timedOut());
                    return;
                }

                retryCount++;
                devTaskRecordService.updateRetryCount(taskId, retryCount);
                transition(taskId, task, TaskState.RETRYING);

                String noiseReducedLog = ollamaNoiseReducer.reduceNoise(testResult.logs());
                currentAttempt = taskWithRetryContext(currentAttempt, noiseReducedLog);
                log.warn("TestRunner attempt failed; retry scheduled. taskId={}, nextAttempt={}, exitCode={}, timedOut={}, summary={}",
                        task.taskId(), retryCount + 1, testResult.exitCode(), testResult.timedOut(), noiseReducedLog);
            }
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.warn("DevTask state update lost optimistic lock; another worker likely owns this task. taskId={}",
                    task.taskId(), ex);
        } catch (Exception ex) {
            log.error("DevTask crashed during orchestration; marking FAILED. taskId={}", task.taskId(), ex);
            transition(taskId, task, TaskState.FAILED);
        }
    }

    private void transition(String taskId, DevTask task, TaskState nextState) {
        devTaskRecordService.updateState(taskId, nextState);
        log.info("Task state changed. taskId={}, state={}", task.taskId(), nextState);
        eventPublisher.publishEvent(new TaskStateChangedEvent(taskId, task, nextState, null, null));
    }

    private void transition(
            String taskId,
            DevTask task,
            TaskState nextState,
            String commitSha,
            String resultSummary
    ) {
        DevTaskRecordService.DeliveryResult deliveryResult = devTaskRecordService.updateStateWithDeliveryResult(
                taskId,
                nextState,
                commitSha,
                resultSummary
        );
        log.info("Task state changed. taskId={}, state={}, commitSha={}",
                task.taskId(), nextState, deliveryResult.commitSha());
        eventPublisher.publishEvent(new TaskStateChangedEvent(
                taskId,
                task,
                nextState,
                deliveryResult.commitSha(),
                deliveryResult.resultSummary()
        ));
    }

    private boolean isTerminal(TaskState state) {
        return state == TaskState.SUCCESS || state == TaskState.FAILED;
    }

    private boolean shouldSyncCleanBaseline(TaskState currentState) {
        return currentState == TaskState.QUEUED;
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
                task.deliveryId(),
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
                task.deliveryId(),
                task.createdAt()
        );
    }

}
