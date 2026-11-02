package org.celanwang.mio.sandbox;

import org.celanwang.mio.config.ToolPolicies;
import org.celanwang.mio.security.PolicyDecision;
import org.celanwang.mio.security.PolicyEffect;
import org.celanwang.mio.security.PolicyEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
public class TicketIssuer {
    private final PolicyEngine policy;
    private final SceneCatalog scenes;
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public TicketIssuer(PolicyEngine policy, SceneCatalog scenes, SandboxProperties properties) {
        this(policy, scenes, Clock.systemUTC(), properties.getTicketTtl());
    }

    public TicketIssuer(PolicyEngine policy, SceneCatalog scenes, Clock clock, Duration ttl) {
        this.policy = policy;
        this.scenes = scenes;
        this.clock = clock;
        this.ttl = ttl;
    }

    public Clock clock() {
        return clock;
    }

    public ExecutionTicket issue(Admission admission) {
        PrincipalId principal = admission.principal() == null ? PrincipalId.LOCAL_OWNER : admission.principal();
        Classification classified = scenes.classify(admission.toolName(), admission.input(), admission.declaredReadOnly(),
                admission.credential());
        Classification classification = admission.forced() == null
                ? classified
                : SandboxMatrix.stricter(classified, admission.forced());
        boolean readOnly = classification.risk() == RiskClass.READONLY;
        PolicyDecision decision = admission.decision() != null
                ? admission.decision()
                : policy.evaluate(admission.toolName(), readOnly);
        boolean shadowDeny = decision.effect() == PolicyEffect.DENY && !policy.isEnforce();
        if (decision.effect() == PolicyEffect.DENY && policy.isEnforce()) {
            throw new SandboxRefusedException("POLICY_DENY", "权限裁决拒绝该操作");
        }
        if (!readOnly && !admission.frameworkAdmitted()) {
            throw new SandboxRefusedException("TICKET_REQUIRED", "写操作缺少权限链放行，拒绝签发票据");
        }
        if (readOnly && decision.effect() != PolicyEffect.ALLOW && !admission.frameworkAdmitted()) {
            throw new SandboxRefusedException("TICKET_REQUIRED", "只读操作未被裁决放行，拒绝出站");
        }
        if (!readOnly && policy.isCircuitOpen() && !admission.frameworkAdmitted()) {
            throw new SandboxRefusedException("KILL_SWITCH", "急停开启，未获人工确认的写操作被拒绝");
        }
        boolean human = classification.risk() == RiskClass.IRREVERSIBLE
                && admission.frameworkAdmitted()
                && !ToolPolicies.isJevReviewable(admission.toolName(), admission.input());
        if (classification.risk() == RiskClass.IRREVERSIBLE && !human) {
            throw new SandboxRefusedException("HUMAN_REQUIRED", "不可逆操作需要人工确认后的执行票据");
        }
        return ExecutionTicket.issue(principal, admission.sessionId(), admission.callId(), admission.toolName(),
                classification, decision.effect().name(), decision.source(), human, admission.frameworkAdmitted(),
                false, shadowDeny, clock, ttl);
    }

    /** Startup MCP handshake only. Not a model write grant and not a subprocess grant. */
    public ExecutionTicket bootstrap(PrincipalId principal, String credential) {
        Classification classification = new Classification(RiskClass.READONLY, RuntimeKind.JVM, credential);
        return ExecutionTicket.issue(principal, "bootstrap", "bootstrap", "bootstrap", classification,
                PolicyEffect.ALLOW.name(), "sandbox-bootstrap", false, false, true, false, clock, ttl);
    }
}
