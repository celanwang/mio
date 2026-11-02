package org.celanwang.mio.align;

/** Pure observation. Implementations must not allow, deny, or issue tickets. */
public interface ValueConflictProbe {
    /**
     * Prefer a pure function. I/O failure is the caller's job to turn into UNAVAILABLE,
     * and UNAVAILABLE must suggest ASK rather than clear.
     */
    ProbeResult probe(ProbeRequest request);
}
