package org.celanwang.mio.align;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit bullets under each skill's 红线 heading. A read failure stays unavailable. */
@Component
public final class SkillRedlineCatalog {
    private final boolean available;
    private final Map<String, List<String>> bySkill;

    public SkillRedlineCatalog(@Value("${app.agent.skills-dir:./skills}") String skillsDir) {
        Map<String, List<String>> loaded = new LinkedHashMap<>();
        boolean ok = true;
        Path root = expand(skillsDir);
        if (!Files.isDirectory(root)) {
            ok = false;
        } else {
            try (var stream = Files.list(root)) {
                for (Path dir : stream.filter(Files::isDirectory).toList()) {
                    Path skill = dir.resolve("SKILL.md");
                    if (!Files.exists(skill)) {
                        continue;
                    }
                    try {
                        loaded.put(dir.getFileName().toString(), bullets(Files.readString(skill)));
                    } catch (IOException failure) {
                        ok = false;
                    }
                }
            } catch (IOException failure) {
                ok = false;
            }
        }
        this.available = ok;
        this.bySkill = Map.copyOf(loaded);
    }

    SkillRedlineCatalog(boolean available, Map<String, List<String>> bySkill) {
        this.available = available;
        this.bySkill = bySkill == null ? Map.of() : Map.copyOf(bySkill);
    }

    public boolean available() {
        return available;
    }

    public List<String> forDomain(String domain) {
        if (!available) {
            return List.of();
        }
        if (domain == null || domain.isBlank()) {
            return bySkill.values().stream().flatMap(List::stream).toList();
        }
        return bySkill.getOrDefault(domain, List.of());
    }

    static List<String> bullets(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<String> bullets = new ArrayList<>();
        StringBuilder current = null;
        boolean inside = false;
        for (String raw : markdown.split("\\R")) {
            String line = raw.stripTrailing();
            if (line.startsWith("## ")) {
                if (inside) {
                    break;
                }
                inside = line.substring(3).trim().startsWith("红线");
                continue;
            }
            if (!inside) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                if (current != null) {
                    bullets.add(current.toString());
                }
                current = new StringBuilder(trimmed.substring(2).trim());
            } else if (current != null && !trimmed.isEmpty()) {
                current.append(' ').append(trimmed);
            }
        }
        if (current != null) {
            bullets.add(current.toString());
        }
        return List.copyOf(bullets);
    }

    private static Path expand(String path) {
        if (path != null && path.startsWith("~")) {
            return Path.of(System.getProperty("user.home") + path.substring(1));
        }
        return Path.of(path == null ? "./skills" : path);
    }
}
