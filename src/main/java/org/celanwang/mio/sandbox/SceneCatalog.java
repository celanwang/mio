package org.celanwang.mio.sandbox;

import org.celanwang.mio.config.ToolPolicies;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Maps an already-known tool call onto the matrix. It does not allow or deny the call.
 * Ride read commands stay aligned with {@code GuardedDomainTool#readOnly}.
 */
@Component
public class SceneCatalog {

    /** Commands that only read or draft a ride. Creating or cancelling an order is not in this set. */
    public static final Set<String> RIDE_READ_COMMANDS = Set.of(
            "preflight", "get-resident-city", "addr-keys", "addr-get-value",
            "short-cut-keys", "short-cut-get-value", "poi-search", "select-poi", "estimate-price",
            "query-ongoing-order", "query-order", "check-cancel-fee", "query-driver-location",
            "build-static-map-url", "addr-sync-to-state");

    public Classification classify(String tool, Map<String, Object> input, boolean declaredReadOnly, String credentialHint) {
        Map<String, Object> args = input == null ? Map.of() : input;
        String credential = credentialHint != null ? credentialHint : inferCredential(tool);
        RuntimeKind runtime = runtimeOf(tool, credential);
        RiskClass risk = riskOf(tool, args, declaredReadOnly, credential);
        return new Classification(risk, runtime, credential);
    }

    private static RuntimeKind runtimeOf(String tool, String credential) {
        if ("tencent-ride".equals(credential) || (tool != null && tool.startsWith("tencent_ride"))) {
            return RuntimeKind.SUBPROCESS;
        }
        return RuntimeKind.JVM;
    }

    private static RiskClass riskOf(String tool, Map<String, Object> input, boolean declaredReadOnly, String credential) {
        if (tool == null || tool.isBlank()) {
            return RiskClass.IRREVERSIBLE;
        }
        if ("tencent_ride_create_order".equals(tool) || "create-order".equals(input.get("command"))
                && tool.startsWith("tencent_ride")) {
            return RiskClass.IRREVERSIBLE;
        }
        if ("tencent_ride".equals(tool)) {
            Object command = input.get("command");
            if (RIDE_READ_COMMANDS.contains(command)) {
                return RiskClass.READONLY;
            }
            if ("cancel-order".equals(command) || command == null || !org.celanwang.mio.tool.TencentRideTool.isSupported(command)) {
                return RiskClass.IRREVERSIBLE;
            }
            return RiskClass.REVERSIBLE;
        }
        if (ToolPolicies.HUMAN_ONLY.contains(tool)) {
            return RiskClass.IRREVERSIBLE;
        }
        if (ToolPolicies.READ_ONLY.contains(tool)) {
            return RiskClass.READONLY;
        }
        if ("weibo".equals(credential) || tool.startsWith("weibo") || "prepare_weibo".equals(tool)) {
            return weiboRisk(tool, declaredReadOnly);
        }
        if (ToolPolicies.JEV_REVIEWABLE.contains(tool)) {
            return RiskClass.REVERSIBLE;
        }
        if (declaredReadOnly) {
            return RiskClass.READONLY;
        }
        // Unknown writes take the stricter column. This is not a new allowlist.
        return RiskClass.IRREVERSIBLE;
    }

    /**
     * Weibo reads stay readonly. Public posts and deletes are irreversible; comments and likes
     * can be undone and stay on the reversible column. The permission chain still asks first.
     */
    static RiskClass weiboRisk(String tool, boolean declaredReadOnly) {
        String name = tool.toLowerCase();
        if (containsToken(name, "delete", "destroy", "publish", "repost", "forward", "share")
                || containsToken(name, "post", "create")) {
            return RiskClass.IRREVERSIBLE;
        }
        if (containsToken(name, "comment", "reply", "like", "unlike", "favorite", "update", "edit")) {
            return RiskClass.REVERSIBLE;
        }
        if (declaredReadOnly || containsToken(name, "search", "get", "list", "query", "show", "timeline", "mention")) {
            return RiskClass.READONLY;
        }
        return declaredReadOnly ? RiskClass.READONLY : RiskClass.IRREVERSIBLE;
    }

    private static boolean containsToken(String name, String... tokens) {
        for (String token : tokens) {
            if (name.equals(token) || name.contains("_" + token) || name.contains(token + "_") || name.contains("-" + token)
                    || name.contains(token + "-")) {
                return true;
            }
        }
        return false;
    }

    private static String inferCredential(String tool) {
        if (tool == null) {
            return null;
        }
        if (tool.startsWith("tencent_ride")) {
            return "tencent-ride";
        }
        if ("prepare_weibo".equals(tool) || tool.startsWith("weibo")) {
            return "weibo";
        }
        if (ToolPolicies.READ_ONLY.contains(tool) || ToolPolicies.HUMAN_ONLY.contains(tool)
                || ToolPolicies.JEV_REVIEWABLE.contains(tool)) {
            if (!"prepare_weibo".equals(tool) && !"tencent_ride".equals(tool)) {
                return "mcd";
            }
        }
        return null;
    }
}
