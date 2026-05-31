package com.agentic.gateway.orchestrator.git;

/**
 * Git push 已送出但遠端回報拒絕、未嘗試或狀態不明時拋出。
 */
public class GitPushException extends RuntimeException {

    public GitPushException(String message) {
        super(message);
    }

    public GitPushException(String message, Throwable cause) {
        super(message, cause);
    }
}
