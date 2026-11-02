package org.celanwang.mio.sandbox;

/**
 * Locked risk × runtime matrix. When two classifications conflict, both axes take the stricter value.
 * A conflict never loosens mounts, tickets, or human confirmation.
 */
public final class SandboxMatrix {

    private SandboxMatrix() {
    }

    public static Classification stricter(Classification left, Classification right) {
        if (left.credential() != null && right.credential() != null
                && !left.credential().equals(right.credential())) {
            throw new SandboxRefusedException("CREDENTIAL_CONFLICT", "隔离分类的凭据域冲突，已拒绝执行");
        }
        RiskClass risk = left.risk().ordinal() >= right.risk().ordinal() ? left.risk() : right.risk();
        RuntimeKind runtime = left.runtime().ordinal() >= right.runtime().ordinal() ? left.runtime() : right.runtime();
        String credential = left.credential() != null ? left.credential() : right.credential();
        return new Classification(risk, runtime, credential);
    }

    public static IsolationProfile profile(Classification classification) {
        boolean subprocess = classification.runtime() != RuntimeKind.JVM;
        boolean container = classification.runtime() == RuntimeKind.CONTAINER;
        boolean irreversible = classification.risk() == RiskClass.IRREVERSIBLE;
        boolean reversible = classification.risk() == RiskClass.REVERSIBLE;
        // Ride quote/book must reach the provider. Other readonly subprocesses stay off the network.
        // Reversible subprocesses gain the matrix "net" allowance. Irreversible non-ride stays closed.
        boolean network = subprocess && ("tencent-ride".equals(classification.credential()) || reversible);
        return new IsolationProfile(
                classification.risk(),
                classification.runtime(),
                true,
                classification.credential() != null,
                true,
                true,
                irreversible,
                true,
                subprocess,
                subprocess,
                subprocess && !container,
                network && !container,
                subprocess,
                false,
                container,
                classification.credential());
    }
}
