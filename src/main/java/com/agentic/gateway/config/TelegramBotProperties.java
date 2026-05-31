package com.agentic.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telegram Bot 設定。
 *
 * <p>對應 application.yml 的 telegram.bot 區塊。
 * Token 與允許使用者 ID 應透過環境變數或 application-local.yml 注入。</p>
 */
@ConfigurationProperties(prefix = "telegram.bot")
public record TelegramBotProperties(
        String username,
        String token,
        Long allowedUserId
) {
}
