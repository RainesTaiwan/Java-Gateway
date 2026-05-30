package com.agentic.gateway.orchestrator.agent;

import com.agentic.gateway.dto.TargetEngine;
import com.agentic.gateway.orchestrator.agent.aider.ClaudeAiderExecutionService;
import com.agentic.gateway.orchestrator.agent.cursor.CursorAgentExecutionService;
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
    private CursorAgentExecutionService cursorAgentExecutionService;

    @Mock
    private LocalOllamaExecutionService localOllamaExecutionService;

    private AgentExecutionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentExecutionRegistry(
                claudeAiderExecutionService,
                cursorAgentExecutionService,
                localOllamaExecutionService
        );
    }

    @Test
    void resolveClaudeToClaudeAider() {
        assertThat(registry.resolve(TargetEngine.CLAUDE)).isSameAs(claudeAiderExecutionService);
    }

    @Test
    void resolveDefaultAndCursorToCursorAgent() {
        assertThat(registry.resolve(TargetEngine.DEFAULT)).isSameAs(cursorAgentExecutionService);
        assertThat(registry.resolve(TargetEngine.CURSOR)).isSameAs(cursorAgentExecutionService);
    }

    @Test
    void resolveLocalToLocalOllama() {
        assertThat(registry.resolve(TargetEngine.LOCAL)).isSameAs(localOllamaExecutionService);
    }
}
