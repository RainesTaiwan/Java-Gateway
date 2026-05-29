package com.agentic.gateway.orchestrator;

/**
 * Orchestrator 任務生命週期狀態。
 *
 * <p>這個 enum 是 Java Orchestrator 的最小狀態機骨架，負責描述一個從
 * ActiveMQ 取出的 {@code DevTask} 在自動化開發流程中的位置。後續接入 JGit、
 * Aider Docker 沙盒與測試重試機制時，應以這些狀態作為狀態轉移的單一語意來源。</p>
 */
public enum TaskState {
    /**
     * 已從 ActiveMQ 收到訊息，尚未正式開始處理。
     */
    RECEIVED,

    /**
     * 地端 LLM 正在將原始需求拆解成較小、可執行的子任務計畫。
     */
    PLANNING,

    /**
     * 任務已被 Orchestrator 接手，準備同步 GitHub 看板與本地工作區。
     */
    IN_PROGRESS,

    /**
     * 核心執行階段；未來會在此啟動 Aider / Claude / Local LLM 沙盒流程。
     */
    RUNNING,

    /**
     * 變更已產生，正在執行測試或其他驗證流程。
     */
    VERIFYING,

    /**
     * 任務成功完成，可推送結果並關閉對應 Issue / Project item。
     */
    SUCCESS,

    /**
     * 任務驗證失敗但仍在重試額度內，準備進入下一輪修復。
     */
    RETRYING,

    /**
     * 任務失敗且不可再重試，需要人工介入。
     */
    FAILED
}
