package com.agentic.gateway.orchestrator.agent;

import com.agentic.gateway.dto.TargetEngine;
import com.agentic.gateway.orchestrator.agent.aider.ClaudeAiderExecutionService;
import com.agentic.gateway.orchestrator.agent.ollama.LocalOllamaExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AgentExecutionRegistryTest {

    @Mock
    private ClaudeAiderExecutionService claudeAiderExecutionService;

    @Mock
    private LocalOllamaExecutionService localOllamaExecutionService;

    private AgentExecutionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentExecutionRegistry(claudeAiderExecutionService, localOllamaExecutionService);
    }

    @Test
    void resolveClaudeAndDefaultToClaudeAider() {
        assertThat(registry.resolve(TargetEngine.CLAUDE)).isSameAs(claudeAiderExecutionService);
        assertThat(registry.resolve(TargetEngine.DEFAULT)).isSameAs(claudeAiderExecutionService);
    }

    @Test
    void resolveLocalToLocalOllama() {
        assertThat(registry.resolve(TargetEngine.LOCAL)).isSameAs(localOllamaExecutionService);
    }
}
