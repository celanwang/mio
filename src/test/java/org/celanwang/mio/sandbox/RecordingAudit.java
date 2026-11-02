package org.celanwang.mio.sandbox;

import java.util.ArrayList;
import java.util.List;

final class RecordingAudit implements SandboxAudit {
    final List<SandboxEvent> events = new ArrayList<>();

    @Override
    public synchronized void record(SandboxEvent event) {
        events.add(event);
    }

    synchronized String text() {
        StringBuilder builder = new StringBuilder();
        for (SandboxEvent event : events) {
            builder.append(event.type()).append(' ').append(event.code()).append(' ').append(event.detail()).append('\n');
        }
        return builder.toString();
    }
}
