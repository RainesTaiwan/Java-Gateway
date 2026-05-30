package com.agentic.gateway.orchestrator.agent.cursor;

import com.agentic.gateway.config.OrchestratorProperties;
import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.agent.AgentExecutionResult;
import com.agentic.gateway.orchestrator.agent.AgentExecutionService;
import com.agentic.gateway.orchestrator.agent.DockerAgentRunSpec;
import com.agentic.gateway.orchestrator.agent.DockerAgentRunner;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 使用自建 Cursor SDK runner 映像在 workspace 內執行開發任務。
 *
 * <p>由 {@link com.agentic.gateway.orchestrator.agent.AgentExecutionRegistry}
 * 依 {@link com.agentic.gateway.dto.TargetEngine} 路由；{@code DEFAULT} 與 {@code CURSOR} 皆走此引擎。</p>
 */
@Service
@RequiredArgsConstructor
public class CursorAgentExecutionService implements AgentExecutionService {

    private static final String ENGINE = "cursor";
    private static final String WORKDIR = "/app";
    private static final String DELIVERY_PROMPT_TEMPLATE = """
            You are running inside the local repository mounted at /app.

            Complete the user's request by directly editing files in this repository.
            Do not only explain what should be changed.
            If the request is impossible or unsafe, explain why clearly.
            After editing, summarize the files changed and the reason.

            User request:
            %s
            """;

    private final OrchestratorProperties orchestratorProperties;
    private final DockerAgentRunner dockerAgentRunner;

    @Override
    public AgentExecutionResult runAgent(DevTask task) {
        DockerAgentRunSpec spec = new DockerAgentRunSpec(
                orchestratorProperties.cursor().image(),
                "cursor-sandbox",
                WORKDIR,
                "cd " + WORKDIR + " && node /runner/cursor-runner.mjs",
                buildEnvironment(task));
        return dockerAgentRunner.run(task, spec, ENGINE);
    }

    @Override
    public String engineName() {
        return ENGINE;
    }

    private List<String> buildEnvironment(DevTask task) {
        List<String> environment = new ArrayList<>();
        String apiKey = System.getenv("CURSOR_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            environment.add("CURSOR_API_KEY=" + apiKey);
        }
        environment.add("CURSOR_MODEL=" + orchestratorProperties.cursor().model());
        String payload = DELIVERY_PROMPT_TEMPLATE.formatted(task.payload() == null ? "" : task.payload());
        environment.add("AGENT_PROMPT_B64="
                + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)));
        return environment;
    }

}
