package org.celanwang.mio.sandbox;

import org.celanwang.mio.trace.TraceStore;
import org.springframework.stereotype.Component;

/** Writes sandbox observations into the existing trace log. Failures there must not grant execution. */
@Component
public class TraceSandboxAudit implements SandboxAudit {
    private final TraceStore traces;

    public TraceSandboxAudit(TraceStore traces) {
        this.traces = traces;
    }

    @Override
    public void record(SandboxEvent event) {
        String session = event.sessionId() == null || event.sessionId().isBlank() ? "sandbox" : event.sessionId();
        long timestamp = System.currentTimeMillis();
        String tool = event.tool() == null ? "" : event.tool();
        traces.append(session, "sandbox", event.type(), event.code() + " " + tool, event.detailAt(timestamp), timestamp);
    }
}
