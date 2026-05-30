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
 * <p>收到訊息並反序列化成功後立即手動 acknowledge，避免長時間 Agent 執行阻塞 JMS listener
 * 執行緒而觸發 ActiveMQ redelivery。實際編排改由
 * {@link WorkflowOrchestrator#processTaskAsync(DevTask)} 在獨立執行緒池處理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevTaskConsumer {

    private final ObjectMapper objectMapper;
    private final WorkflowOrchestrator workflowOrchestrator;

    @JmsListener(destination = "${app.jms.command-queue}")
    public void receive(String rawPayload, Message message) throws JMSException {
        log.info("Received raw DevTask message from ActiveMQ. messageId={}", message.getJMSMessageID());

        try {
            DevTask task = objectMapper.readValue(rawPayload, DevTask.class);
            message.acknowledge();
            log.info("DevTask message acknowledged before async dispatch. taskId={}, messageId={}",
                    task.taskId(), message.getJMSMessageID());
            workflowOrchestrator.processTaskAsync(task);
        } catch (Exception ex) {
            log.error("Failed to consume DevTask message. messageId={}", message.getJMSMessageID(), ex);
            if (ex instanceof JMSException jmsException) {
                throw jmsException;
            }
            throw new IllegalStateException("DevTask 消費失敗，訊息尚未 acknowledge。", ex);
        }
    }
}
