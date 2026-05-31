package com.agentic.gateway.orchestrator.test;

/**
 * 獨立 TestRunner 容器執行結果。
 *
 * @param exitCode 測試容器 process exit code；超時時固定回傳 124
 * @param timedOut 是否因超過硬限制而被 Orchestrator 主動停止
 * @param logs     Maven 輸出 log，供 Ollama 降噪與 Karpathy Loop 重試使用
 */
public record TestExecutionResult(int exitCode, boolean timedOut, String logs) {

    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }
}
