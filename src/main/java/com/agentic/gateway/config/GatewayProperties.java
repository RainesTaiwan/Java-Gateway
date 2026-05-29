package com.agentic.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway 自訂設定。
 *
 * <p>所有敏感資訊均由環境變數注入至 application.yml，再由此型別集中管理，
 * 避免 Secret 分散在各服務類別中。</p>
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        Telegram telegram,
        Github github,
        Jms jms
) {

    /**
     * Telegram Bot 與允許使用者設定。
     */
    public record Telegram(
            String botUsername,
            String botToken,
            Long allowedUserId
    ) {
    }

    /**
     * GitHub Webhook 簽章驗證設定。
     */
    public record Github(
            String webhookSecret
    ) {
    }

    /**
     * JMS 佇列設定。
     */
    public record Jms(
            String commandQueue
    ) {
    }
}
