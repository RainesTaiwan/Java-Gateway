package com.agentic.gateway.orchestrator.agent;

import com.agentic.gateway.config.OrchestratorProperties;
import com.agentic.gateway.dto.DevTask;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
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
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 透過 DooD 在宿主機 Docker Engine 上啟動開發引擎容器。
 *
 * <p>Cursor 與 Aider 共用此 runner，避免重複實作網路、volume、超時與 log 收集邏輯。</p>
 */
@Slf4j
@Component
public class DockerAgentRunner implements Closeable {

    private static final int TIMEOUT_EXIT_CODE = 124;
    private static final long SANDBOX_MEMORY_BYTES = 1_073_741_824L;
    private static final long SANDBOX_NANO_CPUS = 1_000_000_000L;
    private static final String SANDBOX_WORKSPACE = "/app/workspace";
    private static final Map<String, String> SANDBOX_TMPFS = Map.of(
            "/tmp", "rw,nosuid,nodev,size=256m",
            "/root", "rw,nosuid,nodev,size=512m"
    );

    private final OrchestratorProperties orchestratorProperties;
    private final DockerClient dockerClient;
    private final DockerHttpClient dockerHttpClient;

    public DockerAgentRunner(OrchestratorProperties orchestratorProperties) {
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

    public AgentExecutionResult run(DevTask task, DockerAgentRunSpec spec, String engineName) {
        String image = requireText(spec.image(), "agent image");
        String containerId = null;
        String containerName = spec.containerNamePrefix() + "-" + task.taskId() + "-"
                + UUID.randomUUID().toString().substring(0, 8);

        try {
            ensureImageAvailable(image);

            CreateContainerResponse container = createContainer(spec, image, containerName);
            containerId = container.getId();
            log.info("{} sandbox container created. taskId={}, containerId={}, image={}",
                    engineName, task.taskId(), containerId, image);

            dockerClient.startContainerCmd(containerId).exec();
            log.info("{} sandbox container started. taskId={}, containerId={}", engineName, task.taskId(), containerId);

            int exitCode = waitForExitCode(containerId, resolveTimeoutSeconds());
            boolean timedOut = exitCode == TIMEOUT_EXIT_CODE;
            String logs = collectContainerLogs(containerId);

            log.info("{} sandbox finished. taskId={}, containerId={}, exitCode={}, timedOut={}",
                    engineName, task.taskId(), containerId, exitCode, timedOut);
            return new AgentExecutionResult(exitCode, timedOut, logs, engineName);
        } catch (Exception exception) {
            String logs = containerId == null ? "" : safeCollectContainerLogs(containerId);
            log.error("{} sandbox execution failed. taskId={}, containerId={}",
                    engineName, task.taskId(), containerId, exception);
            return new AgentExecutionResult(1, false, logs, engineName);
        } finally {
            if (containerId != null) {
                removeContainerQuietly(containerId, task, engineName);
            }
        }
    }

    private CreateContainerResponse createContainer(DockerAgentRunSpec spec, String image, String containerName) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(new Bind(
                        requireText(orchestratorProperties.workspace().hostPath(), "ORCHESTRATOR_WORKSPACE_HOST_PATH"),
                        new Volume(SANDBOX_WORKSPACE),
                        AccessMode.rw
                ))
                .withNetworkMode(requireText(orchestratorProperties.docker().networkName(), "DOCKER_NETWORK_NAME"))
                .withMemory(SANDBOX_MEMORY_BYTES)
                .withMemorySwap(SANDBOX_MEMORY_BYTES)
                .withNanoCPUs(SANDBOX_NANO_CPUS)
                .withCapDrop(Capability.ALL)
                .withPrivileged(false)
                .withReadonlyRootfs(true)
                .withTmpFs(SANDBOX_TMPFS)
                .withAutoRemove(false);

        return dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withHostConfig(hostConfig)
                .withWorkingDir(spec.workdir())
                .withEntrypoint("sh", "-lc")
                .withCmd(spec.shellCommand())
                .withEnv(spec.environment())
                .exec();
    }

    private void ensureImageAvailable(String image) throws InterruptedException {
        try {
            dockerClient.inspectImageCmd(image).exec();
            return;
        } catch (NotFoundException notFound) {
            log.info("Agent image not found locally. Pulling image={}", image);
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
        } catch (TimeoutException timeout) {
            log.warn("Agent sandbox timed out. containerId={}, timeoutSeconds={}", containerId, timeoutSeconds);
            stopContainerQuietly(containerId);
            return TIMEOUT_EXIT_CODE;
        } finally {
            exitCodeFuture.cancel(true);
            try {
                waitCallback.close();
            } catch (Exception exception) {
                log.warn("Failed to close Docker wait callback. containerId={}", containerId, exception);
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
                            if (item != null && item.getPayload() != null) {
                                logs.append(new String(item.getPayload(), StandardCharsets.UTF_8));
                            }
                        }
                    })
                    .awaitCompletion(30, TimeUnit.SECONDS);
            return logs.toString();
        } catch (Exception exception) {
            log.warn("Failed to collect agent container logs. containerId={}", containerId, exception);
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
            log.info("Agent sandbox container stopped. containerId={}", containerId);
        } catch (Exception exception) {
            log.warn("Failed to stop agent sandbox container. containerId={}", containerId, exception);
        }
    }

    private void removeContainerQuietly(String containerId, DevTask task, String engineName) {
        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
            log.info("{} sandbox container removed. taskId={}, containerId={}", engineName, task.taskId(), containerId);
        } catch (Exception exception) {
            log.error("Failed to remove {} sandbox container. taskId={}, containerId={}",
                    engineName, task.taskId(), containerId, exception);
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
        Integer configuredTimeout = orchestratorProperties.agent().timeoutSeconds();
        if (configuredTimeout == null || configuredTimeout <= 0) {
            return 300;
        }
        return configuredTimeout;
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 未設定，無法啟動 agent sandbox。");
        }
        return value.trim();
    }

    @Override
    public void close() {
        try {
            dockerClient.close();
        } catch (Exception exception) {
            log.warn("Failed to close DockerClient.", exception);
        }

        try {
            dockerHttpClient.close();
        } catch (Exception exception) {
            log.warn("Failed to close Docker HTTP client.", exception);
        }
    }
}
