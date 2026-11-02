package org.celanwang.mio.sandbox;

/** Admission or confinement refused. The operation must not touch the host or the network. */
public class SandboxRefusedException extends RuntimeException {
    private final String code;

    public SandboxRefusedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
