package org.celanwang.mio.sandbox;

/** Where the admitted operation runs. Higher ordinal is a stricter boundary. */
public enum RuntimeKind {
    /** AgentScope / MCP client inside this JVM. */
    JVM,
    /** Local helper process, such as the Tencent ride Python bridge. */
    SUBPROCESS,
    /** Optional later boundary (gVisor / container). Scaffold only. */
    CONTAINER;

    public boolean stricterThan(RuntimeKind other) {
        return ordinal() > other.ordinal();
    }
}
