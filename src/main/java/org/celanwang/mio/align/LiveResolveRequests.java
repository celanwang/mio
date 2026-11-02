package org.celanwang.mio.align;

import org.celanwang.mio.config.DomainAliasRegistry;
import org.celanwang.mio.memory.EntityStore;
import org.celanwang.mio.orchestration.AgentRegistry;
import org.celanwang.mio.orchestration.GoalRecord;

import java.util.*;
import java.util.regex.Pattern;

/** Read-only, bounded lookup after user/preference capture. No episodic/vector/model retrieval in P0. */
public final class LiveResolveRequests {
    private static final Pattern ENTITY_TOKEN = Pattern.compile("[\\p{L}\\p{N}_-]{1,80}");
    private final AgentRegistry registry;
    private final DomainAliasRegistry aliases;
    private final EntityStore entities;

    public LiveResolveRequests(AgentRegistry registry, DomainAliasRegistry aliases, EntityStore entities) {
        this.registry = registry;
        this.aliases = aliases;
        this.entities = entities;
    }

    public ResolveRequest forMessage(String session, String text, GoalRecord goal, PersonalContextSnapshot prefs) {
        try {
            if (aliases == null || !aliases.available() || entities == null || goal == null || text == null
                    || prefs != null && !prefs.available()) {
                return ResolveRequest.unavailable(session, text);
            }
            Map<String, String> aligned = new TreeMap<>();
            Map<String, Set<String>> bySkill = new TreeMap<>();
            for (var capability : registry.list()) {
                if (!aliases.skillNames().contains(capability.skill())) return ResolveRequest.unavailable(session, text);
                aligned.put(capability.id(), capability.skill());
                bySkill.put(capability.skill(), aliases.aliasesOf(capability.skill()));
            }
            // Query only explicit current-message keys, never enumerate the entity store or infer a domain from it.
            Set<String> keys = new LinkedHashSet<>();
            if (text.length() <= 200 && !text.isBlank()) keys.add(text);
            bySkill.values().stream().flatMap(Set::stream).sorted()
                    .filter(a -> DeterministicIntentResolver.mentions(text, a)).forEach(keys::add);
            var tokens = ENTITY_TOKEN.matcher(text);
            while (keys.size() < 12 && tokens.find()) keys.add(tokens.group());
            Set<EntityStore.Triple> hits = new LinkedHashSet<>();
            for (String key : keys.stream().limit(12).toList()) {
                var related = entities.relatedSnapshot(key);
                if (related == null || !related.available()) return ResolveRequest.unavailable(session, text);
                related.triples().stream().limit(100).forEach(hits::add);
            }
            String intent = goal.trustedIntent();
            return new ResolveRequest(session, null, text, goal.id(), goal.revision(),
                    intent.substring(0, Math.min(intent.length(), 2000)), aligned.keySet(), bySkill.keySet(),
                    new DomainPriorBundle(true, bySkill, aligned), List.copyOf(hits), List.of(), prefs, false);
        } catch (RuntimeException failure) {
            return ResolveRequest.unavailable(session, text);
        }
    }
}
