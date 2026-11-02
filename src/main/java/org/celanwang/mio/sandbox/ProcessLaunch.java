package org.celanwang.mio.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** A subprocess the sandbox is willing to start. The command line must not carry credential material. */
public record ProcessLaunch(
        ExecutionTicket ticket,
        List<String> command,
        List<Mount> mounts,
        boolean networkAllowed,
        boolean hostCredentialDirs,
        Path cwd,
        Path stdout,
        Duration timeout) {

    public ProcessLaunch {
        command = List.copyOf(command);
        mounts = List.copyOf(mounts);
    }

    public record Mount(Path source, Path target, boolean readOnly) {
    }
}
