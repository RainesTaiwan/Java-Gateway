package com.agentic.gateway.orchestrator.github;

import com.agentic.gateway.config.GitHubProjectProperties;
import com.agentic.gateway.orchestrator.TaskState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * GitHub Projects v2 看板同步服務。
 *
 * <p>此服務封裝 GitHub GraphQL v4 的 {@code updateProjectV2ItemFieldValue} mutation，
 * 專門負責把 Orchestrator 的 {@link TaskState} 映射成 Project v2 Status 單選欄位。
 * GitHub 官方規範要求更新 single select 欄位時提供：</p>
 *
 * <ul>
 *     <li>{@code projectId}: Project v2 node ID</li>
 *     <li>{@code itemId}: Project item node ID</li>
 *     <li>{@code fieldId}: Status 欄位 ID</li>
 *     <li>{@code singleSelectOptionId}: 目標狀態 option ID</li>
 * </ul>
 *
 * <p>所有 PAT 與 GitHub node ID 均由環境變數注入，不可寫死在程式碼中。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubProjectSyncService {

    private static final String UPDATE_PROJECT_ITEM_STATUS_MUTATION = """
            mutation UpdateProjectItemStatus(
              $projectId: ID!,
              $itemId: ID!,
              $fieldId: ID!,
              $optionId: String!
            ) {
              updateProjectV2ItemFieldValue(input: {
                projectId: $projectId,
                itemId: $itemId,
                fieldId: $fieldId,
                value: { singleSelectOptionId: $optionId }
              }) {
                projectV2Item {
                  id
                }
              }
            }
            """;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final GitHubProjectProperties gitHubProjectProperties;

    /**
     * 將指定 GitHub Projects v2 item 的 Status 欄位同步成 Orchestrator 狀態。
     *
     * <p>所有狀態都會映射到 GitHub Projects v2 Status 單選欄位；
     * 若某個 option ID 未設定，會在設定驗證階段明確失敗，避免靜默漏更新看板。</p>
     */
    public void updateCardStatus(String itemId, TaskState state) {
        Optional<String> optionId = resolveStatusOptionId(state);
        if (optionId.isEmpty()) {
            log.info("No GitHub Project status mapping for state. itemId={}, state={}", itemId, state);
            return;
        }

        validateRequiredConfig(itemId, optionId.get());

        String requestBody = buildGraphQlRequestBody(itemId, optionId.get());
        Duration timeout = Duration.ofSeconds(resolveTimeoutSeconds());

        JsonNode response = webClientBuilder.build()
                .post()
                .uri(gitHubProjectProperties.api().graphqlUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + gitHubProjectProperties.token())
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(timeout);

        if (response == null) {
            throw new IllegalStateException("GitHub GraphQL 回應為空。");
        }

        JsonNode errors = response.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("GitHub GraphQL 更新看板狀態失敗: " + errors);
        }

        String updatedItemId = response.path("data")
                .path("updateProjectV2ItemFieldValue")
                .path("projectV2Item")
                .path("id")
                .asText("");
        log.info("GitHub Project item status updated. itemId={}, updatedItemId={}, state={}",
                itemId, updatedItemId, state);
    }

    private String buildGraphQlRequestBody(String itemId, String optionId) {
        GraphQlRequest request = new GraphQlRequest(
                UPDATE_PROJECT_ITEM_STATUS_MUTATION,
                Map.of(
                        "projectId", gitHubProjectProperties.projectId(),
                        "itemId", itemId,
                        "fieldId", gitHubProjectProperties.statusField().fieldId(),
                        "optionId", optionId
                )
        );

        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("GitHub GraphQL request body 序列化失敗。", ex);
        }
    }

    private Optional<String> resolveStatusOptionId(TaskState state) {
        GitHubProjectProperties.StatusField statusField = gitHubProjectProperties.statusField();
        return switch (state) {
            case RECEIVED, IN_PROGRESS, RUNNING -> Optional.ofNullable(statusField.inProgressOptionId());
            case VERIFYING -> Optional.ofNullable(statusField.verifyingOptionId());
            case RETRYING -> Optional.ofNullable(statusField.retryingOptionId());
            case SUCCESS -> Optional.ofNullable(statusField.doneOptionId());
            case FAILED -> Optional.ofNullable(statusField.failedOptionId());
        };
    }

    private void validateRequiredConfig(String itemId, String optionId) {
        requireText(gitHubProjectProperties.token(), "GITHUB_TOKEN");
        requireText(gitHubProjectProperties.projectId(), "PROJECT_ID");
        requireText(gitHubProjectProperties.statusField().fieldId(), "PROJECT_STATUS_FIELD_ID");
        requireText(itemId, "GitHub Project itemId");
        requireText(optionId, "GitHub Project status option ID");
        requireText(gitHubProjectProperties.api().graphqlUrl(), "GitHub GraphQL URL");
    }

    private int resolveTimeoutSeconds() {
        Integer configuredTimeout = gitHubProjectProperties.api().timeoutSeconds();
        if (configuredTimeout == null || configuredTimeout <= 0) {
            return 10;
        }
        return configuredTimeout;
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 未設定，無法同步 GitHub Projects v2 看板。");
        }
    }

    /**
     * GitHub GraphQL 標準請求格式。
     *
     * <p>使用 Jackson 產生 JSON body，避免手刻 JSON 時因引號或跳脫字元造成 mutation 格式錯誤。</p>
     */
    private record GraphQlRequest(String query, Map<String, String> variables) {
    }
}
