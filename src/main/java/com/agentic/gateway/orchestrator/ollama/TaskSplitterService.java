package com.agentic.gateway.orchestrator.ollama;

import com.agentic.gateway.config.OrchestratorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 地端 Ollama 任務拆解服務。
 *
 * <p>在任務進入 Cursor/Aider 沙盒前，先讓地端模型扮演技術主管，將使用者原始需求
 * 拆成 3 到 5 個更小、更精確的實作步驟，降低大型重構一次丟給 Agent 時只產出規劃
 * 或偏離目標的機率。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSplitterService {

    private static final int MAX_SPEC_CHARS = 10_000;

    private final WebClient.Builder webClientBuilder;
    private final OrchestratorProperties orchestratorProperties;

    /**
     * 將原始使用者 spec 拆解為細緻子任務計畫。
     *
     * <p>Ollama 是強化能力，不應讓任務整體因地端模型暫時不可用而卡死；失敗時回傳
     * 原始 spec，讓既有 Agent 流程仍可繼續執行。</p>
     */
    public String splitTask(String originalSpec) {
        String normalizedSpec = normalizeSpec(originalSpec);
        if (normalizedSpec.isBlank()) {
            return "";
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
                            "prompt", buildPrompt(normalizedSpec),
                            "stream", false
                    ))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(timeout);

            String plan = response == null ? "" : response.path("response").asText("");
            if (plan == null || plan.isBlank()) {
                log.warn("Ollama task splitter returned empty plan; falling back to original spec.");
                return normalizedSpec;
            }
            return sanitizePlan(plan);
        } catch (Exception ex) {
            log.warn("Ollama task splitting failed; falling back to original spec.", ex);
            return normalizedSpec;
        }
    }

    private String buildPrompt(String normalizedSpec) {
        return """
                你是一位技術主管與資深軟體架構師，正在把 Telegram 使用者交付給自動開發 Agent 的需求拆小。

                目標：
                1. 先判斷使用者真正要修改的工程範圍。
                2. 將大型任務拆成 3 到 5 個可直接實作的子任務。
                3. 每個子任務都要包含明確檔案/模組方向、預期變更、驗收標準。
                4. 指示下游 Agent 必須直接修改 repository 內檔案，不要只輸出規劃或要求確認。

                輸出格式：
                - 只輸出「拆解後執行規格」，不要寒暄。
                - 使用 3 到 5 個編號步驟。
                - 最後加上「交付要求」：必須產生 git diff、避免無關重構、完成後摘要變更。

                使用者原始 Spec：
                %s
                """.formatted(normalizedSpec);
    }

    private String normalizeSpec(String originalSpec) {
        if (originalSpec == null) {
            return "";
        }
        String trimmed = originalSpec.trim();
        if (trimmed.length() <= MAX_SPEC_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_SPEC_CHARS);
    }

    private String sanitizePlan(String plan) {
        return plan.replace("\r\n", "\n").replace('\r', '\n').trim();
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
