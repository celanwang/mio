package org.celanwang.mio.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LinuxProcessSandboxTest {
    @TempDir Path temp;

    @Test
    void planIsBubblewrapAndNeverABareInterpreter() {
        LinuxProcessSandbox sandbox = new LinuxProcessSandbox(Path.of("/usr/bin/bwrap"));
        ExecutionTicket ticket = ticket(false);
        if (!Files.isExecutable(sandboxPath())) {
            assertThatThrownBy(() -> sandbox.plan(launch(ticket, false, List.of("/usr/bin/true"))))
                    .isInstanceOf(SandboxUnavailableException.class);
            return;
        }
        List<String> argv = sandbox.plan(launch(ticket, true, List.of("/usr/bin/true")));
        assertThat(argv.get(0)).endsWith("bwrap");
        assertThat(argv).contains("--unshare-user", "--unshare-pid", "--clearenv", "--ro-bind", "--die-with-parent");
        assertThat(argv).doesNotContain("--unshare-net");
        assertThat(argv).doesNotContain("python");
        List<String> offline = sandbox.plan(launch(ticket(true), false, List.of("/usr/bin/true")));
        assertThat(offline).contains("--unshare-net");
        assertThatThrownBy(() -> sandbox.plan(launch(ticket, true, List.of("python"))))
                .isInstanceOf(SandboxRefusedException.class);
    }

    @Test
    void bubblewrapHidesFilesOutsideTheMount() throws Exception {
        LinuxProcessSandbox sandbox = new LinuxProcessSandbox(sandboxPath());
        assumeTrue(sandbox.usable(), "bubblewrap user namespaces are not available");
        Path secret = temp.resolve("secret.txt");
        Files.writeString(secret, "top-secret");
        Path work = temp.resolve("work");
        Files.createDirectories(work);
        Path stdout = work.resolve("out.txt");
        Files.createFile(stdout);
        ExecutionTicket ticket = ticket(false);
        Process hidden = sandbox.launch(launch(ticket, work, stdout, List.of("/bin/cat", secret.toString()), true));
        assertThat(hidden.waitFor()).isNotZero();
        ExecutionTicket echoTicket = ticket(false);
        Path echoOut = work.resolve("echo.txt");
        Files.createFile(echoOut);
        Process echo = sandbox.launch(launch(echoTicket, work, echoOut, List.of("/bin/echo", "sandbox-ok"), true));
        assertThat(echo.waitFor()).isZero();
        assertThat(Files.readString(echoOut)).contains("sandbox-ok");
    }

    private static Path sandboxPath() {
        return Files.isExecutable(Path.of("/usr/bin/bwrap")) ? Path.of("/usr/bin/bwrap") : Path.of("bwrap");
    }

    private ProcessLaunch launch(ExecutionTicket ticket, boolean network, List<String> command) {
        return launch(ticket, temp.resolve("cwd"), temp.resolve("stdout"), command, network);
    }

    private ProcessLaunch launch(ExecutionTicket ticket, Path cwd, Path stdout, List<String> command, boolean network) {
        try {
            Files.createDirectories(cwd);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return new ProcessLaunch(ticket, command, List.of(new ProcessLaunch.Mount(cwd, cwd, false)), network, false,
                cwd, stdout, Duration.ofSeconds(5));
    }

    private static ExecutionTicket ticket(boolean offline) {
        return ExecutionTicket.issue(PrincipalId.LOCAL_OWNER, "s", "c", offline ? "offline" : "tencent_ride",
                new Classification(RiskClass.READONLY, RuntimeKind.SUBPROCESS, offline ? null : "tencent-ride"),
                "ALLOW", "static-allow", false, true, false, false, Clock.systemUTC(), Duration.ofMinutes(2));
    }
}
