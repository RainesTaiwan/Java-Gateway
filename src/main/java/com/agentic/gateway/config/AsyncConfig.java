package com.agentic.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 非同步任務執行緒池設定。
 *
 * <p>接收端不可等待 JMS 或外部 API I/O，因此 JMS 派發與 Telegram 回覆
 * 使用不同執行緒池，避免彼此阻塞。</p>
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "gatewayTaskExecutor")
    public Executor gatewayTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("gateway-task-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(500);
        executor.initialize();
        return executor;
    }

    @Bean(name = "telegramReplyExecutor")
    public Executor telegramReplyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("telegram-reply-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(200);
        executor.initialize();
        return executor;
    }

    @Bean(name = "orchestratorTaskExecutor")
    public Executor orchestratorTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("orchestrator-task-");
        // 共用 /app/workspace 目錄，同一時間只允許一個 Agent 任務執行；其餘任務在佇列中等待。
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }
}
