package com.agentic.gateway.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 從 GitHub Webhook JSON 中擷取可派發的任務內容。
 *
 * <p>GitHub Issues payload 會包含 issue 節點；GitHub Projects v2 payload
 * 常見於 projects_v2_item.content_node。此類別集中處理 payload 結構差異，
 * Controller 不需要知道各事件格式細節。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubPayloadExtractor {

    private final ObjectMapper objectMapper;

    public Optional<GitHubTaskPayload> extract(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            return extractIssue(root)
                    .or(() -> extractProjectsV2Issue(root))
                    .or(() -> extractClassicProjectCard(root));
        } catch (Exception ex) {
            log.warn("Ignored malformed GitHub webhook payload.", ex);
            return Optional.empty();
        }
    }

    private Optional<GitHubTaskPayload> extractIssue(JsonNode root) {
        JsonNode issue = root.path("issue");
        if (issue.isMissingNode() || issue.isNull()) {
            return Optional.empty();
        }

        String title = issue.path("title").asText("");
        String url = firstNonBlank(issue.path("html_url").asText(""), issue.path("url").asText(""));
        return toPayload(title, url);
    }

    private Optional<GitHubTaskPayload> extractProjectsV2Issue(JsonNode root) {
        JsonNode contentNode = root.path("projects_v2_item").path("content_node");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            return Optional.empty();
        }

        String title = contentNode.path("title").asText("");
        String url = firstNonBlank(contentNode.path("url").asText(""), contentNode.path("html_url").asText(""));
        return toPayload(title, url);
    }

    private Optional<GitHubTaskPayload> extractClassicProjectCard(JsonNode root) {
        JsonNode projectCard = root.path("project_card");
        if (projectCard.isMissingNode() || projectCard.isNull()) {
            return Optional.empty();
        }

        String title = firstNonBlank(projectCard.path("note").asText(""), projectCard.path("name").asText(""));
        String url = firstNonBlank(projectCard.path("content_url").asText(""), projectCard.path("url").asText(""));
        return toPayload(title, url);
    }

    private Optional<GitHubTaskPayload> toPayload(String title, String url) {
        if (title.isBlank() && url.isBlank()) {
            return Optional.empty();
        }

        String normalizedTitle = title.isBlank() ? "(無標題)" : title;
        String normalizedUrl = url.isBlank() ? "(無 URL)" : url;
        return Optional.of(new GitHubTaskPayload(normalizedTitle, normalizedUrl));
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
