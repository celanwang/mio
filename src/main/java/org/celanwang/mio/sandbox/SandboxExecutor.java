package org.celanwang.mio.sandbox;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Function;

/**
 * Opens a ticketed scope around an operation the permission chain already released.
 * Container classifications stop here until a real runtime exists; they never run on the host.
 */
@Component
public class SandboxExecutor {
    private final TicketIssuer issuer;
    private final WorkspaceLayout workspace;
    private final CredentialLease lease;
    private final SandboxAudit audit;
    private final SandboxProperties properties;
    private final ContainerRuntimeHook containers;

    public SandboxExecutor(TicketIssuer issuer, WorkspaceLayout workspace, CredentialLease lease, SandboxAudit audit,
                           SandboxProperties properties, ContainerRuntimeHook containers) {
        this.issuer = issuer;
        this.workspace = workspace;
        this.lease = lease;
        this.audit = audit;
        this.properties = properties;
        this.containers = containers;
    }

    public PrincipalId principal() {
        return properties.defaultPrincipal();
    }

    public java.time.Clock clock() {
        return issuer.clock();
    }

    public SandboxAudit audit() {
        return audit;
    }

    public ExecutionTicket admit(Admission admission) {
        Admission scoped = admission.principal() == null ? admission.withPrincipal(principal()) : admission;
        try {
            ExecutionTicket ticket = issuer.issue(scoped);
            audit.admitted(ticket);
            if (ticket.runtime() == RuntimeKind.CONTAINER) {
                containers.launch(ticket);
            }
            return ticket;
        } catch (SandboxRefusedException refused) {
            audit.refused(scoped.sessionId(), scoped.toolName(), scoped.principal(), refused.code(), refused.getMessage());
            throw refused;
        } catch (SandboxUnavailableException unavailable) {
            audit.refused(scoped.sessionId(), scoped.toolName(), scoped.principal(), unavailable.code(), unavailable.getMessage());
            throw unavailable;
        }
    }

    public ExecutionTicket admitReadonlySystem(String tool, java.util.Map<String, Object> input) {
        Classification classification = new SceneCatalog().classify(tool, input, true, null);
        if (classification.risk() != RiskClass.READONLY) {
            throw new SandboxRefusedException("TICKET_REQUIRED", "系统路径只能为只读操作补票据");
        }
        return admit(new Admission(principal(), "system", "system", tool, input, true, false,
                classification.credential(), null, null));
    }

    public SandboxScope open(ExecutionTicket ticket) {
        audit.started(ticket, "workspace=" + workspace.principalRoot(ticket.principal()));
        return SandboxScope.open(ticket, lease, workspace, issuer.clock());
    }

    public <T> T call(Admission admission, Function<ExecutionTicket, T> action) {
        ExecutionTicket ticket = admit(admission);
        try (SandboxScope ignored = open(ticket)) {
            T value = action.apply(ticket);
            audit.finished(ticket, "OK");
            return value;
        } catch (RuntimeException failure) {
            audit.finished(ticket, "ERROR");
            throw failure;
        }
    }

    public <T> T bootstrap(String credential, java.util.function.Supplier<T> action) {
        ExecutionTicket ticket = issuer.bootstrap(principal(), credential);
        audit.admitted(ticket);
        try (SandboxScope ignored = open(ticket)) {
            return action.get();
        } finally {
            audit.finished(ticket, "BOOTSTRAP");
        }
    }

    /**
     * Tool-call boundary used by local tools. A null executor is not used here.
     * When a scope is already open, {@code body} must use that ticket instead of calling this again.
     */
    public Mono<ToolResultBlock> guardCall(ToolCallParam call, ToolBase tool, String credential,
                                            Function<ExecutionTicket, String> body) {
        return Mono.defer(() -> {
            try {
                ExecutionTicket ticket = admit(Admission.tool(principal(), call, tool, credential));
                return Mono.using(
                        () -> open(ticket),
                        scope -> Mono.fromCallable(() -> ToolResultBlock.text(body.apply(ticket)))
                                .subscribeOn(Schedulers.boundedElastic()),
                        SandboxScope::close)
                        .doOnError(error -> audit.finished(ticket, "ERROR"))
                        .doOnSuccess(ignored -> audit.finished(ticket, "OK"));
            } catch (SandboxRefusedException refused) {
                return Mono.just(ToolResultBlock.text("error：沙箱拒绝执行，未访问外部服务（" + refused.code() + "）"));
            } catch (SandboxUnavailableException unavailable) {
                return Mono.just(ToolResultBlock.text("error：沙箱无法启动，已拒绝执行（" + unavailable.code() + "）"));
            }
        });
    }
}
