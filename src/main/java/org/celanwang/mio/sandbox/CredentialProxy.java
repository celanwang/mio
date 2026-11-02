package org.celanwang.mio.sandbox;

import org.celanwang.mio.service.WeiboOAuthService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The only place tool egress may read a credential. Material is returned to the transport or
 * subprocess stdin after a live ticket check, and is never written to the audit log.
 */
@Component
public class CredentialProxy {
    private final CredentialLease lease;
    private final SandboxAudit audit;
    private final Supplier<String> mcd;
    private final Supplier<String> ride;
    private final Supplier<String> weibo;

    public CredentialProxy(CredentialLease lease, SandboxAudit audit,
                           @Value("${app.mcp.mcd.token:}") String mcdToken,
                           @Value("${app.tencent-ride.token:}") String rideToken,
                           ObjectProvider<WeiboOAuthService> weibo) {
        this(lease, audit, () -> mcdToken == null ? "" : mcdToken,
                () -> rideToken == null ? "" : rideToken,
                () -> {
                    WeiboOAuthService oauth = weibo.getIfAvailable();
                    if (oauth == null) {
                        throw new SandboxRefusedException("NO_CREDENTIAL", "微博凭据代理不可用");
                    }
                    return oauth.accessToken();
                });
    }

    public CredentialProxy(CredentialLease lease, SandboxAudit audit,
                           Supplier<String> mcd, Supplier<String> ride, Supplier<String> weibo) {
        this.lease = lease;
        this.audit = audit;
        this.mcd = mcd;
        this.ride = ride;
        this.weibo = weibo;
    }

    public String materialize(ExecutionTicket ticket, String name) {
        if (ticket == null || ticket.expired(java.time.Clock.systemUTC())) {
            throw new SandboxRefusedException("NO_EGRESS_TICKET", "无有效执行票据，拒绝凭据出站");
        }
        if (!ticket.permitsEgress(name)) {
            throw new SandboxRefusedException("EGRESS_DENIED", "执行票据不允许该凭据出站");
        }
        String secret = switch (name) {
            case "mcd" -> mcd.get();
            case "tencent-ride" -> ride.get();
            case "weibo" -> weibo.get();
            default -> throw new SandboxRefusedException("UNKNOWN_CREDENTIAL", "未知凭据域");
        };
        audit.egress(ticket, name);
        return secret == null ? "" : secret;
    }

    /** HTTP customizers on another thread use the lease instead of a thread-local. */
    public String materializeCurrent(String name) {
        return materialize(lease.current(name), name);
    }
}
