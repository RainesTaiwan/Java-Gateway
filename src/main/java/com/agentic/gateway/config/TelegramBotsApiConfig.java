package com.agentic.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * 手動建立 TelegramBotsApi。
 *
 * <p>telegrambots-spring-boot-starter 6.9 在 Spring Boot 3 下不一定會自動註冊 API Bean，
 * 因此 Gateway 自行建立，確保 Long Polling 可以啟動。</p>
 */
@Configuration
public class TelegramBotsApiConfig {

    @Bean
    public TelegramBotsApi telegramBotsApi() throws TelegramApiException {
        return new TelegramBotsApi(DefaultBotSession.class);
    }
}
