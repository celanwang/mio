package org.celanwang.mio.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.celanwang.mio.security.PolicyDecision;

import java.util.Map;

/**
 * Facts the sandbox needs in order to issue a ticket. {@code frameworkAdmitted} means AgentScope
 * already released the call (policy allow, human confirm, or Jev confirm). It is not a second allowlist.
 */
public record Admission(
        PrincipalId principal,
        String sessionId,
        String callId,
        String toolName,
        Map<String, Object> input,
        boolean declaredReadOnly,
        boolean frameworkAdmitted,
        String credential,
        PolicyDecision decision,
        Classification forced) {

    public Admission {
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    public static Admission tool(PrincipalId principal, ToolCallParam call, ToolBase tool, String credential) {
        RuntimeContext context = call.getRuntimeContext();
        ToolUseBlock use = call.getToolUseBlock();
        return new Admission(principal,
                context == null ? null : context.getSessionId(),
                use == null ? null : use.getId(),
                tool.getName(),
                call.getInput(),
                tool.isReadOnly(),
                true,
                credential,
                null,
                null);
    }

    public Admission withPrincipal(PrincipalId principal) {
        return new Admission(principal, sessionId, callId, toolName, input, declaredReadOnly, frameworkAdmitted,
                credential, decision, forced);
    }
}
