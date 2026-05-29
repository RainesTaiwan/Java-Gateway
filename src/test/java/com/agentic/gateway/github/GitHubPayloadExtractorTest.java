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
                  "projects_v2_item": {
                    "id": "PVTI_lADOExampleProjectItem",
                    "content_node": {
                      "title": "Implement orchestrator",
                      "url": "https://github.com/RainesTaiwan/Java-Gateway/issues/12"
                    }
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract(payload);

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Implement orchestrator");
        assertThat(result.get().projectItemId()).isEqualTo("PVTI_lADOExampleProjectItem");
    }

    @Test
    void extractProjectItemIdFromIssueEventProjectItemNode() {
        String payload = """
                {
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

        Optional<GitHubTaskPayload> result = extractor.extract(payload);

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Fix webhook sync");
        assertThat(result.get().projectItemId()).isEqualTo("PVTI_lADOIssueProjectItem");
    }

    @Test
    void ignoreClassicProjectCardIdForProjectsV2Sync() {
        String payload = """
                {
                  "project_card": {
                    "id": 12345,
                    "note": "Classic card",
                    "url": "https://api.github.com/projects/columns/cards/12345"
                  }
                }
                """;

        Optional<GitHubTaskPayload> result = extractor.extract(payload);

        assertThat(result).isPresent();
        assertThat(result.get().projectItemId()).isNull();
    }
}
