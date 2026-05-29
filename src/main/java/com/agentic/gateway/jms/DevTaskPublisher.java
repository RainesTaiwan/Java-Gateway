package com.agentic.gateway.jms;

import com.agentic.gateway.config.GatewayProperties;
import com.agentic.gateway.dto.DevTask;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 將標準化任務發佈到 ActiveMQ。
 *
 * <p>此服務只處理序列化與訊息派發，呼叫端透過 {@link #publishAsync(DevTask)}
 * 取得非同步結果，不需要在 HTTP 或 Telegram callback 執行緒中等待 broker I/O。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevTaskPublisher {

    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayProperties gatewayProperties;

    @Async("gatewayTaskExecutor")
    public CompletableFuture<Void> publishAsync(DevTask task) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(task);
            jmsTemplate.convertAndSend(gatewayProperties.jms().commandQueue(), jsonPayload);
            log.info("DevTask published to queue. taskId={}, source={}, targetEngine={}",
                    task.taskId(), task.source(), task.targetEngine());
            return CompletableFuture.completedFuture(null);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("DevTask JSON 序列化失敗", ex);
        }
    }
}
