package org.celanwang.mio.sandbox;

import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.trace.TraceStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.function.Supplier;

/**
 * Ticket issuer, executor, and credential proxy for offline harnesses.
 * Mock {@code TencentRideService}; this stack does not start bubblewrap.
 */
public final class OfflineSandbox implements AutoCloseable {
    public final PolicyEngine policy;
    public final TraceStore traces;
    public final SandboxExecutor executor;
    public final CredentialProxy credentials;
    public final Path root;

    public OfflineSandbox(PolicyEngine policy, TraceStore traces) {
        this(policy, traces, () -> "", () -> "", () -> "");
    }

    public OfflineSandbox(PolicyEngine policy, TraceStore traces,
                          Supplier<String> mcd, Supplier<String> ride, Supplier<String> weibo) {
        this.policy = policy;
        this.traces = traces;
        try {
            this.root = Files.createTempDirectory("mio-offline-sandbox");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        var properties = new SandboxProperties();
        properties.setRoot(root);
        properties.setPrincipal("local-owner");
        properties.setContainerEnabled(false);
        properties.setTicketTtl(Duration.ofMinutes(2));
        properties.setBwrap("bwrap");
        var scenes = new SceneCatalog();
        var issuer = new TicketIssuer(policy, scenes, Clock.systemUTC(), properties.getTicketTtl());
        var workspace = new WorkspaceLayout(properties);
        var lease = new CredentialLease();
        var audit = new TraceSandboxAudit(traces);
        this.executor = new SandboxExecutor(issuer, workspace, lease, audit, properties, new ContainerRuntimeHook(properties));
        this.credentials = new CredentialProxy(lease, audit, mcd, ride, weibo);
    }

    @Override
    public void close() {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup of the offline workspace.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup of the offline workspace.
        }
    }
}
