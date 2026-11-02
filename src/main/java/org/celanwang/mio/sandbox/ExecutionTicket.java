package org.celanwang.mio.sandbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Capability to run one already-admitted operation inside the sandbox.
 * Issued only after {@link org.celanwang.mio.security.PolicyEngine} allows it,
 * or the existing permission chain has admitted a human/Jev approval.
 */
public final class ExecutionTicket {
    private final String id;
    private final PrincipalId principal;
    private final String sessionId;
    private final String callId;
    private final String tool;
    private final Classification classification;
    private final IsolationProfile profile;
    private final String policyEffect;
    private final String policySource;
    private final boolean humanConfirmed;
    private final boolean frameworkAdmitted;
    private final boolean bootstrap;
    private final boolean shadowDeny;
    private final Instant expiresAt;
    private final AtomicBoolean processUsed = new AtomicBoolean();

    private ExecutionTicket(String id, PrincipalId principal, String sessionId, String callId, String tool,
                            Classification classification, IsolationProfile profile, String policyEffect,
                            String policySource, boolean humanConfirmed, boolean frameworkAdmitted,
                            boolean bootstrap, boolean shadowDeny, Instant expiresAt) {
        this.id = id;
        this.principal = principal;
        this.sessionId = sessionId;
        this.callId = callId;
        this.tool = tool;
        this.classification = classification;
        this.profile = profile;
        this.policyEffect = policyEffect;
        this.policySource = policySource;
        this.humanConfirmed = humanConfirmed;
        this.frameworkAdmitted = frameworkAdmitted;
        this.bootstrap = bootstrap;
        this.shadowDeny = shadowDeny;
        this.expiresAt = expiresAt;
    }

    public static ExecutionTicket issue(PrincipalId principal, String sessionId, String callId, String tool,
                                        Classification classification, String policyEffect, String policySource,
                                        boolean humanConfirmed, boolean frameworkAdmitted, boolean bootstrap,
                                        boolean shadowDeny, Clock clock, Duration ttl) {
        if (ttl == null || ttl.isNegative()) {
            throw new IllegalArgumentException("票据有效期无效");
        }
        return new ExecutionTicket(UUID.randomUUID().toString(), principal, sessionId, callId, tool, classification,
                SandboxMatrix.profile(classification), policyEffect, policySource, humanConfirmed, frameworkAdmitted,
                bootstrap, shadowDeny, clock.instant().plus(ttl));
    }

    public String id() {
        return id;
    }

    public PrincipalId principal() {
        return principal;
    }

    public String sessionId() {
        return sessionId;
    }

    public String callId() {
        return callId;
    }

    public String tool() {
        return tool;
    }

    public Classification classification() {
        return classification;
    }

    public IsolationProfile profile() {
        return profile;
    }

    public RiskClass risk() {
        return classification.risk();
    }

    public RuntimeKind runtime() {
        return classification.runtime();
    }

    public String credential() {
        return classification.credential();
    }

    public String policyEffect() {
        return policyEffect;
    }

    public String policySource() {
        return policySource;
    }

    public boolean humanConfirmed() {
        return humanConfirmed;
    }

    public boolean frameworkAdmitted() {
        return frameworkAdmitted;
    }

    public boolean bootstrap() {
        return bootstrap;
    }

    public boolean shadowDeny() {
        return shadowDeny;
    }

    public boolean expired(Clock clock) {
        return !clock.instant().isBefore(expiresAt);
    }

    public boolean permitsEgress(String name) {
        if (credential() == null || !credential().equals(name)) {
            return false;
        }
        if (risk() == RiskClass.IRREVERSIBLE && !humanConfirmed && !bootstrap) {
            return false;
        }
        return true;
    }

    public boolean covers(Classification required, Clock clock) {
        if (bootstrap || expired(clock)) {
            return false;
        }
        if (risk().ordinal() < required.risk().ordinal()) {
            return false;
        }
        if (runtime().ordinal() < required.runtime().ordinal()) {
            return false;
        }
        if (required.risk() == RiskClass.IRREVERSIBLE && !humanConfirmed) {
            return false;
        }
        return required.credential() == null || credential() == null || required.credential().equals(credential());
    }

    /** One subprocess start per ticket. A second start is a refusal, not a bare retry. */
    public void authorizeSubprocess() {
        if (bootstrap || (runtime() != RuntimeKind.SUBPROCESS && runtime() != RuntimeKind.CONTAINER)) {
            throw new SandboxRefusedException("NOT_SUBPROCESS", "票据不能启动进程");
        }
        if (!processUsed.compareAndSet(false, true)) {
            throw new SandboxRefusedException("TICKET_REUSED", "执行票据已使用，拒绝再次启动进程");
        }
    }
}
