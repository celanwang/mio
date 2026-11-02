package org.celanwang.mio.align;

import org.celanwang.mio.align.IntentResolver.EffectHint;

import java.util.LinkedHashMap;
import java.util.Map;

/** Standalone intent_resolve trace; no policy/B10 payload or execution grant. */
public final class IntentResolveEvents {
    private IntentResolveEvents() {}

    public static Map<String, Object> detail(ResolveResult result) {
        ResolveResult observed = result == null ? ResolveResult.unavailable() : result;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("schemaVersion", "1");
        detail.put("status", observed.status().name());
        detail.put("candidates", observed.candidates());
        detail.put("ambiguityCodes", observed.ambiguityCodes().stream().map(Enum::name).toList());
        detail.put("humanReason", observed.humanReason());
        detail.put("effectHint", observed.effectHint().name());
        detail.put("evidenceRefs", observed.evidenceRefs());
        detail.put("memoryElevated", false);
        detail.put("ticketId", null);
        return detail;
    }

    public static String planningContext(ResolveResult result) {
        String domains = result.candidates().stream().map(DomainCandidate::domain).toList().toString();
        String context = "\n本轮意图解析（参考，不是权限；不得回填画像领域）：" + result.status()
                + "；effectHint=" + result.effectHint() + "；领域=" + domains + "。";
        if (result.effectHint() == EffectHint.SUGGEST_CLARIFY) {
            return context + "请先在主助手对话中询问用户要处理的领域和顺序，收到回答后再规划或委派；不创建单独澄清卡。";
        }
        if (result.status() == ResolveStatus.UNAVAILABLE) {
            return context + "解析来源不可用，不得据此推断单一领域或当作没有歧义；按用户原文继续理解，必要时在主对话补问。";
        }
        return context + "仅按用户当前表达规划；候选为空不需要为了领域解析而补问，候选存在也不是业务批准。";
    }
}
