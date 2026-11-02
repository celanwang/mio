package org.celanwang.mio.sandbox;

import java.io.IOException;
import java.util.List;

/** Starts a process inside the OS sandbox. Implementations must not return an unsandboxed process. */
public interface ProcessSandbox {
    List<String> plan(ProcessLaunch launch);

    Process launch(ProcessLaunch launch) throws IOException;
}
