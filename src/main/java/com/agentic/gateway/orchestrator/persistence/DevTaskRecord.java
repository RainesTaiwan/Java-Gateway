package com.agentic.gateway.orchestrator.persistence;

import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.dto.TaskSource;
import com.agentic.gateway.dto.TargetEngine;
import com.agentic.gateway.orchestrator.TaskState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * DevTask 生命週期持久化實體。
 */
@Entity
@Table(name = "dev_task_record")
@Getter
@Setter
@NoArgsConstructor
public class DevTaskRecord {

    @Id
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TargetEngine targetEngine;

    @Lob
    @Column(nullable = false)
    private String payload;

    private String projectItemId;

    private String telegramChatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskState currentState;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public static DevTaskRecord fromDevTask(DevTask task, TaskState initialState) {
        Instant now = Instant.now();
        DevTaskRecord record = new DevTaskRecord();
        record.setTaskId(task.taskId().toString());
        record.setSource(task.source());
        record.setTargetEngine(task.targetEngine());
        record.setPayload(task.payload());
        record.setProjectItemId(task.projectItemId());
        record.setTelegramChatId(task.telegramChatId());
        record.setCurrentState(initialState);
        record.setRetryCount(0);
        record.setCreatedAt(task.createdAt() != null ? task.createdAt() : now);
        record.setUpdatedAt(now);
        return record;
    }

    public DevTask toDevTask() {
        return new DevTask(
                UUID.fromString(taskId),
                source,
                targetEngine,
                payload,
                projectItemId,
                telegramChatId,
                createdAt
        );
    }
}
