package org.celanwang.mio.sandbox;

import org.celanwang.mio.security.PolicyEngine;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

final class SandboxStack {
    final RecordingAudit audit = new RecordingAudit();
    final CredentialLease lease = new CredentialLease();
    final SandboxProperties properties = new SandboxProperties();
    final PolicyEngine policy;
    final SceneCatalog scenes = new SceneCatalog();
    final TicketIssuer issuer;
    final WorkspaceLayout workspace;
    final ContainerRuntimeHook containers;
    final SandboxExecutor executor;
    final CredentialProxy credentials;

    SandboxStack(Path root, PolicyEngine policy, Clock clock, Supplier<String> mcd, Supplier<String> ride, Supplier<String> weibo) {
        this.policy = policy;
        properties.setRoot(root);
        properties.setPrincipal("local-owner");
        properties.setContainerEnabled(false);
        properties.setTicketTtl(Duration.ofMinutes(2));
        properties.setBwrap("bwrap");
        issuer = new TicketIssuer(policy, scenes, clock, properties.getTicketTtl());
        workspace = new WorkspaceLayout(properties);
        containers = new ContainerRuntimeHook(properties);
        executor = new SandboxExecutor(issuer, workspace, lease, audit, properties, containers);
        credentials = new CredentialProxy(lease, audit, mcd, ride, weibo);
    }
}
