package com.agentic.gateway.orchestrator.agent;

import java.util.List;

/**
 * 動態 Docker 沙盒執行規格。
 *
 * @param image              要啟動的 Docker image
 * @param containerNamePrefix 容器名稱前綴
 * @param workdir            容器內工作目錄
 * @param shellCommand       透過 {@code sh -lc} 執行的命令
 * @param environment        容器環境變數
 */
public record DockerAgentRunSpec(
        String image,
        String containerNamePrefix,
        String workdir,
        String shellCommand,
        List<String> environment
) {
}
