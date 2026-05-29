package com.agentic.gateway.orchestrator.ollama;

import com.agentic.gateway.config.OrchestratorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * 地端 Ollama log 降噪服務。
 *
 * <p>此服務用於 Karpathy Loop 的失敗重試階段：Aider / 測試流程失敗時通常會產生大量
 * Maven、Gradle 或 Docker noise。Orchestrator 先把原始 log 丟給地端小模型摘要成
 * 150 字以內的純文字，再把摘要回填到下一輪 Aider spec，讓下一次修復更聚焦。</p>
 *
 * <p>降噪是輔助能力，不是任務成功的必要條件。因此任何 Ollama 連線失敗、超時、回應格式錯誤
 * 都不會往外拋出例外，避免造成 ActiveMQ listener 崩潰或訊息重送風暴。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaNoiseReducer {

    private static final int MAX_RAW_LOG_CHARS = 12_000;
    private static final int FALLBACK_SUMMARY_CHARS = 500;

    private final WebClient.Builder webClientBuilder;
    private final OrchestratorProperties orchestratorProperties;

    /**
     * 將原始失敗 log 壓縮成短錯誤摘要。
     *
     * @param rawLog Aider container 取回的 stdout / stderr tail
     * @return 150 字以內為目標的純文字摘要；Ollama 不可用時回傳截斷 fallback
     */
    public String reduceNoise(String rawLog) {
        String normalizedLog = normalizeRawLog(rawLog);
        if (normalizedLog.isBlank()) {
            return "Aider 未提供可分析的失敗 log，請檢查容器啟動、模型設定或測試指令。";
        }

        try {
            String baseUrl = resolveBaseUrl();
            String model = resolveModel();
            Duration timeout = Duration.ofSeconds(resolveTimeoutSeconds());

            JsonNode response = webClientBuilder.build()
                    .post()
                    .uri(baseUrl + "/api/generate")
                    .bodyValue(Map.of(
                            "model", model,
                            "prompt", buildPrompt(normalizedLog),
                            "stream", false
                    ))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(timeout);

            String summary = response == null ? "" : response.path("response").asText("");
            if (summary.isBlank()) {
                log.warn("Ollama returned empty noise-reduced summary.");
                return fallbackSummary(normalizedLog);
            }

            return sanitizeSummary(summary);
        } catch (Exception ex) {
            log.warn("Ollama noise reduction failed; falling back to trimmed raw log summary.", ex);
            return fallbackSummary(normalizedLog);
        }
    }

    private String buildPrompt(String normalizedLog) {
        return """
                你是一位精明、不廢話的地端 Log 降噪工程師。
                任務：過濾 Maven/Gradle/Docker 的重複編譯軌跡，只萃取最關鍵失敗原因。
                請指出：哪個測試或檔案失敗、哪一行或哪個 Bean/Exception 是根因、下一步應修什麼。
                輸出限制：150 字以內，純文字，不要 Markdown，不要條列符號，不要寒暄。

                原始失敗 Log：
                %s
                """.formatted(normalizedLog);
    }

    private String normalizeRawLog(String rawLog) {
        if (rawLog == null) {
            return "";
        }
        String trimmed = rawLog.trim();
        if (trimmed.length() <= MAX_RAW_LOG_CHARS) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - MAX_RAW_LOG_CHARS);
    }

    private String sanitizeSummary(String summary) {
        String sanitized = summary
                .replace("```", "")
                .replace("*", "")
                .replace("#", "")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim()
                .replaceAll("\\s+", " ");

        if (sanitized.length() <= FALLBACK_SUMMARY_CHARS) {
            return sanitized;
        }
        return sanitized.substring(0, FALLBACK_SUMMARY_CHARS);
    }

    private String fallbackSummary(String normalizedLog) {
        if (normalizedLog.length() <= FALLBACK_SUMMARY_CHARS) {
            return normalizedLog;
        }
        return normalizedLog.substring(normalizedLog.length() - FALLBACK_SUMMARY_CHARS);
    }

    private String resolveBaseUrl() {
        String baseUrl = orchestratorProperties.ollama().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://ollama:11434";
        }
        return baseUrl.trim().replaceAll("/+$", "");
    }

    private String resolveModel() {
        String model = orchestratorProperties.ollama().model();
        if (model == null || model.isBlank()) {
            return "qwen2.5-coder:1.5b";
        }
        return model.trim();
    }

    private int resolveTimeoutSeconds() {
        Integer timeoutSeconds = orchestratorProperties.ollama().timeoutSeconds();
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return 20;
        }
        return timeoutSeconds;
    }
}
