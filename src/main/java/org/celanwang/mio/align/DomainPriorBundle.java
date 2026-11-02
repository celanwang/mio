package org.celanwang.mio.align;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Trusted skill aliases and capability-to-skill alignment, with an explicit source-health bit. */
public record DomainPriorBundle(boolean available, Map<String, Set<String>> aliasesBySkill,
                                Map<String, String> capabilitySkills) {
    public DomainPriorBundle {
        if (aliasesBySkill == null || capabilitySkills == null) {
            available = false;
        }
        Map<String, Set<String>> aliases = new TreeMap<>();
        if (aliasesBySkill != null) {
            aliasesBySkill.forEach((skill, values) -> aliases.put(skill, Set.copyOf(values)));
        }
        aliasesBySkill = Map.copyOf(aliases);
        capabilitySkills = capabilitySkills == null ? Map.of() : Map.copyOf(capabilitySkills);
    }

    public static DomainPriorBundle unavailable() {
        return new DomainPriorBundle(false, Map.of(), Map.of());
    }
}
