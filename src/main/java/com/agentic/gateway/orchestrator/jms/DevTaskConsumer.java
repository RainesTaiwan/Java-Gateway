package com.agentic.gateway.orchestrator.jms;

import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.WorkflowOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * ActiveMQ 任務消費端。
 *
 * <p>此 consumer 監聽 Gateway 已經約定好的 {@code dev.command.queue}，
 * 反序列化為相同的 {@link DevTask} 結構後交給 {@link WorkflowOrchestrator}。
 * JMS listener container 會透過 {@code spring.jms.listener.acknowledge-mode=client}
 * 設定為 CLIENT_ACKNOWLEDGE，因此只有在任務確定交給 Orchestrator 處理後才手動 ack。
 * 若反序列化或 Orchestrator 初始化失敗，例外會往外拋，訊息不會被 acknowledge。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevTaskConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @JmsListener(destination = "dev.command.queue")
    public void receive(String rawPayload, Message message) throws JMSException {
        log.info("Received raw DevTask message from ActiveMQ. messageId={}", message.getJMSMessageID());

        try {
            DevTask task = objectMapper.readValue(rawPayload, DevTask.class);
            workflowOrchestrator.processTask(task);
            message.acknowledge();
            log.info("DevTask message acknowledged. taskId={}, messageId={}",
                    task.taskId(), message.getJMSMessageID());
        } catch (Exception ex) {
            log.error("Failed to consume DevTask message. messageId={}", message.getJMSMessageID(), ex);
            if (ex instanceof JMSException jmsException) {
                throw jmsException;
            }
            throw new IllegalStateException("DevTask 消費失敗，訊息尚未 acknowledge。", ex);
        }
    }
}
