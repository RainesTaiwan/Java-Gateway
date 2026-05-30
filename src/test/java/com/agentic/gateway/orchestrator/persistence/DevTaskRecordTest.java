package com.agentic.gateway.orchestrator.persistence;

import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.dto.TaskSource;
import com.agentic.gateway.dto.TargetEngine;
import com.agentic.gateway.orchestrator.TaskState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DevTaskRecordTest {

    @Test
    void fromDevTaskAndToDevTaskRoundTrip() {
        UUID taskId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-05-30T12:00:00Z");
        DevTask original = new DevTask(
                taskId,
                TaskSource.TELEGRAM,
                TargetEngine.CURSOR,
                "fix the bug",
                null,
                "12345",
                createdAt
        );

        DevTaskRecord record = DevTaskRecord.fromDevTask(original, TaskState.RECEIVED);
        DevTask restored = record.toDevTask();

        assertThat(record.getTaskId()).isEqualTo(taskId.toString());
        assertThat(record.getCurrentState()).isEqualTo(TaskState.RECEIVED);
        assertThat(record.getRetryCount()).isZero();
        assertThat(restored.taskId()).isEqualTo(taskId);
        assertThat(restored.source()).isEqualTo(TaskSource.TELEGRAM);
        assertThat(restored.targetEngine()).isEqualTo(TargetEngine.CURSOR);
        assertThat(restored.payload()).isEqualTo("fix the bug");
        assertThat(restored.telegramChatId()).isEqualTo("12345");
        assertThat(restored.createdAt()).isEqualTo(createdAt);
    }
}
