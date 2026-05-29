package com.agentic.gateway.orchestrator.sandbox;

import com.agentic.gateway.config.OrchestratorProperties;
import com.agentic.gateway.dto.DevTask;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Aider Docker 沙盒動態調度服務。
 *
 * <p>此服務執行系統架構圖步驟 7、9、10、11：透過 Docker-outside-of-Docker
 * 連到宿主機 Docker Engine，建立一次性 Aider container，將 Host workspace 掛載為
 * container 內的 {@code /app}，等待 Aider 結束並回傳 exit code。無論成功、失敗或超時，
 * {@code finally} 都會執行 {@code removeContainerCmd(...).withForce(true)}，避免留下殭屍容器。</p>
 */
@Slf4j
@Service
public class AiderSandboxService implements Closeable {

    private static final int TIMEOUT_EXIT_CODE = 124;
    private static final String AIDER_WORKDIR = "/app";

    private final OrchestratorProperties orchestratorProperties;
    private final DockerClient dockerClient;
    private final DockerHttpClient dockerHttpClient;

    public AiderSandboxService(OrchestratorProperties orchestratorProperties) {
        this.orchestratorProperties = orchestratorProperties;

        DockerClientConfig dockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(resolveDockerHost())
                .build();
        this.dockerHttpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerClientConfig.getDockerHost())
                .sslConfig(dockerClientConfig.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(resolveTimeoutSeconds() + 60L))
                .build();
        this.dockerClient = DockerClientImpl.getInstance(dockerClientConfig, dockerHttpClient);
    }

    /**
     * 啟動一次性 Aider container 並等待其完成。
     *
     * <p>超過 {@code AIDER_TIMEOUT_SECONDS} 時會主動 stop container，回傳 exit code 124。
     * 非超時但 Aider 自行失敗時，回傳實際 exit code，交由狀態機轉往 RETRYING。</p>
     */
    public AiderSandboxResult runAider(DevTask task) {
        String image = requireText(orchestratorProperties.aider().image(), "AIDER_IMAGE");
        String containerId = null;
        String containerName = "aider-sandbox-" + task.taskId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        boolean timedOut = false;

        try {
            ensureImageAvailable(image);

            CreateContainerResponse container = createContainer(task, image, containerName);
            containerId = container.getId();
            log.info("Aider sandbox container created. taskId={}, containerId={}, image={}",
                    task.taskId(), containerId, image);

            dockerClient.startContainerCmd(containerId).exec();
            log.info("Aider sandbox container started. taskId={}, containerId={}", task.taskId(), containerId);

            int exitCode = waitForExitCode(containerId, resolveTimeoutSeconds());
            timedOut = exitCode == TIMEOUT_EXIT_CODE;
            String logs = collectContainerLogs(containerId);

            log.info("Aider sandbox finished. taskId={}, containerId={}, exitCode={}, timedOut={}",
                    task.taskId(), containerId, exitCode, timedOut);
            return new AiderSandboxResult(exitCode, timedOut, logs);
        } catch (Exception ex) {
            String logs = containerId == null ? "" : safeCollectContainerLogs(containerId);
            log.error("Aider sandbox execution failed. taskId={}, containerId={}", task.taskId(), containerId, ex);
            return new AiderSandboxResult(1, false, logs);
        } finally {
            if (containerId != null) {
                removeContainerQuietly(containerId, task);
            }
        }
    }

    private CreateContainerResponse createContainer(DevTask task, String image, String containerName) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(new Bind(
                        requireText(orchestratorProperties.workspace().hostPath(), "ORCHESTRATOR_WORKSPACE_HOST_PATH"),
                        new Volume(AIDER_WORKDIR),
                        AccessMode.rw
                ))
                .withNetworkMode(requireText(orchestratorProperties.docker().networkName(), "DOCKER_NETWORK_NAME"))
                .withAutoRemove(false);

        return dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withWorkingDir(AIDER_WORKDIR)
                .withEntrypoint("sh", "-lc")
                .withCmd(buildAiderCommand(task))
                .withEnv(buildEnvironment())
                .exec();
    }

    private String buildAiderCommand(DevTask task) {
        String message = "根據以下 Spec 修改程式碼:\n" + (task.payload() == null ? "" : task.payload());
        return "cd " + shellQuote(AIDER_WORKDIR)
                + " && aider --yes-always --model "
                + shellQuote(requireText(orchestratorProperties.aider().model(), "AIDER_MODEL"))
                + " --message " + shellQuote(message);
    }

    private List<String> buildEnvironment() {
        List<String> environment = new ArrayList<>();
        putIfPresent(environment, "CLAUDE_API_KEY", System.getenv("CLAUDE_API_KEY"));
        putIfPresent(environment, "ANTHROPIC_API_KEY", System.getenv("CLAUDE_API_KEY"));
        putIfPresent(environment, "OLLAMA_BASE_URL", System.getenv("OLLAMA_BASE_URL"));
        putIfPresent(environment, "OLLAMA_API_BASE", System.getenv("OLLAMA_BASE_URL"));
        return environment;
    }

    private void ensureImageAvailable(String image) throws InterruptedException {
        try {
            dockerClient.inspectImageCmd(image).exec();
            return;
        } catch (NotFoundException ex) {
            log.info("Aider image not found locally. Pulling image={}", image);
        }

        dockerClient.pullImageCmd(image)
                .exec(new PullImageResultCallback())
                .awaitCompletion();
    }

    private int waitForExitCode(String containerId, int timeoutSeconds) throws InterruptedException, ExecutionException {
        ExecutorService waitExecutor = Executors.newSingleThreadExecutor();
        WaitContainerResultCallback waitCallback = dockerClient.waitContainerCmd(containerId)
                .exec(new WaitContainerResultCallback());
        Callable<Integer> waitForStatusCode = waitCallback::awaitStatusCode;
        Future<Integer> exitCodeFuture = waitExecutor.submit(waitForStatusCode);

        try {
            return exitCodeFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            log.warn("Aider sandbox timed out. containerId={}, timeoutSeconds={}", containerId, timeoutSeconds);
            stopContainerQuietly(containerId);
            return TIMEOUT_EXIT_CODE;
        } finally {
            exitCodeFuture.cancel(true);
            try {
                waitCallback.close();
            } catch (Exception ex) {
                log.warn("Failed to close Docker wait callback. containerId={}", containerId, ex);
            }
            waitExecutor.shutdownNow();
        }
    }

    private String collectContainerLogs(String containerId) {
        StringBuilder logs = new StringBuilder();
        try {
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(300)
                    .exec(new LogContainerResultCallback() {
                        @Override
                        public void onNext(Frame item) {
                            logs.append(item.toString());
                        }
                    })
                    .awaitCompletion(30, TimeUnit.SECONDS);
            return logs.toString();
        } catch (Exception ex) {
            log.warn("Failed to collect Aider container logs. containerId={}", containerId, ex);
            return logs.toString();
        }
    }

    private String safeCollectContainerLogs(String containerId) {
        try {
            return collectContainerLogs(containerId);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void stopContainerQuietly(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId)
                    .withTimeout(10)
                    .exec();
            log.info("Aider sandbox container stopped. containerId={}", containerId);
        } catch (Exception ex) {
            log.warn("Failed to stop Aider sandbox container. containerId={}", containerId, ex);
        }
    }

    private void removeContainerQuietly(String containerId, DevTask task) {
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
            log.info("Aider sandbox container removed. taskId={}, containerId={}", task.taskId(), containerId);
        } catch (Exception ex) {
            log.error("Failed to remove Aider sandbox container. taskId={}, containerId={}",
                    task.taskId(), containerId, ex);
        }
    }

    private String resolveDockerHost() {
        String configuredHost = orchestratorProperties.docker().host();
        if (configuredHost == null || configuredHost.isBlank()) {
            return "unix:///var/run/docker.sock";
        }
        return configuredHost.trim();
    }

    private int resolveTimeoutSeconds() {
        Integer configuredTimeout = orchestratorProperties.aider().timeoutSeconds();
        if (configuredTimeout == null || configuredTimeout <= 0) {
            return 300;
        }
        return configuredTimeout;
    }

    private void putIfPresent(List<String> environment, String key, String value) {
        if (value != null && !value.isBlank()) {
            environment.add(key + "=" + value);
        }
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 未設定，無法啟動 Aider sandbox。");
        }
        return value.trim();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @Override
    public void close() {
        try {
            dockerClient.close();
        } catch (Exception ex) {
            log.warn("Failed to close DockerClient.", ex);
        }

        try {
            dockerHttpClient.close();
        } catch (Exception ex) {
            log.warn("Failed to close Docker HTTP client.", ex);
        }
    }
}
