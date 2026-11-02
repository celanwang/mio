package org.celanwang.mio.align;

/** Planning evidence only. Missing or failed inputs resolve to UNAVAILABLE, never to a guessed domain. */
public interface IntentResolver {
    /** B8 namespace preserves B10 EffectHint's existing NONE | SUGGEST_ASK contract. */
    enum EffectHint { NONE, SUGGEST_CLARIFY }

    ResolveResult resolve(ResolveRequest request);
}
