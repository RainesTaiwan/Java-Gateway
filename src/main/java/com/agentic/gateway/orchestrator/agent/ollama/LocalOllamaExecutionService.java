package com.agentic.gateway.orchestrator.agent.ollama;

import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.agent.AgentExecutionResult;
import com.agentic.gateway.orchestrator.agent.AgentExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 地端 Ollama 執行引擎占位實作，對應 {@link com.agentic.gateway.dto.TargetEngine#LOCAL}。
 */
@Slf4j
@Service
public class LocalOllamaExecutionService implements AgentExecutionService {

    private static final String ENGINE = "local-ollama";
    private static final String TODO_MESSAGE = "[TODO] 尚未實作地端執行引擎";

    @Override
    public AgentExecutionResult runAgent(DevTask task) {
        log.warn("{} taskId={}", TODO_MESSAGE, task.taskId());
        return new AgentExecutionResult(1, false, TODO_MESSAGE, ENGINE);
    }

    @Override
    public String engineName() {
        return ENGINE;
    }
}
