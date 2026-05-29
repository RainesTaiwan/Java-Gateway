package com.agentic.gateway;

import com.agentic.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Java Gateway 啟動入口。
 *
 * <p>本模組只負責接收外部事件、完成安全驗證，並將標準化任務推送至 ActiveMQ。
 * 不在此模組內執行 AI 任務或任何長時間工作。</p>
 */
@EnableAsync
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class JavaGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaGatewayApplication.class, args);
    }
}
