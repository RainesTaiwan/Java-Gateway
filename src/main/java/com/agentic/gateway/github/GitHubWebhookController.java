package com.agentic.gateway.github;

import com.agentic.gateway.dto.DevTask;
import com.agentic.gateway.dto.TargetEngine;
import com.agentic.gateway.dto.TaskSource;
import com.agentic.gateway.jms.DevTaskPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub Webhook REST 接收端。
 *
 * <p>安全驗證失敗時立即回傳 401，且不解析 payload、不建立任務、不進入佇列。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/webhooks")
public class GitHubWebhookController {

    private final GitHubSignatureVerifier signatureVerifier;
    private final GitHubPayloadExtractor payloadExtractor;
    private final DevTaskPublisher devTaskPublisher;

    @PostMapping("/github")
    public ResponseEntity<Void> receiveGitHubWebhook(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventName,
            @RequestBody String rawPayload
    ) {
        if (!signatureVerifier.isValid(signature, rawPayload)) {
            log.warn("Rejected GitHub webhook because signature verification failed. event={}", eventName);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        GitHubPayloadExtractor.ExtractionResult extractionResult = payloadExtractor.extract(rawPayload);
        extractionResult.payload().ifPresentOrElse(
                this::publishGitHubTask,
                () -> {
                    if (extractionResult.nonActionableAction()) {
                        return;
                    }
                    log.info("GitHub webhook verified but no supported task payload was found. event={}", eventName);
                }
        );

        if (extractionResult.payload().isPresent()) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok().build();
    }

    private void publishGitHubTask(GitHubTaskPayload payload) {
        DevTask task = DevTask.create(
                TaskSource.GITHUB,
                TargetEngine.DEFAULT,
                payload.toTaskText(),
                payload.projectItemId()
        );
        devTaskPublisher.publishAsync(task)
                .exceptionally(ex -> {
                    log.error("Failed to publish GitHub DevTask. taskId={}", task.taskId(), ex);
                    return null;
                });
    }
}
