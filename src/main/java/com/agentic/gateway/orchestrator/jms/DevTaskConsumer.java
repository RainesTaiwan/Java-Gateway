package com.agentic.gateway.orchestrator.jms;

import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.orchestrator.WorkflowOrchestrator;
import com.agentic.gateway.orchestrator.persistence.DevTaskRecordService;
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
 * <p>採用「先落表、再簽收、後非同步執行」模式：資料庫寫入成功後才 acknowledge，
 * 避免 JVM 崩潰時因過早 ACK 造成任務靜默遺失。編排由
 * {@link WorkflowOrchestrator#processTaskAsync(String)} 在獨立執行緒池處理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevTaskConsumer {

    private final ObjectMapper objectMapper;
    private final DevTaskRecordService devTaskRecordService;
    private final WorkflowOrchestrator workflowOrchestrator;

    @JmsListener(destination = "${app.jms.command-queue}")
    public void receive(String rawPayload, Message message) throws JMSException {
        log.info("Received raw DevTask message from ActiveMQ. messageId={}", message.getJMSMessageID());

        try {
            DevTask task = objectMapper.readValue(rawPayload, DevTask.class);

            // Step A: 落表
            devTaskRecordService.persistReceived(task);
            log.info("DevTask persisted before acknowledge. taskId={}, messageId={}",
                    task.taskId(), message.getJMSMessageID());

            // Step B: 簽收
            message.acknowledge();
            log.info("DevTask message acknowledged after persistence. taskId={}, messageId={}",
                    task.taskId(), message.getJMSMessageID());

            // Step C: 非同步調度
            workflowOrchestrator.processTaskAsync(task.taskId().toString());
        } catch (Exception ex) {
            log.error("Failed to consume DevTask message. messageId={}", message.getJMSMessageID(), ex);
            if (ex instanceof JMSException jmsException) {
                throw jmsException;
            }
            throw new IllegalStateException("DevTask 消費失敗，訊息尚未 acknowledge。", ex);
        }
    }
}
