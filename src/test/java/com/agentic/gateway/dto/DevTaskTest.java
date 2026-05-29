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
}
