package org.celanwang.mio.align;

import org.celanwang.mio.memory.EntityStore;

import java.util.*;

/** P0: current-text aliases establish candidates; entities only add bounded scores to those candidates. */
public final class DeterministicIntentResolver implements IntentResolver {
    @Override
    public ResolveResult resolve(ResolveRequest request) {
        if (request == null || request.allowModelDisambiguation() || !request.priors().available()
                || !request.prefsOrEmpty().available() || request.userText() == null) {
            return ResolveResult.unavailable();
        }
        Map<String, List<String>> capabilities = new TreeMap<>();
        request.priors().capabilitySkills().forEach((id, skill) -> {
            if (request.knownCapabilityIds().contains(id) && request.knownSkillNames().contains(skill)) {
                capabilities.computeIfAbsent(skill, ignored -> new ArrayList<>()).add(id);
            }
        });
        List<DomainCandidate> candidates = new ArrayList<>();
        Map<String, Set<String>> owners = new TreeMap<>();
        for (var domain : capabilities.entrySet()) {
            String skill = domain.getKey();
            Set<String> aliases = new TreeSet<>(request.priors().aliasesBySkill().getOrDefault(skill, Set.of()));
            aliases.add(skill);
            aliases.addAll(domain.getValue());
            List<String> hits = aliases.stream().filter(a -> mentions(request.userText(), a)).toList();
            if (hits.isEmpty()) continue;
            for (String hit : hits) {
                owners.computeIfAbsent(hit.toLowerCase(Locale.ROOT), ignored -> new TreeSet<>()).add(skill);
            }
            List<String> entityRefs = request.entityHits().stream()
                    .filter(t -> t.validTo() == null && mentions(request.userText(), t.subject()))
                    .filter(t -> aliases.stream().anyMatch(a -> mentions(t.object(), a)))
                    .map(DeterministicIntentResolver::entityRef).distinct().sorted().limit(3).toList();
            candidates.add(new DomainCandidate(skill, domain.getValue().stream().sorted().toList(),
                    100 * hits.size() + 10 * entityRefs.size(), hits, entityRefs));
        }
        candidates.sort(Comparator.comparingInt(DomainCandidate::score).reversed().thenComparing(DomainCandidate::domain));
        Map<String, Object> evidence = new TreeMap<>();
        evidence.put("goalRevision", request.goalRevisionOrZero());
        if (request.goalIdOrNull() != null) evidence.put("goalId", request.goalIdOrNull());
        evidence.put("profileRefs", request.prefsOrEmpty().preferences().stream()
                .map(PersonalContextSnapshot.Preference::id).sorted().toList());
        evidence.put("episodicSnippetCount", request.episodicSnippets().size());
        evidence.put("entityHitCount", request.entityHits().size());
        if (candidates.isEmpty()) {
            // NO_PRIOR_HIT is diagnostic evidence, never an ambiguity code or a request to clarify.
            evidence.put("reasonCode", AmbiguityCode.NO_PRIOR_HIT.name());
            return new ResolveResult("1", ResolveStatus.CLEAR, List.of(), List.of(),
                    "当前消息没有命中已注册领域先验。", EffectHint.NONE, evidence, null);
        }
        if (candidates.size() == 1) {
            return new ResolveResult("1", ResolveStatus.CLEAR, candidates, List.of(),
                    "当前消息命中唯一已注册领域；仅作本轮规划参考。", EffectHint.NONE, evidence, null);
        }
        List<AmbiguityCode> codes = new ArrayList<>();
        codes.add(AmbiguityCode.MULTI_DOMAIN);
        if (owners.values().stream().anyMatch(domains -> domains.size() > 1)) codes.add(AmbiguityCode.ALIAS_COLLISION);
        return new ResolveResult("1", ResolveStatus.AMBIGUOUS, candidates, codes,
                "当前消息命中多个领域，建议先在主对话确认用户要处理的领域及顺序。",
                EffectHint.SUGGEST_CLARIFY, evidence, null);
    }

    private static String entityRef(EntityStore.Triple triple) {
        return triple.subject() + "/" + triple.predicate() + "/" + triple.object() + "@" + triple.sourceTs();
    }

    /** CJK aliases may occur within a sentence; Latin identifiers must not match inside another word. */
    static boolean mentions(String text, String alias) {
        if (text == null || alias == null || alias.isBlank()) return false;
        String haystack = text.toLowerCase(Locale.ROOT), needle = alias.toLowerCase(Locale.ROOT);
        for (int from = 0, at; (at = haystack.indexOf(needle, from)) >= 0; from = at + 1) {
            int end = at + needle.length();
            boolean left = !identifierChar(needle.charAt(0)) || at == 0 || !identifierChar(haystack.charAt(at - 1));
            boolean right = !identifierChar(needle.charAt(needle.length() - 1)) || end == haystack.length()
                    || !identifierChar(haystack.charAt(end));
            if (left && right) return true;
        }
        return false;
    }

    private static boolean identifierChar(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-';
    }
}
