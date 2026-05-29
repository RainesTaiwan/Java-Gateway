package com.agentic.gateway.telegram;

import com.agentic.gateway.config.TelegramBotProperties;
import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.dto.TaskSource;
import com.agentic.gateway.jms.DevTaskPublisher;
import com.agentic.gateway.task.CommandParser;
import com.agentic.gateway.task.ParsedCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Telegram Long Polling 接收端。
 *
 * <p>此類別的責任只有三件事：驗證使用者、解析文字指令、排程推送任務。
 * 不在 Bot callback 中執行任何實際開發工作。</p>
 */
@Slf4j
@Component
public class TelegramCommandBot extends TelegramLongPollingBot {

    private static final String ACCEPTED_MESSAGE = "任務已進入排程";

    private final TelegramBotProperties telegramBotProperties;
    private final CommandParser commandParser;
    private final DevTaskPublisher devTaskPublisher;

    public TelegramCommandBot(
            TelegramBotProperties telegramBotProperties,
            CommandParser commandParser,
            DevTaskPublisher devTaskPublisher
    ) {
        super(telegramBotProperties.token());
        this.telegramBotProperties = telegramBotProperties;
        this.commandParser = commandParser;
        this.devTaskPublisher = devTaskPublisher;
    }

    @Override
    public String getBotUsername() {
        return telegramBotProperties.username();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Message message = update.getMessage();
        if (!isAllowedUser(message)) {
            log.warn("Rejected Telegram message from unauthorized user. telegramUserId={}",
                    message.getFrom() == null ? null : message.getFrom().getId());
            return;
        }

        ParsedCommand parsedCommand = commandParser.parse(message.getText());
        if (parsedCommand.payload().isBlank()) {
            log.warn("Ignored empty Telegram command. telegramUserId={}", message.getFrom().getId());
            return;
        }

        DevTask task = DevTask.create(TaskSource.TELEGRAM, parsedCommand.targetEngine(), parsedCommand.payload());
        devTaskPublisher.publishAsync(task)
                .thenRun(() -> sendAcceptedMessageAsync(message.getChatId()))
                .exceptionally(ex -> {
                    log.error("Failed to publish Telegram DevTask. taskId={}", task.taskId(), ex);
                    return null;
                });
    }

    /**
     * 嚴格比對 Telegram 使用者 ID；任何不符合者都不可進入佇列。
     */
    private boolean isAllowedUser(Message message) {
        if (message.getFrom() == null || message.getFrom().getId() == null) {
            return false;
        }
        return message.getFrom().getId().equals(telegramBotProperties.allowedUserId());
    }

    /**
     * 使用 TelegramBots 非同步 API 回覆，避免阻塞 Long Polling callback。
     */
    private void sendAcceptedMessageAsync(Long chatId) {
        SendMessage response = SendMessage.builder()
                .chatId(chatId)
                .text(ACCEPTED_MESSAGE)
                .build();

        try {
            executeAsync(response);
        } catch (TelegramApiException ex) {
            log.error("Failed to send Telegram acknowledgement. chatId={}", chatId, ex);
        }
    }
}
