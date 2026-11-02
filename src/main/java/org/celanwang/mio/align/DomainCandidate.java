package org.celanwang.mio.align;

import java.util.List;

/** A skill domain supported by this round's text, with aligned capability ids and read-only scores. */
public record DomainCandidate(String domain, List<String> capabilityIds, int score,
                              List<String> matchedAliases, List<String> entityRefs) {
    public DomainCandidate {
        capabilityIds = List.copyOf(capabilityIds);
        matchedAliases = List.copyOf(matchedAliases);
        entityRefs = List.copyOf(entityRefs);
    }
}
