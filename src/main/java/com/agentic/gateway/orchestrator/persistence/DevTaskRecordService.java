package com.agentic.gateway.orchestrator.persistence;

import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.TaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * DevTask 持久化與狀態更新服務。
 *
 * <p>獨立於 {@code @Async} 編排器，確保 {@link Transactional} 透過 Spring Proxy 正確生效。</p>
 */
@Service
@RequiredArgsConstructor
public class DevTaskRecordService {

    private final DevTaskRepository repository;

    @Transactional
    public void persistReceived(DevTask task) {
        repository.save(DevTaskRecord.fromDevTask(task, TaskState.RECEIVED));
    }

    @Transactional(readOnly = true)
    public DevTask loadDevTask(String taskId) {
        return repository.findById(taskId)
                .map(DevTaskRecord::toDevTask)
                .orElseThrow(() -> new IllegalStateException("DevTask not found in database. taskId=" + taskId));
    }

    @Transactional
    public void updateState(String taskId, TaskState state) {
        DevTaskRecord record = requireRecord(taskId);
        record.setCurrentState(state);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
    }

    @Transactional
    public void updateRetryCount(String taskId, int retryCount) {
        DevTaskRecord record = requireRecord(taskId);
        record.setRetryCount(retryCount);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
    }

    private DevTaskRecord requireRecord(String taskId) {
        return repository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("DevTask not found in database. taskId=" + taskId));
    }
}
