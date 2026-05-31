package com.agentic.gateway.orchestrator.recovery;

import com.agentic.gateway.orchestrator.WorkflowOrchestrator;
import com.agentic.gateway.orchestrator.persistence.DevTaskRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring Boot 啟動後恢復資料庫中尚未終局的孤兒任務。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevTaskRecoveryScheduler {

    private final DevTaskRecordService devTaskRecordService;
    private final WorkflowOrchestrator workflowOrchestrator;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        List<String> taskIds = devTaskRecordService.findRecoverableTaskIds();
        if (taskIds.isEmpty()) {
            log.info("No recoverable DevTask records found on startup.");
            return;
        }

        log.warn("Recovering interrupted DevTask records on startup. count={}, taskIds={}", taskIds.size(), taskIds);
        for (String taskId : taskIds) {
            workflowOrchestrator.processTaskAsync(taskId);
        }
    }
}
