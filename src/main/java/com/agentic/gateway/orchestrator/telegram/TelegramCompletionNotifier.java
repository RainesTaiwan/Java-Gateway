package com.agentic.gateway.orchestrator.telegram;

import com.agentic.gateway.config.TelegramBotProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Orchestrator 專用的 Telegram outbound 通知服務。
 *
 * <p>此服務不啟動 polling，只在任務終點透過 Telegram Bot API 回報交付結果。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramCompletionNotifier {

    private static final String TELEGRAM_API_BASE_URL = "https://api.telegram.org";

    private final TelegramBotProperties telegramBotProperties;
    private final WebClient.Builder webClientBuilder;

    public void sendReport(String chatId, String message) {
        if (chatId == null || chatId.isBlank()) {
            log.debug("Skip Telegram completion notification because chatId is empty.");
            return;
        }
        if (telegramBotProperties.token() == null || telegramBotProperties.token().isBlank()) {
            log.warn("Skip Telegram completion notification because TELEGRAM_BOT_TOKEN is empty. chatId={}", chatId);
            return;
        }

        try {
            webClientBuilder.baseUrl(TELEGRAM_API_BASE_URL)
                    .build()
                    .post()
                    .uri("/bot" + telegramBotProperties.token() + "/sendMessage")
                    .body(BodyInserters.fromFormData("chat_id", chatId)
                            .with("text", message))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("Telegram completion notification sent. chatId={}", chatId);
        } catch (Exception ex) {
            log.error("Failed to send Telegram completion notification. chatId={}", chatId, ex);
        }
    }
}
