package org.celanwang.mio.sandbox;

/** Caller identity for workspace and row checks. The product default remains a single local owner. */
public record PrincipalId(String value) {
    public static final PrincipalId LOCAL_OWNER = new PrincipalId("local-owner");

    public PrincipalId {
        if (value == null || !value.matches("[a-z0-9][a-z0-9\\-]{0,62}")) {
            throw new IllegalArgumentException("主体标识无效");
        }
    }

    public static PrincipalId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return LOCAL_OWNER;
        }
        return new PrincipalId(raw.trim());
    }

    public boolean isLocalOwner() {
        return LOCAL_OWNER.value.equals(value);
    }
}
