package com.agentic.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway 自訂設定。
 *
 * <p>此類別只保存非平台專屬的 Gateway 設定，例如 JMS 佇列名稱。
 * Telegram 與 GitHub 的敏感設定分別由專用 Properties 類別管理。</p>
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
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
