package org.celanwang.mio.sandbox;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Cross-thread egress lease. HTTP customizers run off the tool thread, so a thread-local is not enough.
 * One credential is held by one ticket at a time; the same ticket may re-enter.
 */
@Component
public class CredentialLease {
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(20);

    private static final class Hold {
        private ExecutionTicket ticket;
        private int depth;
    }

    private final ConcurrentHashMap<String, Hold> holds = new ConcurrentHashMap<>();

    public void acquire(String name, ExecutionTicket ticket) {
        Hold hold = holds.computeIfAbsent(name, ignored -> new Hold());
        long deadline = System.nanoTime() + WAIT_NANOS;
        synchronized (hold) {
            while (hold.ticket != null && !hold.ticket.id().equals(ticket.id())) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new SandboxRefusedException("LEASE_BUSY", "凭据出站正在被其他票据占用");
                }
                try {
                    hold.wait(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 200L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SandboxRefusedException("LEASE_INTERRUPTED", "凭据出站等待被中断");
                }
            }
            hold.ticket = ticket;
            hold.depth++;
        }
    }

    public void release(String name, ExecutionTicket ticket) {
        Hold hold = holds.get(name);
        if (hold == null) {
            return;
        }
        synchronized (hold) {
            if (hold.ticket == null || !hold.ticket.id().equals(ticket.id())) {
                return;
            }
            hold.depth--;
            if (hold.depth <= 0) {
                hold.ticket = null;
                hold.depth = 0;
                hold.notifyAll();
            }
        }
    }

    public ExecutionTicket current(String name) {
        Hold hold = holds.get(name);
        if (hold == null) {
            return null;
        }
        synchronized (hold) {
            return hold.ticket;
        }
    }
}
