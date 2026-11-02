package org.celanwang.mio.sandbox;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds a ticket to the thread that opened the scope and holds the credential lease.
 * Close is safe from another thread: reactor often completes the call away from the opener,
 * and a leftover scope must not be reused as a process ticket.
 */
public final class SandboxScope implements AutoCloseable {
    private static final ConcurrentHashMap<Long, SandboxScope> OPEN = new ConcurrentHashMap<>();

    private final ExecutionTicket ticket;
    private final CredentialLease lease;
    private final boolean acquired;
    private final long owner;

    private SandboxScope(ExecutionTicket ticket, CredentialLease lease, boolean acquired, long owner) {
        this.ticket = ticket;
        this.lease = lease;
        this.acquired = acquired;
        this.owner = owner;
    }

    public static Optional<ExecutionTicket> currentTicket() {
        SandboxScope scope = OPEN.get(Thread.currentThread().getId());
        return scope == null ? Optional.empty() : Optional.of(scope.ticket);
    }

    public static SandboxScope open(ExecutionTicket ticket, CredentialLease lease, WorkspaceLayout workspace, Clock clock) {
        long owner = Thread.currentThread().getId();
        if (OPEN.containsKey(owner)) {
            throw new SandboxRefusedException("SCOPE_NESTED", "沙箱作用域不能嵌套");
        }
        if (ticket.expired(clock)) {
            throw new SandboxRefusedException("TICKET_EXPIRED", "执行票据已过期");
        }
        workspace.ensure(ticket.principal());
        boolean acquired = false;
        if (ticket.credential() != null) {
            lease.acquire(ticket.credential(), ticket);
            acquired = true;
        }
        SandboxScope scope = new SandboxScope(ticket, lease, acquired, owner);
        if (OPEN.putIfAbsent(owner, scope) != null) {
            if (acquired) {
                lease.release(ticket.credential(), ticket);
            }
            throw new SandboxRefusedException("SCOPE_NESTED", "沙箱作用域不能嵌套");
        }
        return scope;
    }

    public ExecutionTicket ticket() {
        return ticket;
    }

    @Override
    public void close() {
        OPEN.remove(owner, this);
        if (acquired && ticket.credential() != null) {
            lease.release(ticket.credential(), ticket);
        }
    }
}
