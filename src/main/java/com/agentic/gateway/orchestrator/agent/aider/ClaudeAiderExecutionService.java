package com.agentic.gateway.orchestrator.agent.aider;

import com.agentic.gateway.config.OrchestratorProperties;
import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.agent.AgentExecutionResult;
import com.agentic.gateway.orchestrator.agent.AgentExecutionService;
import com.agentic.gateway.orchestrator.agent.DockerAgentRunSpec;
import com.agentic.gateway.orchestrator.agent.DockerAgentRunner;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 使用 Aider Docker 映像執行 Claude / 預設開發任務。
 */
@Service
@RequiredArgsConstructor
public class ClaudeAiderExecutionService implements AgentExecutionService {

    private static final String ENGINE = "claude-aider";
    private static final String WORKDIR = "/app";

    private final OrchestratorProperties orchestratorProperties;
    private final DockerAgentRunner dockerAgentRunner;

    @Override
    public AgentExecutionResult runAgent(DevTask task) {
        DockerAgentRunSpec spec = new DockerAgentRunSpec(
                orchestratorProperties.aider().image(),
                "aider-sandbox",
                WORKDIR,
                buildAiderCommand(task),
                buildEnvironment());
        return dockerAgentRunner.run(task, spec, ENGINE);
    }

    @Override
    public String engineName() {
        return ENGINE;
    }

    private List<String> buildEnvironment() {
        List<String> environment = new ArrayList<>();
        putIfPresent(environment, "CLAUDE_API_KEY", System.getenv("CLAUDE_API_KEY"));
        putIfPresent(environment, "ANTHROPIC_API_KEY", System.getenv("CLAUDE_API_KEY"));
        putIfPresent(environment, "OLLAMA_BASE_URL", System.getenv("OLLAMA_BASE_URL"));
        putIfPresent(environment, "OLLAMA_API_BASE", System.getenv("OLLAMA_BASE_URL"));
        return environment;
    }

    private String buildAiderCommand(DevTask task) {
        String message = "根據以下 Spec 修改程式碼:\n" + (task.payload() == null ? "" : task.payload());
        return "cd " + shellQuote(WORKDIR)
                + " && aider --yes-always --model "
                + shellQuote(orchestratorProperties.aider().model())
                + " --message " + shellQuote(message);
    }

    private static void putIfPresent(List<String> environment, String key, String value) {
        if (value != null && !value.isBlank()) {
            environment.add(key + "=" + value);
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
