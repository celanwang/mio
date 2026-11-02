package org.celanwang.mio.sandbox;

/** The required sandbox could not be started. Callers must not fall back to bare host execution. */
public class SandboxUnavailableException extends RuntimeException {
    private final String code;

    public SandboxUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public SandboxUnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
