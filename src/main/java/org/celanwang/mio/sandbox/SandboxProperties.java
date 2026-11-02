package org.celanwang.mio.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

@Component
@ConfigurationProperties("app.sandbox")
public class SandboxProperties {
    /** Dedicated workspace parent. Never the filesystem root. */
    private Path root = Path.of(".mio/sandbox");
    /** Product boundary is still one local owner. The id is explicit so more principals can be added later. */
    private String principal = PrincipalId.LOCAL_OWNER.value();
    /** Off unless an operator opts into the container seam. Default tests and CI do not need Docker or gVisor. */
    private boolean containerEnabled = false;
    /** Optional runsc/gVisor binary. Looked at only when {@link #containerEnabled} is true, and still not executed. */
    private String containerRuntime = "";
    /** bubblewrap executable. Subprocess launch fails closed when it cannot be used. */
    private String bwrap = "bwrap";
    private Duration ticketTtl = Duration.ofMinutes(2);

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = root;
    }

    public String getPrincipal() {
        return principal;
    }

    public void setPrincipal(String principal) {
        this.principal = principal;
    }

    public boolean isContainerEnabled() {
        return containerEnabled;
    }

    public void setContainerEnabled(boolean containerEnabled) {
        this.containerEnabled = containerEnabled;
    }

    public String getContainerRuntime() {
        return containerRuntime;
    }

    public void setContainerRuntime(String containerRuntime) {
        this.containerRuntime = containerRuntime;
    }

    public String getBwrap() {
        return bwrap;
    }

    public void setBwrap(String bwrap) {
        this.bwrap = bwrap;
    }

    public Duration getTicketTtl() {
        return ticketTtl;
    }

    public void setTicketTtl(Duration ticketTtl) {
        this.ticketTtl = ticketTtl;
    }

    public PrincipalId defaultPrincipal() {
        return PrincipalId.parse(principal);
    }
}
