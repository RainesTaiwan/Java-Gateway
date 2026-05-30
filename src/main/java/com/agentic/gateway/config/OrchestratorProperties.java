package com.agentic.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Java Orchestrator 執行期設定。
 *
 * <p>此設定集中管理 Host 端工作目錄、JGit 遠端來源、Aider 沙盒映像檔與 Docker 網路。
 * 所有會依環境變動的值都應由環境變數注入，避免把本機路徑或 API key 寫死在服務類別中。</p>
 */
@ConfigurationProperties(prefix = "orchestrator")
public record OrchestratorProperties(
        Git git,
        Workspace workspace,
        Agent agent,
        Cursor cursor,
        Aider aider,
        Docker docker,
        Ollama ollama,
        TestRunner testRunner
) {

    public record Git(
            String repositoryUri,
            String branch
    ) {
    }

    public record Workspace(
            String containerPath,
            String hostPath
    ) {
    }

    public record Agent(
            String engine,
            Integer timeoutSeconds
    ) {
    }

    public record Cursor(
            String image,
            String model
    ) {
    }

    public record Aider(
            String image,
            String model
    ) {
    }

    public record Docker(
            String host,
            String networkName
    ) {
    }

    public record Ollama(
            String baseUrl,
            String model,
            Integer timeoutSeconds
    ) {
    }

    public record TestRunner(
            String image,
            Integer timeoutSeconds
    ) {
    }
}
