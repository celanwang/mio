package org.celanwang.mio.config;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.celanwang.mio.sandbox.CredentialProxy;
import org.celanwang.mio.sandbox.SandboxExecutor;
import org.celanwang.mio.sandbox.SandboxToolInstaller;
import org.celanwang.mio.security.ToolRegistrationGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HashSet;

/**
 * 麦当劳 MCP 接入配置：创建客户端，并将远程工具注册到智能体工具集中。
 */
@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    // 仅在配置项 app.mcp.mcd.enabled 为 true 时启用；token 缺失或连接失败时降级为不连接，
    // 不影响普通聊天。应用关闭时释放连接。
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.mcp.mcd", name = "enabled", havingValue = "true")
    public McpClientWrapper mcdMcpClient(
            @Value("${app.mcp.mcd.url:https://mcp.mcd.cn}") String url,
            @Value("${app.mcp.mcd.token:}") String token,
            SandboxExecutor sandbox,
            CredentialProxy credentials) {
        if (!StringUtils.hasText(token)) {
            log.warn("已启用麦当劳 MCP，但 MCD_MCP_TOKEN 为空，跳过麦当劳连接（点餐工具不可用）。");
            return null;
        }

        // 令牌只经凭据代理在已持有票据时写入请求，不放进静态头。
        try {
            return sandbox.bootstrap("mcd", () -> McpClientBuilder.create("mcd-mcp")
                    .streamableHttpTransport(url)
                    .httpRequestCustomizer((builder, method, endpoint, body, context) ->
                            builder.header("Authorization", "Bearer " + credentials.materializeCurrent("mcd")))
                    .timeout(Duration.ofSeconds(30))
                    .initializationTimeout(Duration.ofSeconds(30))
                    .buildAsync()
                    .block(Duration.ofSeconds(45)));
        } catch (Exception e) {
            log.warn("连接麦当劳 MCP 失败，跳过麦当劳连接（点餐工具不可用）：{}", e.getClass().getSimpleName());
            return null;
        }
    }

    @Bean
    public Toolkit toolkit(ObjectProvider<McpClientWrapper> mcdMcpClient, ToolRegistrationGuard registrationGuard,
                           SandboxExecutor sandbox) {
        Toolkit toolkit = new Toolkit();
        McpClientWrapper client = mcdMcpClient.getIfAvailable();
        if (client != null) {
            // 握手期间持有启动票据；模型调用另发执行票据，静态头里不放令牌。
            try {
                Integer registered = sandbox.bootstrap("mcd", () -> {
                    client.initialize().block(Duration.ofSeconds(45));
                    var tools = client.listTools().block(Duration.ofSeconds(45));
                    if (tools == null || tools.isEmpty()) {
                        return null;
                    }
                    var before = registrationGuard.snapshot(toolkit);
                    toolkit.registerMcpClient(client).block(Duration.ofSeconds(45));
                    registrationGuard.verifyMcpRegistration(toolkit, before, tools.size(), "mcd-mcp");
                    var added = new HashSet<>(toolkit.getToolNames());
                    added.removeAll(before);
                    SandboxToolInstaller.wrap(toolkit, added, sandbox, "mcd");
                    return tools.size();
                });
                if (registered == null) {
                    log.warn("麦当劳 MCP 未返回可用工具，跳过注册（点餐工具不可用）。");
                }
            } catch (IllegalStateException conflict) {
                throw conflict;
            } catch (Exception e) {
                log.warn("初始化麦当劳 MCP 失败，跳过注册（点餐工具不可用）：{}", e.getClass().getSimpleName());
            }
        }
        return toolkit;
    }
}
