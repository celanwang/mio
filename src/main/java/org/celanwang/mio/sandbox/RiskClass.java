package org.celanwang.mio.sandbox;

/** How hard it is to undo the effect. Higher ordinal is stricter. */
public enum RiskClass {
    READONLY,
    REVERSIBLE,
    IRREVERSIBLE;

    public boolean stricterThan(RiskClass other) {
        return ordinal() > other.ordinal();
    }
}
