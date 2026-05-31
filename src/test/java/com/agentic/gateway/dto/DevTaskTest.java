package com.agentic.gateway.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevTaskTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void serializeAndDeserializeProjectItemId() throws Exception {
        DevTask task = DevTask.create(
                TaskSource.GITHUB,
                TargetEngine.DEFAULT,
                "GitHub 任務",
                "PVTI_lADOExample"
        );

        String json = objectMapper.writeValueAsString(task);
        DevTask deserialized = objectMapper.readValue(json, DevTask.class);

        assertThat(json).contains("\"projectItemId\":\"PVTI_lADOExample\"");
        assertThat(deserialized.projectItemId()).isEqualTo("PVTI_lADOExample");
    }

    @Test
    void telegramTaskAllowsNullProjectItemId() {
        DevTask task = DevTask.create(TaskSource.TELEGRAM, TargetEngine.CLAUDE, "幫我改 Code");

        assertThat(task.projectItemId()).isNull();
    }

    @Test
    void telegramTaskCanCarryChatIdForCompletionNotification() throws Exception {
        DevTask task = DevTask.create(
                TaskSource.TELEGRAM,
                TargetEngine.DEFAULT,
                "幫我改 Code",
                null,
                "1377489086"
        );

        String json = objectMapper.writeValueAsString(task);
        DevTask deserialized = objectMapper.readValue(json, DevTask.class);

        assertThat(json).contains("\"telegramChatId\":\"1377489086\"");
        assertThat(deserialized.telegramChatId()).isEqualTo("1377489086");
    }

    @Test
    void githubTaskCanCarryDeliveryIdForIdempotency() throws Exception {
        DevTask task = DevTask.createGitHubTask(
                TaskSource.GITHUB,
                TargetEngine.DEFAULT,
                "GitHub 任務",
                "PVTI_lADOExample",
                "delivery-123"
        );

        String json = objectMapper.writeValueAsString(task);
        DevTask deserialized = objectMapper.readValue(json, DevTask.class);

        assertThat(json).contains("\"deliveryId\":\"delivery-123\"");
        assertThat(deserialized.deliveryId()).isEqualTo("delivery-123");
    }

    @Test
    void deserializeLegacyTaskWithoutTelegramChatId() throws Exception {
        String json = """
                {
                  "taskId": "63250562-4030-42d0-a1db-b087599afd94",
                  "source": "TELEGRAM",
                  "targetEngine": "DEFAULT",
                  "payload": "legacy task",
                  "projectItemId": null,
                  "createdAt": "2026-05-29T17:50:00Z"
                }
                """;

        DevTask deserialized = objectMapper.readValue(json, DevTask.class);

        assertThat(deserialized.telegramChatId()).isNull();
        assertThat(deserialized.deliveryId()).isNull();
    }
}
