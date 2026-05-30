package com.agentic.gateway.orchestrator.agent;

import com.agentic.gateway.dto.TargetEngine;
import com.agentic.gateway.orchestrator.agent.aider.ClaudeAiderExecutionService;
import com.agentic.gateway.orchestrator.agent.ollama.LocalOllamaExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 依 {@link TargetEngine} 動態解析對應的 {@link AgentExecutionService} 實作。
 */
@Component
@RequiredArgsConstructor
public class AgentExecutionRegistry {

    private final ClaudeAiderExecutionService claudeAiderExecutionService;
    private final LocalOllamaExecutionService localOllamaExecutionService;

    public AgentExecutionService resolve(TargetEngine targetEngine) {
        return switch (targetEngine) {
            case CLAUDE, DEFAULT -> claudeAiderExecutionService;
            case LOCAL -> localOllamaExecutionService;
        };
    }
}
