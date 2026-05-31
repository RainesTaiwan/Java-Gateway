package com.agentic.gateway.orchestrator.agent;

/**
 * 開發引擎沙盒執行結果。
 *
 * <p>無論底層是 Cursor SDK runner 或 Aider container，Orchestrator 都只依賴此結果
 * 判斷成功、失敗、超時與後續 Ollama 降噪重試。</p>
 *
 * @param exitCode 容器 process exit code；超時時固定回傳 124
 * @param timedOut 是否因超過硬限制而被 Orchestrator 主動停止
 * @param logs     容器尾端 log，供後續 Ollama 降噪摘要與重試策略使用
 * @param engine   實際使用的開發引擎名稱，例如 cursor 或 aider
 */
public record AgentExecutionResult(int exitCode, boolean timedOut, String logs, String engine) {

    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }
}
