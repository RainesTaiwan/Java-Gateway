package com.agentic.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub Webhook 設定。
 *
 * <p>對應 application.yml 的 github.webhook 區塊。
 * Secret 必須由環境變數或本機忽略檔提供，不可寫死後提交。</p>
 */
@ConfigurationProperties(prefix = "github.webhook")
public record GitHubWebhookProperties(
        String secret
) {
}
