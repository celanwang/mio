package org.celanwang.mio.align;

/** Independent of B10 ConflictCode. GOAL_DRIFT is reserved; P0 never emits it. */
public enum AmbiguityCode {
    MULTI_DOMAIN, ALIAS_COLLISION, ENTITY_REF, SLOT_MISSING,
    GOAL_DRIFT, NO_PRIOR_HIT, OTHER
}
