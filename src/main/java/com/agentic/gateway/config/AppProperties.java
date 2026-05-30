package com.agentic.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 應用程式自訂設定。
 *
 * <p>保存非平台專屬的 Gateway / Orchestrator 共用設定，例如 JMS 佇列名稱。</p>
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jms jms
) {

    /**
     * JMS 佇列設定。
     */
    public record Jms(
            String commandQueue
    ) {
    }
}
