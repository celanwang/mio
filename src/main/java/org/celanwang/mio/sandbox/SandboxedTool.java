package org.celanwang.mio.sandbox;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.celanwang.mio.security.ToolContract;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** Runs a JVM tool only after a ticket is issued. Permission checks stay on the delegate. */
public final class SandboxedTool extends ToolBase implements ToolContract.SourceBound {
    private final ToolBase delegate;
    private final SandboxExecutor executor;
    private final String credential;

    public SandboxedTool(ToolBase delegate, SandboxExecutor executor, String credential) {
        super(builder(delegate));
        this.delegate = delegate;
        this.executor = executor;
        this.credential = credential;
    }

    private static Builder builder(ToolBase delegate) {
        Builder builder = ToolBase.builder()
                .name(delegate.getName())
                .description(delegate.getDescription())
                .inputSchema(delegate.getParameters())
                .readOnly(delegate.isReadOnly())
                .concurrencySafe(delegate.isConcurrencySafe());
        if (delegate.isMcp() && delegate.getMcpName() != null) {
            builder.mcp(delegate.getMcpName());
        }
        return builder;
    }

    @Override
    public AgentTool contractSource() {
        return delegate;
    }

    public String credential() {
        return credential;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return delegate.checkPermissions(input, context);
    }

    @Override
    public boolean matchRule(String name, Map<String, Object> input) {
        return delegate.matchRule(name, input);
    }

    @Override
    public List<PermissionRule> generateSuggestions(Map<String, Object> input) {
        return delegate.generateSuggestions(input);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam call) {
        if (SandboxScope.currentTicket().isPresent()) {
            return delegate.callAsync(call);
        }
        if (executor == null) {
            return Mono.just(ToolResultBlock.text("error：沙箱拒绝执行，未访问外部服务（TICKET_REQUIRED）"));
        }
        return Mono.defer(() -> {
            ExecutionTicket ticket;
            SandboxScope scope;
            try {
                ticket = executor.admit(Admission.tool(executor.principal(), call, delegate, credential));
                scope = executor.open(ticket);
            } catch (SandboxRefusedException refused) {
                return Mono.just(ToolResultBlock.text("error：沙箱拒绝执行，未访问外部服务（" + refused.code() + "）"));
            } catch (SandboxUnavailableException unavailable) {
                return Mono.just(ToolResultBlock.text("error：沙箱无法启动，已拒绝执行（" + unavailable.code() + "）"));
            }
            // Close before the result is delivered. Reactor runs doFinally after downstream onNext,
            // and the agent invokes the next tool from that callback. A leaked scope would reuse this ticket.
            return delegate.callAsync(call)
                    .doOnError(error -> executor.audit().finished(ticket, "ERROR"))
                    .doOnSuccess(ignored -> executor.audit().finished(ticket, "OK"))
                    .flatMap(result -> {
                        scope.close();
                        return Mono.just(result);
                    })
                    .doOnError(error -> scope.close())
                    .doFinally(signal -> scope.close());
        });
    }
}
