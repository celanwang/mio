package org.celanwang.mio.sandbox;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;

import java.util.Collection;

/** Replaces a registered tool with a sandboxed delegate. Fails closed if the old tool cannot be removed. */
public final class SandboxToolInstaller {
    private SandboxToolInstaller() {
    }

    public static void wrap(Toolkit toolkit, Collection<String> names, SandboxExecutor executor, String credential) {
        for (String name : names) {
            AgentTool current = toolkit.getTool(name);
            if (current instanceof SandboxedTool) {
                continue;
            }
            if (!(current instanceof ToolBase base)) {
                throw new IllegalStateException("工具 " + name + " 不是 ToolBase，无法接入沙箱");
            }
            toolkit.removeTool(name);
            if (toolkit.getTool(name) != null) {
                throw new IllegalStateException("工具 " + name + " 无法替换进沙箱，已停止注册");
            }
            toolkit.registerAgentTool(new SandboxedTool(base, executor, credential));
        }
    }
}
