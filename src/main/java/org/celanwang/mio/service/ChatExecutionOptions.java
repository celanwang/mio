package org.celanwang.mio.service;

/** Delegated instructions are never promoted to user memory or used as the authorization source. */
public record ChatExecutionOptions(String trustedIntent, boolean recordMemory, Runnable beforeReview, String alignSessionId) {
    public static final ChatExecutionOptions USER_CONVERSATION = new ChatExecutionOptions(null, true, () -> {}, null);

    public ChatExecutionOptions {
        java.util.Objects.requireNonNull(beforeReview);
    }

    public ChatExecutionOptions(String trustedIntent, boolean recordMemory, Runnable beforeReview) {
        this(trustedIntent, recordMemory, beforeReview, null);
    }
}
