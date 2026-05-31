package com.agentic.gateway.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.BotSession;

/**
 * 明確啟動 Telegram Long Polling。
 *
 * <p>僅建立 {@link TelegramCommandBot} Bean 並不足以開始收訊息，
 * 必須呼叫 {@link TelegramBotsApi#registerBot} 才會真正連上 Telegram。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramPollingBootstrap {

    private final TelegramBotsApi telegramBotsApi;
    private final TelegramCommandBot telegramCommandBot;

    @EventListener(ApplicationReadyEvent.class)
    public void registerLongPollingBot() {
        try {
            BotSession session = telegramBotsApi.registerBot(telegramCommandBot);
            boolean running = session != null && session.isRunning();
            log.info("Telegram Bot 註冊成功，正在監聽指令... botUsername={}, allowedUserId={}, pollingRunning={}",
                    telegramCommandBot.getBotUsername(),
                    telegramCommandBot.getAllowedUserId(),
                    running);
        } catch (TelegramApiException ex) {
            log.error("Telegram Bot 註冊失敗，無法開始 Long Polling。", ex);
        }
    }
}
