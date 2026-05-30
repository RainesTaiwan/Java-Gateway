package com.agentic.gateway.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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

        GitHubPayloadExtractor.ExtractionResult result = extractor.extract(payload);

        assertThat(result.payload()).isPresent();
        assertThat(result.nonActionableAction()).isFalse();
        assertThat(result.payload().get().title()).isEqualTo("Implement orchestrator");
        assertThat(result.payload().get().projectItemId()).isEqualTo("PVTI_lADOExampleProjectItem");
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

        GitHubPayloadExtractor.ExtractionResult result = extractor.extract(payload);

        assertThat(result.payload()).isPresent();
        assertThat(result.nonActionableAction()).isFalse();
        assertThat(result.payload().get().title()).isEqualTo("Fix webhook sync");
        assertThat(result.payload().get().projectItemId()).isEqualTo("PVTI_lADOIssueProjectItem");
    }

    @Test
    void ignoreClassicProjectCardIdForProjectsV2Sync() {
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

        GitHubPayloadExtractor.ExtractionResult result = extractor.extract(payload);

        assertThat(result.payload()).isPresent();
        assertThat(result.nonActionableAction()).isFalse();
        assertThat(result.payload().get().projectItemId()).isNull();
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

        GitHubPayloadExtractor.ExtractionResult result = extractor.extract(payload);

        assertThat(result.payload()).isEmpty();
        assertThat(result.nonActionableAction()).isTrue();
    }

    @Test
    void allowProjectsV2ItemMovedToTodoColumn() {
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

        GitHubPayloadExtractor.ExtractionResult result = extractor.extract(payload);

        assertThat(result.payload()).isPresent();
        assertThat(result.nonActionableAction()).isFalse();
        assertThat(result.payload().get().title()).isEqualTo("Move to Todo");
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

        GitHubPayloadExtractor.ExtractionResult result = extractor.extract(payload);

        assertThat(result.payload()).isEmpty();
        assertThat(result.nonActionableAction()).isTrue();
    }
}
