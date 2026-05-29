package com.agentic.gateway.orchestrator.sandbox;

/**
 * Aider 沙盒執行結果。
 *
 * @param exitCode Docker container 的 process exit code；超時時固定回傳 124
 * @param timedOut 是否因超過硬限制而被 Orchestrator 主動停止
 * @param logs     容器尾端 log，供後續 Ollama 降噪摘要與重試策略使用
 */
public record AiderSandboxResult(int exitCode, boolean timedOut, String logs) {

    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }
}
