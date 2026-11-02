package org.celanwang.mio.align;

/** A goal hard constraint. Empty when the session has no goal. */
public record HardConstraint(String id, String text) {
    public HardConstraint {
        id = id == null ? "" : id;
        text = text == null ? "" : text;
    }
}
