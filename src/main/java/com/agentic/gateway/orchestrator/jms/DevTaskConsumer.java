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
 * <p>採用 DB-driven 調度：訊息只負責把任務冪等寫入資料庫的 QUEUED 狀態。
 * 寫入成功後立即 ACK，實際執行交由 {@link WorkflowOrchestrator#processTaskAsync(String)}
 * 以及啟動恢復器從資料庫重新派發。</p>
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
            DevTaskRecordService.EnqueueResult enqueueResult = devTaskRecordService.enqueueIfAbsent(task);

            log.info("DevTask enqueue checked. taskId={}, messageId={}, created={}, existingState={}, shouldDispatch={}",
                    enqueueResult.taskId(), message.getJMSMessageID(), enqueueResult.created(),
                    enqueueResult.existingState(), enqueueResult.shouldDispatch());

            message.acknowledge();
            log.info("DevTask message acknowledged after DB enqueue decision. taskId={}, messageId={}",
                    enqueueResult.taskId(), message.getJMSMessageID());

            if (enqueueResult.shouldDispatch()) {
                workflowOrchestrator.processTaskAsync(enqueueResult.taskId());
                return;
            }

            log.info("Duplicate DevTask ignored because it is already past QUEUED. taskId={}, state={}",
                    enqueueResult.taskId(), enqueueResult.existingState());
        } catch (Exception ex) {
            log.error("Failed to consume DevTask message. messageId={}", message.getJMSMessageID(), ex);
            if (ex instanceof JMSException jmsException) {
                throw jmsException;
            }
            throw new IllegalStateException("DevTask 消費失敗，訊息尚未 acknowledge。", ex);
        }
    }
}
