package com.agentic.gateway.github;

/**
 * 從 GitHub Webhook 擷取出的任務內容。
 *
 * @param title         Issue 或 Project item 標題
 * @param url           Issue 或相關資源 URL
 * @param projectItemId GitHub Projects v2 item node ID，例如 PVTI_xxx；非看板來源可為 null
 */
public record GitHubTaskPayload(String title, String url, String projectItemId) {

    /**
     * 轉成下游 AI 引擎容易理解的純文字任務描述。
     */
    public String toTaskText() {
        return "GitHub 任務：" + title + System.lineSeparator() + "URL：" + url;
    }
}
