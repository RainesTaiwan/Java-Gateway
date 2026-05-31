package com.agentic.gateway.orchestrator.persistence;

import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.TaskState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * DevTask 持久化與狀態更新服務。
 *
 * <p>獨立於 {@code @Async} 編排器，確保 {@link Transactional} 透過 Spring Proxy 正確生效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevTaskRecordService {

    private static final List<TaskState> RECOVERABLE_STATES = List.of(
            TaskState.QUEUED,
            TaskState.PLANNING,
            TaskState.IN_PROGRESS,
            TaskState.RUNNING,
            TaskState.VERIFYING,
            TaskState.RETRYING
    );
    private static final List<TaskState> ACTIVE_STATES = List.of(
            TaskState.QUEUED,
            TaskState.PLANNING,
            TaskState.IN_PROGRESS,
            TaskState.RUNNING,
            TaskState.VERIFYING,
            TaskState.RETRYING
    );

    private final DevTaskRepository repository;

    @Transactional
    public EnqueueResult enqueueIfAbsent(DevTask task) {
        String taskId = task.taskId().toString();
        Optional<DevTaskRecord> duplicateDelivery = findDuplicateDelivery(task, taskId);
        if (duplicateDelivery.isPresent()) {
            log.info("[INFO] Ignored duplicate GitHub delivery: {}", task.deliveryId());
            return new EnqueueResult(taskId, duplicateDelivery.get().getCurrentState(), false, false);
        }

        return repository.findById(taskId)
                .map(existing -> {
                    if (existing.getCurrentState().ordinal() > TaskState.QUEUED.ordinal()) {
                        return new EnqueueResult(taskId, existing.getCurrentState(), false, false);
                    }
                    return new EnqueueResult(taskId, existing.getCurrentState(), false, true);
                })
                .orElseGet(() -> {
                    try {
                        repository.saveAndFlush(DevTaskRecord.fromDevTask(task, TaskState.QUEUED));
                        return new EnqueueResult(taskId, TaskState.QUEUED, true, true);
                    } catch (DataIntegrityViolationException ex) {
                        if (task.deliveryId() == null || task.deliveryId().isBlank()) {
                            throw ex;
                        }
                        log.info("[INFO] Ignored duplicate GitHub delivery: {}", task.deliveryId());
                        return new EnqueueResult(taskId, TaskState.QUEUED, false, false);
                    }
                });
    }

    @Transactional
    public EnqueueResult reserveGitHubDeliveryIfAbsent(DevTask task) {
        if (task.deliveryId() == null || task.deliveryId().isBlank()) {
            return enqueueIfAbsent(task);
        }
        return enqueueIfAbsent(task);
    }

    @Transactional
    public void persistReceived(DevTask task) {
        repository.save(DevTaskRecord.fromDevTask(task, TaskState.RECEIVED));
    }

    @Transactional(readOnly = true)
    public TaskState loadState(String taskId) {
        return requireRecord(taskId).getCurrentState();
    }

    @Transactional(readOnly = true)
    public int loadRetryCount(String taskId) {
        return requireRecord(taskId).getRetryCount();
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
    public DeliveryResult updateStateWithDeliveryResult(
            String taskId,
            TaskState state,
            String commitSha,
            String resultSummary
    ) {
        DevTaskRecord record = requireRecord(taskId);
        record.setCurrentState(state);
        record.setCommitSha(commitSha);
        record.setResultSummary(resultSummary);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return new DeliveryResult(record.getCommitSha(), record.getResultSummary());
    }

    @Transactional
    public void updateRetryCount(String taskId, int retryCount) {
        DevTaskRecord record = requireRecord(taskId);
        record.setRetryCount(retryCount);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
    }

    @Transactional(readOnly = true)
    public List<String> findRecoverableTaskIds() {
        return repository.findByCurrentStateIn(RECOVERABLE_STATES).stream()
                .map(DevTaskRecord::getTaskId)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DevTaskRecord> getActiveTasks() {
        return repository.findByCurrentStateInOrderByUpdatedAtDesc(ACTIVE_STATES);
    }

    private DevTaskRecord requireRecord(String taskId) {
        return repository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("DevTask not found in database. taskId=" + taskId));
    }

    private Optional<DevTaskRecord> findDuplicateDelivery(DevTask task, String taskId) {
        String deliveryId = task.deliveryId();
        if (deliveryId == null || deliveryId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByDeliveryId(deliveryId.trim())
                .filter(existing -> !existing.getTaskId().equals(taskId));
    }

    public record EnqueueResult(
            String taskId,
            TaskState existingState,
            boolean created,
            boolean shouldDispatch
    ) {
    }

    public record DeliveryResult(String commitSha, String resultSummary) {
    }
}
