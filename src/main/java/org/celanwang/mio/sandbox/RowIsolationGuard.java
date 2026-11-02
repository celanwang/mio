package org.celanwang.mio.sandbox;

import org.springframework.stereotype.Component;

/**
 * P2 row-isolation scaffold. PostgreSQL row security is not installed; callers that opt in
 * must compare principal ids before reading another subject's row.
 */
@Component
public class RowIsolationGuard {
    public void check(PrincipalId actor, PrincipalId owner) {
        if (actor == null || owner == null || !actor.equals(owner)) {
            throw new SandboxRefusedException("CROSS_PRINCIPAL", "跨主体数据访问被拒绝");
        }
    }

    /** Future predicate for a principal column. Not applied to existing tables in this change. */
    public String principalPredicate() {
        return "principal_id = ?";
    }

    public String establishment() {
        return "NOT_ESTABLISHED";
    }
}
