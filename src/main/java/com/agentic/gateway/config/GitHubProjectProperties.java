package com.agentic.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub Projects v2 GraphQL 同步設定。
 *
 * <p>GitHub 的 {@code updateProjectV2ItemFieldValue} mutation 更新單選欄位時，
 * 需要同時提供 Project node ID、Item node ID、Status 欄位 ID，以及該欄位底下的
 * single select option ID。這些 ID 都不是人類可讀名稱，需先用 GitHub GraphQL 查出後
 * 透過環境變數注入，避免把 PAT 或看板內部 ID 寫死在程式中。</p>
 */
@ConfigurationProperties(prefix = "github.project")
public record GitHubProjectProperties(
        String token,
        String projectId,
        StatusField statusField,
        Api api
) {

    public record StatusField(
            String fieldId,
            String inProgressOptionId,
            String verifyingOptionId,
            String retryingOptionId,
            String doneOptionId,
            String failedOptionId
    ) {
    }

    public record Api(
            String graphqlUrl,
            Integer timeoutSeconds
    ) {
    }
}
