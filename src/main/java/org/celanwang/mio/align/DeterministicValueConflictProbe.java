package org.celanwang.mio.align;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P0 rules over preferences, goal hard constraints, and explicit skill redlines.
 * No model call. A miss is CLEAR; a failed input is not this class's problem.
 */
@Component
public final class DeterministicValueConflictProbe implements ValueConflictProbe {
    private static final Pattern DIETARY = Pattern.compile("(?:忌口|不吃|过敏|不能吃|不要吃|DIETARY)\\s*[:：]?\\s*([^。；;\\n]+)");
    private static final Pattern BUDGET = Pattern.compile(
            "(?i)(?:预算|budget|金额上限|花费上限)\\s*(?:不超过|不高于|<=|≤|上限为|最多|至多|at most|=|:|：)?\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern BUDGET_FIELD = Pattern.compile(
            "(?i)^(budget|amount|total|price|payAmount|payable|maxBudget|费用|金额|应付|总价)\\s*(?:=|LTE|<=|≤|不超过)\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern PRIVACY = Pattern.compile("隐私|不要泄露|不得泄露|不公开|PRIVACY");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern STALE_ADDRESS = Pattern.compile("地址已过期|地址失效|地址待更新|ADDRESS_STALE");
    private static final Pattern WINDOW = Pattern.compile("(?:时间窗口|TIME_WINDOW)\\s*[:：]?\\s*(\\d{1,2}:\\d{2})\\s*[-~到至]\\s*(\\d{1,2}:\\d{2})");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern BAN_TOOL = Pattern.compile("(?:禁止|不得)(?:调用|使用|执行)\\s*`?([A-Za-z0-9_-]+)`?");
    private static final Pattern BAN_ORDER = Pattern.compile("不得下单|禁止下单");
    private static final Pattern OTHER = Pattern.compile("(?m)^OTHER[:：]\\s*(\\S+)");
    private static final Set<String> ORDER_TOOLS = Set.of(
            "create-order", "mall-create-order", "party-order-create", "tencent_ride_create_order");
    private static final Set<String> MONEY_KEYS = Set.of(
            "amount", "total", "totalprice", "payamount", "price", "payable", "fee", "money", "budget",
            "金额", "应付", "总价", "价格");
    private static final Set<String> ADDRESS_KEYS = Set.of("address", "addr", "location", "地址");
    private static final Set<String> TIME_KEYS = Set.of("time", "arrivetime", "expecttime", "eta", "window", "时间");

    @Override
    public ProbeResult probe(ProbeRequest request) {
        if (request == null || request.prefs() == null || !request.prefs().available()) {
            return ProbeResult.unavailable("个人偏好或目标约束暂时不可用，不能视为没有冲突。");
        }
        Map<ConflictCode, String> hits = new LinkedHashMap<>();
        Set<String> profileIds = new LinkedHashSet<>();
        Set<String> constraintIds = new LinkedHashSet<>();
        List<String> matchedRedlines = new ArrayList<>();
        String flat = flatten(request.input());
        if (!request.readOnlyDeclared()) {
            dietary(request, flat, hits, profileIds, constraintIds);
            budget(request, hits, profileIds, constraintIds);
        }
        privacy(request, flat, hits, profileIds, constraintIds);
        address(request, hits, profileIds, constraintIds);
        timeWindow(request, hits, constraintIds);
        skill(request, hits, matchedRedlines);
        goalBan(request, hits, constraintIds);
        exceptionIgnored(request, flat, hits);
        other(request, flat, hits, profileIds, constraintIds);
        if (hits.isEmpty()) {
            return ProbeResult.clear("未发现与画像偏好、目标硬条件或技能红线的冲突。", Map.of());
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (!profileIds.isEmpty()) {
            evidence.put("profileIds", List.copyOf(profileIds));
        }
        if (!constraintIds.isEmpty()) {
            evidence.put("constraintIds", List.copyOf(constraintIds));
        }
        if (!matchedRedlines.isEmpty()) {
            evidence.put("skillRedlines", List.copyOf(matchedRedlines));
        }
        List<ConflictCode> codes = hits.keySet().stream().sorted().toList();
        return ProbeResult.conflict(codes, String.join("；", hits.values()), evidence);
    }

    private static void dietary(ProbeRequest request, String flat, Map<ConflictCode, String> hits,
                                Set<String> profileIds, Set<String> constraintIds) {
        List<String> foods = new ArrayList<>();
        Set<String> dietProfiles = new LinkedHashSet<>();
        Set<String> dietConstraints = new LinkedHashSet<>();
        for (PersonalContextSnapshot.Preference preference : request.prefs().preferences()) {
            if (collectFoods(preference.content(), foods) || collectFoods(preference.title(), foods)) {
                dietProfiles.add(preference.id());
            }
        }
        for (HardConstraint constraint : request.hardConstraints()) {
            if (collectFoods(constraint.text(), foods)) {
                dietConstraints.add(constraint.id());
            }
        }
        List<String> present = foods.stream().filter(food -> flat.contains(food)).distinct().toList();
        if (!present.isEmpty()) {
            profileIds.addAll(dietProfiles);
            constraintIds.addAll(dietConstraints);
            hits.put(ConflictCode.DIETARY, "忌口「" + String.join("、", present) + "」与 " + request.toolName() + " 的内容冲突，建议先询问用户。");
        }
    }

    private static boolean collectFoods(String text, List<String> foods) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean found = false;
        Matcher matcher = DIETARY.matcher(text);
        while (matcher.find()) {
            for (String part : matcher.group(1).split("[、,，/]|\\s+和\\s+")) {
                String token = part.replaceAll("[\\s。；;，,]", "");
                if (token.length() >= 1) {
                    foods.add(token);
                    found = true;
                }
            }
        }
        return found;
    }

    private static void budget(ProbeRequest request, Map<ConflictCode, String> hits,
                               Set<String> profileIds, Set<String> constraintIds) {
        Double cap = null;
        boolean fen = false;
        String source = null;
        for (HardConstraint constraint : request.hardConstraints()) {
            Double found = capOf(constraint.text());
            if (found != null && (cap == null || found < cap)) {
                cap = found;
                fen = constraint.text().contains("分") && !constraint.text().contains("元");
                source = constraint.id();
            }
        }
        for (PersonalContextSnapshot.Preference preference : request.prefs().preferences()) {
            Double found = capOf(preference.content());
            if (found != null && (cap == null || found < cap)) {
                cap = found;
                fen = preference.content().contains("分") && !preference.content().contains("元");
                source = preference.id();
            }
        }
        if (cap == null) {
            return;
        }
        Double spend = maxSpend(request.input(), !fen, 0);
        String cited = source;
        if (spend != null && spend > cap + 1e-9) {
            if (cited != null && request.hardConstraints().stream().anyMatch(item -> item.id().equals(cited))) {
                constraintIds.add(cited);
            } else if (cited != null) {
                profileIds.add(cited);
            }
            hits.put(ConflictCode.BUDGET, "金额 " + trim(spend) + " 超过预算上限 " + trim(cap) + "，建议先询问用户。");
        }
    }

    private static Double capOf(String text) {
        if (text == null) {
            return null;
        }
        Matcher field = BUDGET_FIELD.matcher(text.trim());
        if (field.matches()) {
            return Double.valueOf(field.group(2));
        }
        Matcher prose = BUDGET.matcher(text);
        if (prose.find()) {
            return Double.valueOf(prose.group(1));
        }
        return null;
    }

    private static Double maxSpend(Object node, boolean yuanFromFen, int depth) {
        if (node == null || depth > 8) {
            return null;
        }
        Double best = null;
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                Double nested = maxSpend(entry.getValue(), yuanFromFen, depth + 1);
                best = higher(best, nested);
                if (MONEY_KEYS.contains(key) || key.endsWith("fen")) {
                    Double number = number(entry.getValue());
                    if (number != null && (key.endsWith("fen") || "amountfen".equals(key)) && yuanFromFen) {
                        number = number / 100.0;
                    }
                    best = higher(best, number);
                }
            }
        } else if (node instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                best = higher(best, maxSpend(item, yuanFromFen, depth + 1));
            }
        }
        return best;
    }

    private static void privacy(ProbeRequest request, String flat, Map<ConflictCode, String> hits,
                                Set<String> profileIds, Set<String> constraintIds) {
        boolean sensitive = PHONE.matcher(flat).find() || EMAIL.matcher(flat).find();
        if (!sensitive) {
            return;
        }
        boolean marked = false;
        for (PersonalContextSnapshot.Preference preference : request.prefs().preferences()) {
            if (PRIVACY.matcher(preference.content() + preference.title()).find()) {
                profileIds.add(preference.id());
                marked = true;
            }
        }
        for (HardConstraint constraint : request.hardConstraints()) {
            if (PRIVACY.matcher(constraint.text()).find()) {
                constraintIds.add(constraint.id());
                marked = true;
            }
        }
        if (marked) {
            hits.put(ConflictCode.PRIVACY, "调用携带了画像标记为不要泄露的联系方式，建议先询问用户。");
        }
    }

    private static void address(ProbeRequest request, Map<ConflictCode, String> hits,
                                Set<String> profileIds, Set<String> constraintIds) {
        if (!hasAddress(request.input(), 0)) {
            return;
        }
        boolean stale = false;
        for (PersonalContextSnapshot.Preference preference : request.prefs().preferences()) {
            if (STALE_ADDRESS.matcher(preference.content() + preference.title()).find()) {
                profileIds.add(preference.id());
                stale = true;
            }
        }
        for (HardConstraint constraint : request.hardConstraints()) {
            if (STALE_ADDRESS.matcher(constraint.text()).find()) {
                constraintIds.add(constraint.id());
                stale = true;
            }
        }
        if (stale) {
            hits.put(ConflictCode.ADDRESS_STALE, "画像或目标标明地址已过期，这次调用仍带了地址，建议先询问用户。");
        }
    }

    private static boolean hasAddress(Object node, int depth) {
        if (!(node instanceof Map<?, ?> map) || depth > 8) {
            return false;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if (ADDRESS_KEYS.contains(key) && entry.getValue() != null && !entry.getValue().toString().isBlank()) {
                return true;
            }
            if (hasAddress(entry.getValue(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static void timeWindow(ProbeRequest request, Map<ConflictCode, String> hits, Set<String> constraintIds) {
        for (HardConstraint constraint : request.hardConstraints()) {
            Matcher window = WINDOW.matcher(constraint.text() == null ? "" : constraint.text());
            if (!window.find()) {
                continue;
            }
            int start = minutes(window.group(1));
            int end = minutes(window.group(2));
            Integer actual = clock(request.input(), 0);
            if (actual != null && !inside(actual, start, end)) {
                constraintIds.add(constraint.id());
                hits.put(ConflictCode.TIME_WINDOW, "时间 " + window.group(0) + " 之外，建议先询问用户。");
                return;
            }
        }
    }

    private static Integer clock(Object node, int depth) {
        if (!(node instanceof Map<?, ?> map) || depth > 8) {
            return null;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if (TIME_KEYS.contains(key) && entry.getValue() != null) {
                Matcher matcher = CLOCK.matcher(entry.getValue().toString());
                if (matcher.find()) {
                    return minutes(matcher.group(1) + ":" + matcher.group(2));
                }
            }
            Integer nested = clock(entry.getValue(), depth + 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static boolean inside(int minute, int start, int end) {
        if (start <= end) {
            return minute >= start && minute <= end;
        }
        return minute >= start || minute <= end;
    }

    private static int minutes(String clock) {
        String[] parts = clock.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private static void skill(ProbeRequest request, Map<ConflictCode, String> hits, List<String> matched) {
        for (String redline : request.skillRedlines()) {
            if (redline == null) {
                continue;
            }
            Matcher ban = BAN_TOOL.matcher(redline);
            while (ban.find()) {
                if (ban.group(1).equals(request.toolName()) || ban.group(1).equals(command(request))) {
                    matched.add(redline);
                    hits.put(ConflictCode.SKILL_REDLINE, "技能红线禁止调用 " + ban.group(1) + "，建议先询问用户。");
                    return;
                }
            }
        }
    }

    private static void goalBan(ProbeRequest request, Map<ConflictCode, String> hits, Set<String> constraintIds) {
        for (HardConstraint constraint : request.hardConstraints()) {
            String text = constraint.text() == null ? "" : constraint.text();
            Matcher ban = BAN_TOOL.matcher(text);
            boolean hit = false;
            while (ban.find()) {
                if (ban.group(1).equals(request.toolName()) || ban.group(1).equals(command(request))) {
                    hit = true;
                }
            }
            if (!hit && BAN_ORDER.matcher(text).find() && (ORDER_TOOLS.contains(request.toolName()) || "create-order".equals(command(request)))) {
                hit = true;
            }
            if (hit) {
                constraintIds.add(constraint.id());
                hits.put(ConflictCode.GOAL_HARD_CONSTRAINT, "目标硬条件禁止这次 " + request.toolName() + "，建议先询问用户。");
                return;
            }
        }
    }

    private static void exceptionIgnored(ProbeRequest request, String flat, Map<ConflictCode, String> hits) {
        for (String id : request.prefs().excludedProfileIds()) {
            if (id != null && id.length() >= 4 && containsToken(flat, id)) {
                hits.put(ConflictCode.PROFILE_EXCEPTION_IGNORED, "调用引用了本目标已排除的画像 " + id + "，建议先询问用户。");
                return;
            }
        }
    }

    private static void other(ProbeRequest request, String flat, Map<ConflictCode, String> hits,
                              Set<String> profileIds, Set<String> constraintIds) {
        for (HardConstraint constraint : request.hardConstraints()) {
            Matcher matcher = OTHER.matcher(constraint.text() == null ? "" : constraint.text());
            if (matcher.find() && flat.contains(matcher.group(1))) {
                constraintIds.add(constraint.id());
                hits.putIfAbsent(ConflictCode.OTHER, "目标标记了 OTHER:" + matcher.group(1) + "，建议先询问用户。");
            }
        }
        for (PersonalContextSnapshot.Preference preference : request.prefs().preferences()) {
            Matcher matcher = OTHER.matcher(preference.content());
            if (matcher.find() && flat.contains(matcher.group(1))) {
                profileIds.add(preference.id());
                hits.putIfAbsent(ConflictCode.OTHER, "画像标记了 OTHER:" + matcher.group(1) + "，建议先询问用户。");
            }
        }
    }

    private static boolean containsToken(String flat, String token) {
        int from = 0;
        while (from <= flat.length() - token.length()) {
            int at = flat.indexOf(token, from);
            if (at < 0) {
                return false;
            }
            boolean left = at == 0 || !Character.isLetterOrDigit(flat.charAt(at - 1));
            int end = at + token.length();
            boolean right = end >= flat.length() || !Character.isLetterOrDigit(flat.charAt(end));
            if (left && right) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static String command(ProbeRequest request) {
        Object command = request.input().get("command");
        return command instanceof String text ? text : "";
    }

    private static String flatten(Object node) {
        StringBuilder out = new StringBuilder();
        append(out, node, 0);
        return out.toString();
    }

    private static void append(StringBuilder out, Object node, int depth) {
        if (node == null || depth > 8) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append('\n').append(entry.getKey());
                append(out, entry.getValue(), depth + 1);
            }
            return;
        }
        if (node instanceof Iterable<?> iterable && !(node instanceof String)) {
            for (Object item : iterable) {
                append(out, item, depth + 1);
            }
            return;
        }
        out.append('\n').append(node);
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            return Double.isFinite(parsed) ? parsed : null;
        }
        if (value instanceof String text && text.matches("\\d+(?:\\.\\d+)?")) {
            return Double.valueOf(text);
        }
        return null;
    }

    private static Double higher(Double left, Double right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    private static String trim(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
