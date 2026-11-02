package org.celanwang.mio.sandbox;

import org.celanwang.mio.security.PolicyEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loopback status for the sandbox. It does not change policy mode or grant execution. */
@RestController
@RequestMapping("/api/security/sandbox")
public class SandboxController {
    private final SandboxProperties properties;
    private final PolicyEngine policy;
    private final ContainerRuntimeHook containers;
    private final RowIsolationGuard rows;
    private final LinuxProcessSandbox processes;

    public SandboxController(SandboxProperties properties, PolicyEngine policy, ContainerRuntimeHook containers,
                             RowIsolationGuard rows, LinuxProcessSandbox processes) {
        this.properties = properties;
        this.policy = policy;
        this.containers = containers;
        this.rows = rows;
        this.processes = processes;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("principal", properties.defaultPrincipal().value());
        body.put("policyMode", policy.isEnforce() ? "enforce" : "shadow");
        body.put("containerEnabled", containers.enabled());
        body.put("container", containers.establishment());
        body.put("rowIsolation", rows.establishment());
        body.put("processSandbox", processes.usable() ? "bwrap" : "UNAVAILABLE");
        body.put("established", List.of(
                "principal workspace",
                "execution tickets",
                "credential proxy",
                "subprocess bubblewrap required"));
        body.put("notEstablished", List.of(
                "gvisor",
                "database row security",
                "destination network filter",
                "seccomp"));
        return body;
    }
}
