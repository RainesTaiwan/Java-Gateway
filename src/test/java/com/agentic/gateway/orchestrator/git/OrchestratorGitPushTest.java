package com.agentic.gateway.orchestrator.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 手動驗證 Orchestrator 使用的 JGit commit/push 路徑（需設定 GITHUB_TOKEN）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "orchestrator.workspace.container-path=${ORCHESTRATOR_WORKSPACE_CONTAINER_PATH:${user.dir}/workspace}",
        "orchestrator.git.repository-uri=${GIT_REPOSITORY_URI:}",
        "orchestrator.git.branch=${GIT_BRANCH:master}"
})
@EnabledIfEnvironmentVariable(named = "GITHUB_TOKEN", matches = ".+")
class OrchestratorGitPushTest {

    @Autowired
    private GitSyncService gitSyncService;

    @Test
    void commitAndPushToRemote() throws Exception {
        Path workspace = gitSyncService.syncRepository();
        Path marker = workspace.resolve("orchestrator-push-test.txt");
        Files.writeString(marker, "orchestrator push test at " + Instant.now() + System.lineSeparator());

        assertThatCode(() -> gitSyncService.commitAndPush("manual-orchestrator-push-test"))
                .doesNotThrowAnyException();
    }
}
