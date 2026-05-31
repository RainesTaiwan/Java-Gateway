package com.agentic.gateway.telegram;

import com.agentic.gateway.config.TelegramBotProperties;
import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.dto.TaskSource;
import com.agentic.gateway.jms.DevTaskPublisher;
import com.agentic.gateway.orchestrator.persistence.DevTaskRecord;
import com.agentic.gateway.orchestrator.persistence.DevTaskRecordService;
import com.agentic.gateway.task.CommandParser;
import com.agentic.gateway.task.ParsedCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Telegram Long Polling 接收端。
 *
 * <p>此類別的責任只有三件事：驗證使用者、解析文字指令、排程推送任務。
 * 不在 Bot callback 中執行任何實際開發工作。</p>
 */
@Slf4j
@Component
public class TelegramCommandBot extends TelegramLongPollingBot {

    private static final String ACCEPTED_MESSAGE = "已收到任務並排入佇列，尚未執行完成。成功或失敗會另行通知。";
    private static final String STATUS_COMMAND = "/status";
    private static final int STATUS_PAYLOAD_PREVIEW_LIMIT = 80;

    private final TelegramBotProperties telegramBotProperties;
    private final CommandParser commandParser;
    private final DevTaskPublisher devTaskPublisher;
    private final DevTaskRecordService devTaskRecordService;
    private final Executor telegramReplyExecutor;

    public TelegramCommandBot(
            TelegramBotProperties telegramBotProperties,
            CommandParser commandParser,
            DevTaskPublisher devTaskPublisher,
            DevTaskRecordService devTaskRecordService,
            @Qualifier("telegramReplyExecutor") Executor telegramReplyExecutor
    ) {
        super(telegramBotProperties.token());
        this.telegramBotProperties = telegramBotProperties;
        this.commandParser = commandParser;
        this.devTaskPublisher = devTaskPublisher;
        this.devTaskRecordService = devTaskRecordService;
        this.telegramReplyExecutor = telegramReplyExecutor;
    }

    @Override
    public String getBotUsername() {
        return telegramBotProperties.username();
    }

    public Long getAllowedUserId() {
        return telegramBotProperties.allowedUserId();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Message message = update.getMessage();
        log.info("Received Telegram text message. telegramUserId={}, chatId={}",
                message.getFrom() == null ? null : message.getFrom().getId(),
                message.getChatId());
        if (!isAllowedUser(message)) {
            User from = message.getFrom();
            log.warn("Rejected Telegram message from unauthorized user. telegramUserId={}, firstName={}, lastName={}, userName={}",
                    from == null ? null : from.getId(),
                    from == null ? null : from.getFirstName(),
                    from == null ? null : from.getLastName(),
                    from == null ? null : from.getUserName());
            return;
        }

        if (isStatusCommand(message.getText())) {
            handleStatusCommand(message);
            return;
        }

        ParsedCommand parsedCommand = commandParser.parse(message.getText());
        if (parsedCommand.payload().isBlank()) {
            log.warn("Ignored empty Telegram command. telegramUserId={}", message.getFrom().getId());
            return;
        }

        DevTask task = DevTask.create(
                TaskSource.TELEGRAM,
                parsedCommand.targetEngine(),
                parsedCommand.payload(),
                null,
                message.getChatId().toString()
        );
        log.info("Scheduling Telegram DevTask. taskId={}, targetEngine={}",
                task.taskId(), task.targetEngine());
        devTaskPublisher.publishAsync(task)
                .thenRunAsync(() -> sendMessageAsync(message.getChatId(), ACCEPTED_MESSAGE), telegramReplyExecutor)
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

    private boolean isStatusCommand(String text) {
        return text != null && STATUS_COMMAND.equalsIgnoreCase(text.trim());
    }

    private void handleStatusCommand(Message message) {
        try {
            List<DevTaskRecord> activeTasks = devTaskRecordService.getActiveTasks();
            sendMessageAsync(message.getChatId(), buildStatusMessage(activeTasks));
        } catch (Exception ex) {
            log.error("Failed to query active DevTask status. chatId={}", message.getChatId(), ex);
            sendMessageAsync(message.getChatId(), "查詢任務狀態失敗，請查看 Gateway log。");
        }
    }

    private String buildStatusMessage(List<DevTaskRecord> activeTasks) {
        if (activeTasks.isEmpty()) {
            return "目前沒有進行中的任務。";
        }

        StringBuilder report = new StringBuilder("進行中任務：")
                .append(activeTasks.size())
                .append(" 筆");
        for (DevTaskRecord task : activeTasks) {
            report.append("\n\n")
                    .append("• ")
                    .append(shortTaskId(task.getTaskId()))
                    .append(" [")
                    .append(task.getCurrentState())
                    .append("]")
                    .append("\n來源: ")
                    .append(task.getSource())
                    .append("\n更新: ")
                    .append(task.getUpdatedAt())
                    .append("\n內容: ")
                    .append(truncatePayload(task.getPayload()));
        }
        return report.toString();
    }

    private String shortTaskId(String taskId) {
        if (taskId == null || taskId.length() <= 8) {
            return taskId == null ? "(unknown)" : taskId;
        }
        return taskId.substring(0, 8);
    }

    private String truncatePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return "(empty)";
        }
        String normalized = payload.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= STATUS_PAYLOAD_PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, STATUS_PAYLOAD_PREVIEW_LIMIT) + "...";
    }

    /**
     * 使用 TelegramBots 非同步 API 回覆，避免阻塞 Long Polling callback。
     */
    private void sendMessageAsync(Long chatId, String text) {
        SendMessage response = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();

        try {
            executeAsync(response);
        } catch (TelegramApiException | RuntimeException ex) {
            log.error("Failed to send Telegram acknowledgement asynchronously. chatId={}", chatId, ex);
        }
    }
}
