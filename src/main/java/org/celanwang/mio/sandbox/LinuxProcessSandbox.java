package org.celanwang.mio.sandbox;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * bubblewrap + user namespace. If bubblewrap cannot be executed, launch throws and the caller
 * must stop. There is no unconfined {@code ProcessBuilder} fallback.
 */
@Component
public class LinuxProcessSandbox implements ProcessSandbox {
    private static final List<String> SYSTEM_DIRS = List.of("/usr", "/bin", "/lib", "/lib64");
    private static final List<String> SYSTEM_FILES = List.of(
            "/etc/resolv.conf", "/etc/hosts", "/etc/nsswitch.conf", "/etc/ssl", "/etc/ca-certificates");

    private final Path bwrap;
    private volatile Boolean usable;

    public LinuxProcessSandbox(SandboxProperties properties) {
        this(resolve(properties.getBwrap()));
    }

    public LinuxProcessSandbox(Path bwrap) {
        this.bwrap = bwrap;
    }

    public boolean usable() {
        Boolean cached = usable;
        if (cached != null) {
            return cached;
        }
        boolean result = probe();
        usable = result;
        return result;
    }

    private boolean probe() {
        if (!Files.isExecutable(bwrap)) {
            return false;
        }
        try {
            Process probe = new ProcessBuilder(bwrap.toString(), "--unshare-user", "--unshare-pid", "--unshare-ipc",
                    "--unshare-uts", "--disable-userns", "--die-with-parent",
                    "--ro-bind", "/usr", "/usr", "--ro-bind", "/lib", "/lib", "--ro-bind", "/lib64", "/lib64",
                    "--ro-bind", "/bin", "/bin", "--proc", "/proc", "--dev", "/dev", "--chdir", "/", "--",
                    "/bin/true").start();
            boolean finished = probe.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                probe.destroyForcibly();
                return false;
            }
            return probe.exitValue() == 0;
        } catch (Exception failure) {
            return false;
        }
    }

    @Override
    public List<String> plan(ProcessLaunch launch) {
        validate(launch);
        List<String> argv = new ArrayList<>();
        argv.add(bwrap.toString());
        argv.add("--unshare-user");
        argv.add("--unshare-pid");
        argv.add("--unshare-ipc");
        argv.add("--unshare-uts");
        argv.add("--disable-userns");
        argv.add("--die-with-parent");
        argv.add("--new-session");
        argv.add("--hostname");
        argv.add("mio-sandbox");
        if (!launch.networkAllowed()) {
            argv.add("--unshare-net");
        }
        argv.add("--proc");
        argv.add("/proc");
        argv.add("--dev");
        argv.add("/dev");
        argv.add("--tmpfs");
        argv.add("/tmp");
        for (String dir : SYSTEM_DIRS) {
            if (Files.isDirectory(Path.of(dir))) {
                argv.add("--ro-bind");
                argv.add(dir);
                argv.add(dir);
            }
        }
        for (String file : SYSTEM_FILES) {
            if (Files.exists(Path.of(file))) {
                argv.add("--ro-bind");
                argv.add(file);
                argv.add(file);
            }
        }
        for (ProcessLaunch.Mount mount : launch.mounts()) {
            argv.add(mount.readOnly() ? "--ro-bind" : "--bind");
            argv.add(mount.source().toString());
            argv.add(mount.target().toString());
        }
        argv.add("--clearenv");
        argv.add("--setenv");
        argv.add("PATH");
        argv.add("/usr/bin:/bin");
        argv.add("--setenv");
        argv.add("PYTHONIOENCODING");
        argv.add("utf-8");
        argv.add("--setenv");
        argv.add("PYTHONDONTWRITEBYTECODE");
        argv.add("1");
        argv.add("--setenv");
        argv.add("HOME");
        argv.add(launch.cwd().toString());
        argv.add("--setenv");
        argv.add("TMPDIR");
        argv.add("/tmp");
        argv.add("--chdir");
        argv.add(launch.cwd().toString());
        argv.add("--");
        argv.addAll(launch.command());
        return List.copyOf(argv);
    }

    @Override
    public Process launch(ProcessLaunch launch) throws IOException {
        launch.ticket().authorizeSubprocess();
        List<String> argv = plan(launch);
        if (!usable()) {
            throw new SandboxUnavailableException("SANDBOX_UNAVAILABLE", "进程沙箱不可用，已拒绝且未回退到宿主机");
        }
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.environment().clear();
        builder.redirectErrorStream(true);
        if (launch.stdout() != null) {
            builder.redirectOutput(launch.stdout().toFile());
        }
        try {
            return builder.start();
        } catch (IOException failure) {
            throw new SandboxUnavailableException("SANDBOX_START_FAILED", "进程沙箱启动失败，已拒绝且未回退到宿主机", failure);
        }
    }

    private void validate(ProcessLaunch launch) {
        if (launch.ticket() == null || launch.ticket().bootstrap()) {
            throw new SandboxRefusedException("TICKET_REQUIRED", "缺少可启动进程的执行票据");
        }
        if (!launch.ticket().profile().processSandbox()) {
            throw new SandboxRefusedException("NOT_SUBPROCESS", "票据不是进程沙箱票据");
        }
        if (launch.networkAllowed() != launch.ticket().profile().networkAllowed()) {
            throw new SandboxRefusedException("NETWORK_MISMATCH", "进程网络与隔离档不一致");
        }
        if (launch.hostCredentialDirs() || launch.ticket().profile().hostCredentialDirs()) {
            throw new SandboxRefusedException("CREDENTIAL_MOUNT", "默认不挂载宿主机凭据目录");
        }
        if (launch.command().isEmpty() || !Path.of(launch.command().get(0)).isAbsolute()) {
            throw new SandboxRefusedException("COMMAND_REJECTED", "沙箱命令必须是绝对路径");
        }
        MountPolicy.assertNarrow(launch.cwd(), false);
        for (ProcessLaunch.Mount mount : launch.mounts()) {
            MountPolicy.assertNarrow(mount.source(), false);
            if (!mount.source().toAbsolutePath().normalize().equals(mount.target().toAbsolutePath().normalize())) {
                throw new SandboxRefusedException("MOUNT_TARGET", "沙箱挂载必须保持原路径");
            }
        }
        if (!Files.isExecutable(bwrap)) {
            throw new SandboxUnavailableException("SANDBOX_UNAVAILABLE", "未找到 bubblewrap，已拒绝且未回退到宿主机");
        }
    }

    private static Path resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            return Path.of("bwrap");
        }
        Path given = Path.of(configured);
        if (given.isAbsolute()) {
            return given;
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!dir.isBlank()) {
                Path candidate = Path.of(dir, configured);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return given.toAbsolutePath();
    }
}
