package com.agentic.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 非同步任務執行緒池設定。
 *
 * <p>接收端不可等待 JMS 或外部 API I/O，因此所有派發工作都交由此執行緒池處理。</p>
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
}
