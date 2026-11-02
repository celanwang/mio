package org.celanwang.mio.sandbox;

/**
 * How an admitted operation must be carried out. This is not an allow/deny rule:
 * {@link org.celanwang.mio.security.PolicyEngine} already decided the operation may run.
 */
public record IsolationProfile(
        RiskClass risk,
        RuntimeKind runtime,
        boolean workspace,
        boolean credentialProxy,
        boolean executionTicket,
        boolean audit,
        boolean humanRequired,
        boolean unticketedEgressForbidden,
        boolean processSandbox,
        boolean readonlyMounts,
        boolean narrowMounts,
        boolean networkAllowed,
        boolean timeLimited,
        boolean hostCredentialDirs,
        boolean containerRuntime,
        String credential) {

    public boolean subprocess() {
        return runtime == RuntimeKind.SUBPROCESS || runtime == RuntimeKind.CONTAINER;
    }
}
