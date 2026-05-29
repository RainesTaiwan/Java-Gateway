package com.agentic.gateway.orchestrator.agent;

import com.agentic.gateway.dto.DevTask;

/**
 * 開發引擎執行抽象介面。
 *
 * <p>Orchestrator 透過此介面呼叫 Cursor 或 Aider，而不直接耦合任一實作。
 * 切換引擎時只需調整設定與 Spring Bean 選擇，狀態機與 Karpathy Loop 無需改動。</p>
 */
public interface AgentExecutionService {

    /**
     * 在已同步的 workspace 上執行一次開發任務。
     */
    AgentExecutionResult runAgent(DevTask task);

    /**
     * 回傳目前引擎識別名稱，供 log 與觀測使用。
     */
    String engineName();
}
