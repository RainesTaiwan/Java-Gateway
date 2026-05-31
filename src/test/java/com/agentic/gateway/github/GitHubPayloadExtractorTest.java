package com.agentic.gateway.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubPayloadExtractorTest {

    private final GitHubPayloadExtractor extractor = new GitHubPayloadExtractor(new ObjectMapper());

    @Test
    void extractProjectItemIdFromProjectsV2ItemEvent() {
        String payload = """
                {
                  "action": "created",
                  "projects_v2_item": {
                    "id": "PVTI_lADOExampleProjectItem",
                    "content_node": {
                      "title": "Implement orchestrator",
                      "url": "https://github.com/RainesTaiwan/Java-Gateway/issues/12"
                    }
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract("projects_v2_item", payload);

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Implement orchestrator");
        assertThat(result.get().projectItemId()).isEqualTo("PVTI_lADOExampleProjectItem");
    }

    @Test
    void extractProjectItemIdFromIssueEventProjectItemNode() {
        String payload = """
                {
                  "action": "opened",
                  "issue": {
                    "title": "Fix webhook sync",
                    "html_url": "https://github.com/RainesTaiwan/Java-Gateway/issues/13",
                    "project_items": {
                      "nodes": [
                        { "id": "PVTI_lADOIssueProjectItem" }
                      ]
                    }
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract("issues", payload);

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Fix webhook sync");
        assertThat(result.get().projectItemId()).isEqualTo("PVTI_lADOIssueProjectItem");
    }

    @Test
    void extractReopenedIssueEvent() {
        String payload = """
                {
                  "action": "reopened",
                  "issue": {
                    "title": "Reopened issue",
                    "html_url": "https://github.com/RainesTaiwan/Java-Gateway/issues/17"
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract("issues", payload);

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Reopened issue");
    }

    @Test
    void ignoreClosedIssueEvent() {
        String payload = """
                {
                  "action": "closed",
                  "issue": {
                    "title": "Closed issue",
                    "html_url": "https://github.com/RainesTaiwan/Java-Gateway/issues/14"
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract("issues", payload);

        assertThat(result).isEmpty();
    }

    @Test
    void ignoreLabeledIssueEvent() {
        String payload = """
                {
                  "action": "labeled",
                  "issue": {
                    "title": "Labeled issue",
                    "html_url": "https://github.com/RainesTaiwan/Java-Gateway/issues/18"
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract("issues", payload);

        assertThat(result).isEmpty();
    }

    @Test
    void ignoreEditedIssueEvent() {
        String payload = """
                {
                  "action": "edited",
                  "issue": {
                    "title": "Edited issue",
                    "html_url": "https://github.com/RainesTaiwan/Java-Gateway/issues/19"
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract("issues", payload);

        assertThat(result).isEmpty();
    }

    @Test
    void ignoreProjectsV2ItemEditedEvenWhenMovedToTodoColumn() {
        String payload = """
                {
                  "action": "edited",
                  "projects_v2_item": {
                    "id": "PVTI_lADOExampleProjectItem",
                    "content_node": {
                      "title": "Move to Todo",
                      "url": "https://github.com/RainesTaiwan/Java-Gateway/issues/15"
                    }
                  },
                  "changes": {
                    "field_value": {
                      "field_name": "Status",
                      "to": {
                        "name": "Todo"
                      }
                    }
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract("projects_v2_item", payload);

        assertThat(result).isEmpty();
    }

    @Test
    void ignoreProjectsV2ItemEditedWithoutTodoMove() {
        String payload = """
                {
                  "action": "edited",
                  "projects_v2_item": {
                    "id": "PVTI_lADOExampleProjectItem",
                    "content_node": {
                      "title": "Still in progress",
                      "url": "https://github.com/RainesTaiwan/Java-Gateway/issues/16"
                    }
                  },
                  "changes": {
                    "field_value": {
                      "field_name": "Status",
                      "to": {
                        "name": "In Progress"
                      }
                    }
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract("projects_v2_item", payload);

        assertThat(result).isEmpty();
    }

    @Test
    void ignoreUnsupportedEventType() {
        String payload = """
                {
                  "action": "created",
                  "project_card": {
                    "id": 12345,
                    "note": "Classic card",
                    "url": "https://api.github.com/projects/columns/cards/12345"
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract("project_card", payload);

        assertThat(result).isEmpty();
    }
}
