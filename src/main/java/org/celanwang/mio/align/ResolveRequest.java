package org.celanwang.mio.align;

import org.celanwang.mio.memory.EntityStore;

import java.util.List;
import java.util.Set;

public record ResolveRequest(
        String sessionId,
        String messageIdOrNull,
        String userText,
        String goalIdOrNull,
        long goalRevisionOrZero,
        String trustedIntentExcerpt,
        Set<String> knownCapabilityIds,
        Set<String> knownSkillNames,
        DomainPriorBundle priors,
        List<EntityStore.Triple> entityHits,
        List<String> episodicSnippets,
        PersonalContextSnapshot prefsOrEmpty,
        boolean allowModelDisambiguation
) {
    public ResolveRequest {
        if (priors == null || entityHits == null || knownCapabilityIds == null || knownSkillNames == null) {
            priors = DomainPriorBundle.unavailable();
        }
        knownCapabilityIds = knownCapabilityIds == null ? Set.of() : Set.copyOf(knownCapabilityIds);
        knownSkillNames = knownSkillNames == null ? Set.of() : Set.copyOf(knownSkillNames);
        entityHits = entityHits == null ? List.of() : List.copyOf(entityHits);
        episodicSnippets = episodicSnippets == null ? List.of() : List.copyOf(episodicSnippets);
        prefsOrEmpty = prefsOrEmpty == null ? PersonalContextSnapshot.empty() : prefsOrEmpty;
    }

    public static ResolveRequest unavailable(String sessionId, String userText) {
        return new ResolveRequest(sessionId, null, userText, null, 0, "", Set.of(), Set.of(),
                DomainPriorBundle.unavailable(), List.of(), List.of(), PersonalContextSnapshot.unavailable(), false);
    }
}
