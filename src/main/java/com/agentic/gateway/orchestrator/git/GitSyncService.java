package com.agentic.gateway.orchestrator.git;

import com.agentic.gateway.config.GitHubProjectProperties;
import com.agentic.gateway.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Host workspace Git 同步服務。
 *
 * <p>此服務執行系統架構圖步驟 6：由 Java Orchestrator 使用 JGit 在
 * {@code /app/workspace} 維護一份乾淨的原始碼工作區。若工作區為空，執行 clone；
 * 若工作區已是 Git repository，則執行 fetch + reset --hard origin/main + clean，
 * 確保每次 Agent 進入沙盒前都從乾淨基線開始。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitSyncService {

    private final OrchestratorProperties orchestratorProperties;
    private final GitHubProjectProperties gitHubProjectProperties;

    /**
     * 將本機 workspace 同步到指定遠端分支的乾淨狀態。
     *
     * @return 容器內 workspace path，例如 {@code /app/workspace}
     */
    public Path syncRepository() {
        Path workspace = Path.of(orchestratorProperties.workspace().containerPath());
        String repositoryUri = requireText(orchestratorProperties.git().repositoryUri(), "GIT_REPOSITORY_URI");
        String branch = normalizeBranch(orchestratorProperties.git().branch());

        try {
            Files.createDirectories(workspace);
            if (isDirectoryEmpty(workspace)) {
                cloneRepository(repositoryUri, branch, workspace);
            } else if (Files.isDirectory(workspace.resolve(".git"))) {
                resetExistingRepository(workspace, branch);
            } else {
                throw new IllegalStateException("Workspace is not empty and is not a Git repository: " + workspace);
            }
            return workspace;
        } catch (Exception ex) {
            throw new IllegalStateException("Git workspace 同步失敗: " + workspace, ex);
        }
    }

    private void cloneRepository(String repositoryUri, String branch, Path workspace) throws Exception {
        log.info("Cloning repository into workspace. repositoryUri={}, branch={}, workspace={}",
                repositoryUri, branch, workspace);
        try (Git ignored = Git.cloneRepository()
                .setURI(repositoryUri)
                .setBranch(branch)
                .setDirectory(workspace.toFile())
                .setCredentialsProvider(resolveCredentialsProvider())
                .call()) {
            log.info("Repository cloned successfully. workspace={}", workspace);
        }
    }

    private void resetExistingRepository(Path workspace, String branch) throws Exception {
        log.info("Resetting existing repository to clean origin baseline. branch={}, workspace={}", branch, workspace);
        try (Git git = Git.open(workspace.toFile())) {
            git.fetch()
                    .setRemote("origin")
                    .setCredentialsProvider(resolveCredentialsProvider())
                    .call();

            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("origin/" + branch)
                    .call();

            git.clean()
                    .setCleanDirectories(true)
                    .setForce(true)
                    .setIgnore(false)
                    .call();

            log.info("Repository reset completed. workspace={}, ref=origin/{}", workspace, branch);
        }
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private CredentialsProvider resolveCredentialsProvider() {
        String token = gitHubProjectProperties.token();
        if (token == null || token.isBlank()) {
            return CredentialsProvider.getDefault();
        }
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    private String normalizeBranch(String configuredBranch) {
        if (configuredBranch == null || configuredBranch.isBlank()) {
            return "main";
        }
        return configuredBranch.trim();
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 未設定，無法同步 Git repository。");
        }
        return value.trim();
    }
}
