package org.celanwang.mio.sandbox;

/** Audit record. Must not contain credential material. */
public record SandboxEvent(
        String type,
        String code,
        String sessionId,
        String tool,
        String principal,
        String detail) {
}
