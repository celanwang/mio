package org.celanwang.mio.sandbox;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P2 seam for gVisor or another container runtime. Launch never falls back to the host or to bubblewrap.
 * Even when the flag and a binary are present, this build does not start a container.
 */
@Component
public class ContainerRuntimeHook {
    private final SandboxProperties properties;

    public ContainerRuntimeHook(SandboxProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.isContainerEnabled();
    }

    public String establishment() {
        return "NOT_ESTABLISHED";
    }

    public void launch(ExecutionTicket ticket) {
        if (!properties.isContainerEnabled()) {
            throw new SandboxUnavailableException("CONTAINER_DISABLED", "容器运行时未启用，已拒绝且未回退到宿主机");
        }
        String runtime = properties.getContainerRuntime() == null ? "" : properties.getContainerRuntime().trim();
        if (runtime.isBlank() || !Files.isExecutable(Path.of(runtime))) {
            throw new SandboxUnavailableException("CONTAINER_RUNTIME_MISSING", "未找到可用的容器运行时，已拒绝执行");
        }
        throw new SandboxUnavailableException("CONTAINER_NOT_ESTABLISHED",
                "gVisor/容器隔离仍是接缝，当前构建不会启动容器，也不会改在宿主机执行");
    }
}
