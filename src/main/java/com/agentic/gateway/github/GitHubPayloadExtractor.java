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

    private static final String TODO_COLUMN_NAME = "Todo";

    private final ObjectMapper objectMapper;

    public ExtractionResult extract(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            if (!isActionableEvent(root)) {
                log.info("Webhook ignored: Action not actionable.");
                return ExtractionResult.nonActionable();
            }

            Optional<GitHubTaskPayload> payload = extractProjectsV2Issue(root)
                    .or(() -> extractIssue(root))
                    .or(() -> extractClassicProjectCard(root));
            return payload.map(ExtractionResult::found).orElseGet(ExtractionResult::notFound);
        } catch (Exception ex) {
            log.warn("Ignored malformed GitHub webhook payload.", ex);
            return ExtractionResult.notFound();
        }
    }

    private boolean isActionableEvent(JsonNode root) {
        if (!root.path("projects_v2_item").isMissingNode() && !root.path("projects_v2_item").isNull()) {
            return isProjectsV2Actionable(root);
        }
        if (!root.path("issue").isMissingNode() && !root.path("issue").isNull()) {
            return isIssueActionable(root.path("action").asText(""));
        }
        if (!root.path("project_card").isMissingNode() && !root.path("project_card").isNull()) {
            return isClassicProjectCardActionable(root.path("action").asText(""));
        }
        return false;
    }

    private boolean isIssueActionable(String action) {
        return switch (action) {
            case "opened", "labeled", "unlabeled" -> true;
            default -> false;
        };
    }

    private boolean isProjectsV2Actionable(JsonNode root) {
        String action = root.path("action").asText("");
        return switch (action) {
            case "created" -> true;
            case "edited" -> isMovedToTodoColumn(root);
            default -> false;
        };
    }

    private boolean isClassicProjectCardActionable(String action) {
        return switch (action) {
            case "created", "moved" -> true;
            default -> false;
        };
    }

    private boolean isMovedToTodoColumn(JsonNode root) {
        JsonNode toNode = root.path("changes").path("field_value").path("to");
        if (toNode.isMissingNode() || toNode.isNull()) {
            return false;
        }

        if (isTodoColumnName(toNode.path("name").asText(""))) {
            return true;
        }

        JsonNode singleSelectOption = toNode.path("single_select_option");
        if (!singleSelectOption.isMissingNode() && !singleSelectOption.isNull()) {
            return isTodoColumnName(singleSelectOption.path("name").asText(""));
        }

        return false;
    }

    private boolean isTodoColumnName(String name) {
        return TODO_COLUMN_NAME.equalsIgnoreCase(name.trim());
    }

    private Optional<GitHubTaskPayload> extractIssue(JsonNode root) {
        JsonNode issue = root.path("issue");
        if (issue.isMissingNode() || issue.isNull()) {
            return Optional.empty();
        }

        String title = issue.path("title").asText("");
        String url = firstNonBlank(issue.path("html_url").asText(""), issue.path("url").asText(""));
        String projectItemId = extractProjectItemId(root).orElse(null);
        return toPayload(title, url, projectItemId);
    }

    private Optional<GitHubTaskPayload> extractProjectsV2Issue(JsonNode root) {
        JsonNode projectsV2Item = root.path("projects_v2_item");
        JsonNode contentNode = projectsV2Item.path("content_node");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            return Optional.empty();
        }

        String title = contentNode.path("title").asText("");
        String url = firstNonBlank(contentNode.path("url").asText(""), contentNode.path("html_url").asText(""));
        String projectItemId = extractProjectItemId(root).orElse(null);
        return toPayload(title, url, projectItemId);
    }

    private Optional<GitHubTaskPayload> extractClassicProjectCard(JsonNode root) {
        JsonNode projectCard = root.path("project_card");
        if (projectCard.isMissingNode() || projectCard.isNull()) {
            return Optional.empty();
        }

        String title = firstNonBlank(projectCard.path("note").asText(""), projectCard.path("name").asText(""));
        String url = firstNonBlank(projectCard.path("content_url").asText(""), projectCard.path("url").asText(""));
        return toPayload(title, url, null);
    }

    private Optional<GitHubTaskPayload> toPayload(String title, String url, String projectItemId) {
        if (title.isBlank() && url.isBlank()) {
            return Optional.empty();
        }

        String normalizedTitle = title.isBlank() ? "(無標題)" : title;
        String normalizedUrl = url.isBlank() ? "(無 URL)" : url;
        return Optional.of(new GitHubTaskPayload(normalizedTitle, normalizedUrl, projectItemId));
    }

    /**
     * 從 GitHub Webhook payload 中取出 Projects v2 item 的 GraphQL node ID。
     *
     * <p>Projects v2 webhook 常見路徑是 {@code projects_v2_item.id} 或
     * {@code projects_v2_item.node_id}；部分 issue payload 可能會把 project item
     * 放在 {@code project_item} 或 {@code issue.project_items.nodes[]}。此處只接受
     * {@code PVTI_...} 這類 Projects v2 item node ID，避免把 REST 數字 ID 或 classic
     * project card ID 誤用到 GraphQL mutation。</p>
     */
    private Optional<String> extractProjectItemId(JsonNode root) {
        return firstProjectItemId(
                textAt(root, "projects_v2_item", "id"),
                textAt(root, "projects_v2_item", "node_id"),
                textAt(root, "project_item", "id"),
                textAt(root, "project_item", "node_id"),
                textAt(root, "issue", "project_item", "id"),
                textAt(root, "issue", "project_item", "node_id"),
                textAt(root, "itemId"),
                firstIssueProjectItemNodeId(root).orElse(null)
        );
    }

    private Optional<String> firstIssueProjectItemNodeId(JsonNode root) {
        JsonNode nodes = root.path("issue").path("project_items").path("nodes");
        if (!nodes.isArray()) {
            return Optional.empty();
        }

        for (JsonNode node : nodes) {
            Optional<String> projectItemId = firstProjectItemId(
                    node.path("id").asText(""),
                    node.path("node_id").asText("")
            );
            if (projectItemId.isPresent()) {
                return projectItemId;
            }
        }

        return Optional.empty();
    }

    private Optional<String> firstProjectItemId(String... candidates) {
        for (String candidate : candidates) {
            if (isProjectV2ItemNodeId(candidate)) {
                return Optional.of(candidate.trim());
            }
        }
        return Optional.empty();
    }

    private boolean isProjectV2ItemNodeId(String value) {
        return value != null && value.trim().startsWith("PVTI_");
    }

    private String textAt(JsonNode root, String... path) {
        JsonNode current = root;
        for (String segment : path) {
            current = current.path(segment);
        }
        return current.asText("");
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    public record ExtractionResult(Optional<GitHubTaskPayload> payload, boolean nonActionableAction) {

        public static ExtractionResult found(GitHubTaskPayload payload) {
            return new ExtractionResult(Optional.of(payload), false);
        }

        public static ExtractionResult nonActionable() {
            return new ExtractionResult(Optional.empty(), true);
        }

        public static ExtractionResult notFound() {
            return new ExtractionResult(Optional.empty(), false);
        }
    }
}
