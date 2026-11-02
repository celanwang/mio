package org.celanwang.mio.service;

import org.celanwang.mio.orchestration.CapabilityAvailability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.config.TencentRideProperties;
import org.celanwang.mio.sandbox.Classification;
import org.celanwang.mio.sandbox.ExecutionTicket;
import org.celanwang.mio.sandbox.MountPolicy;
import org.celanwang.mio.sandbox.ProcessLaunch;
import org.celanwang.mio.sandbox.ProcessSandbox;
import org.celanwang.mio.sandbox.RiskClass;
import org.celanwang.mio.sandbox.SandboxAudit;
import org.celanwang.mio.sandbox.SandboxExecutor;
import org.celanwang.mio.sandbox.SandboxRefusedException;
import org.celanwang.mio.sandbox.SandboxScope;
import org.celanwang.mio.sandbox.SandboxUnavailableException;
import org.celanwang.mio.sandbox.SceneCatalog;
import org.celanwang.mio.sandbox.WorkspaceLayout;
import org.celanwang.mio.sandbox.CredentialProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class TencentRideService {
    private static final Logger log = LoggerFactory.getLogger(TencentRideService.class);
    private final TencentRideProperties properties;
    private final ObjectMapper json;
    private final Path skillsDir;
    private final SandboxExecutor executor;
    private final ProcessSandbox processSandbox;
    private final CredentialProxy credentials;
    private final WorkspaceLayout workspace;
    private final SandboxAudit audit;
    private final SceneCatalog scenes;

    @Autowired
    public TencentRideService(TencentRideProperties properties, ObjectMapper json,
                              @Value("${app.agent.skills-dir:./skills}") String skillsDir,
                              SandboxExecutor executor, ProcessSandbox processSandbox, CredentialProxy credentials,
                              WorkspaceLayout workspace, SandboxAudit audit, SceneCatalog scenes) {
        this.properties = properties;
        this.json = json;
        this.skillsDir = Path.of(skillsDir);
        this.executor = executor;
        this.processSandbox = processSandbox;
        this.credentials = credentials;
        this.workspace = workspace;
        this.audit = audit;
        this.scenes = scenes;
    }

    /** Test and availability callers that never start a process. Execution fails closed until a sandbox is wired. */
    public TencentRideService(TencentRideProperties properties, ObjectMapper json, String skillsDir) {
        this(properties, json, skillsDir, null, null, null, null, null, null);
    }

    /** Local configuration observation only: no process, provider request or credential disclosure. */
    public CapabilityAvailability availability(){
        var state=CapabilityAvailability.State.UNAVAILABLE;
        if(!properties.isEnabled())return CapabilityAvailability.of(state,"DISABLED","出行能力已在本机停用");
        if(properties.getToken()==null||properties.getToken().isBlank())return CapabilityAvailability.of(state,"CREDENTIAL_REQUIRED","需要在本机配置出行凭据；不要在对话中发送凭据");
        if(resolvePython()==null)return CapabilityAvailability.of(state,"PYTHON_UNAVAILABLE","本机未找到配置的可执行程序，请检查 Python 配置");
        for(String name:new String[]{"tms_takecar.py","tms_takecar_api.py","personal_agent_bridge.py"})
            if(!Files.isRegularFile(skillsDir.resolve("tencent-ride/scripts").resolve(name))&&!new ClassPathResource("skills/tencent-ride/scripts/"+name,TencentRideService.class.getClassLoader()).exists())
                return CapabilityAvailability.of(state,"SCRIPT_UNAVAILABLE","出行桥接脚本缺失，请检查技能安装");
        return CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"LOCAL_CONFIGURED","本地配置已具备；Python 版本、凭据有效性和业务权限尚未验证，实际使用仍需 preflight");
    }
    public String operationScope(){return sessionKey("tencent-ride\0"+properties.getToken().trim());}
    /** Only reads a correlated local receipt and optionally the current provider order. Never creates an order. */
    public synchronized String reconcile(String sessionId,String logicalId,String scopeKey)throws IOException{
        if(!properties.isEnabled()||!operationScope().equals(scopeKey))throw new IllegalStateException("出行账号不可用或已改变，不能核实旧操作");
        if(!java.util.UUID.fromString(logicalId).toString().equals(logicalId))throw new IllegalArgumentException("逻辑操作编号无效");
        Path receipt=properties.getDataDirectory().toAbsolutePath().normalize().resolve("accounts").resolve(sessionKey(properties.getToken())).resolve("operations").resolve(logicalId+".json");
        if(!Files.isRegularFile(receipt))return json.writeValueAsString(Map.of("logicalId",logicalId,"scopeKey",scopeKey,"status","UNKNOWN","reason","CORRELATION_MISSING"));
        if(Files.size(receipt)>128000)throw new IllegalStateException("关联回执过大，停止核实");
        com.fasterxml.jackson.databind.JsonNode saved;
        try{saved=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(Files.readString(receipt,StandardCharsets.UTF_8));}
        catch(IOException unreadable){throw new IllegalStateException("无法读取关联回执，已停止核查",unreadable);}
        if(!logicalId.equals(saved.path("logicalId").asText())||!scopeKey.equals(saved.path("scopeKey").asText()))throw new IllegalStateException("回执关联不匹配");
        var verified=new org.celanwang.mio.orchestration.TencentOrderReceiptVerifier().verify(json.writeValueAsString(saved.path("receipt")));
        String observed=verified.outcome()==org.celanwang.mio.orchestration.OperationOutcome.CONFIRMED_APPLIED?execute(sessionId,Map.of("command","query-order","parameters",Map.of("order_id",verified.businessId()))):"{}";
        return json.writeValueAsString(Map.of("logicalId",logicalId,"scopeKey",scopeKey,"createReceipt",saved.path("receipt"),"currentOrder",observed));
    }

    // 同一账号串行执行；会话各自保存起终点和报价，不能覆盖另一会话的已确认行程。
    public synchronized String execute(String sessionId, Map<String, Object> input) throws IOException {
        return execute(sessionId, input, null);
    }

    public synchronized String execute(String sessionId, Map<String, Object> input, ExecutionTicket presented) throws IOException {
        if (!properties.isEnabled()) return "disabled：腾讯打车服务已停用。";
        if (sessionId == null || sessionId.isBlank()) return "error：缺少对话标识，请开启新对话。";
        if (!org.celanwang.mio.tool.TencentRideTool.isSupported(input.get("command"))) {
            return "error：不支持的打车命令。";
        }
        if (executor == null || processSandbox == null || credentials == null || workspace == null || audit == null) {
            return "error：进程沙箱未配置，已拒绝执行，未回退到宿主机。";
        }
        String tool = presented != null ? presented.tool()
                : "create-order".equals(input.get("command")) ? "tencent_ride_create_order" : "tencent_ride";
        Classification required = scenes.classify(tool, input, false, "tencent-ride");
        ExecutionTicket ticket = presented != null ? presented : SandboxScope.currentTicket().orElse(null);
        try {
            if (ticket == null) {
                if (required.risk() != RiskClass.READONLY) {
                    audit.refused(sessionId, tool, executor.principal(), "TICKET_REQUIRED", "写操作或下单缺少执行票据");
                    return "error：沙箱拒绝执行，缺少执行票据，未启动进程。";
                }
                ticket = executor.admitReadonlySystem(tool, input);
            }
            if (!ticket.covers(required, executor.clock())) {
                audit.refused(ticket, "TICKET_TOO_WEAK");
                return "error：沙箱拒绝执行，票据不足以覆盖该操作，未启动进程。";
            }
            String token = credentials.materialize(ticket, "tencent-ride");
            Path root = workspace.rideState(ticket.principal(), properties.getDataDirectory());
            MountPolicy.assertNarrow(root, false);
            Path runtime = root.resolve("runtime");
            Path session = root.resolve("sessions").resolve(sessionKey(sessionId));
            Path account = root.resolve("accounts").resolve(sessionKey(properties.getToken() == null ? "" : properties.getToken()));
            workspace.requireInside(root, runtime);
            workspace.requireInside(root, session);
            workspace.requireInside(root, account);
            Files.createDirectories(runtime);
            Files.createDirectories(session);
            Files.createDirectories(account);
            for (String name : new String[]{"tms_takecar.py", "tms_takecar_api.py", "personal_agent_bridge.py"}) {
                copyScript(name, runtime.resolve(name));
            }
            Path python = resolvePython();
            if (python == null) {
                return "error：无法运行腾讯打车脚本，请检查 Python 路径和数据目录权限。";
            }
            Map<String, Object> request = new LinkedHashMap<>(input);
            request.put("token", token);
            request.put("residentCity", properties.getResidentCity());
            request.put("sessionDirectory", session.toString());
            request.put("accountDirectory", account.toString());
            Path output = Files.createTempFile(session, "output-", ".json");
            List<ProcessLaunch.Mount> mounts = new ArrayList<>();
            mounts.add(new ProcessLaunch.Mount(runtime, runtime, true));
            mounts.add(new ProcessLaunch.Mount(session, session, false));
            mounts.add(new ProcessLaunch.Mount(account, account, false));
            if (!isSystemPath(python)) {
                mounts.add(new ProcessLaunch.Mount(python, python, true));
            }
            List<String> command = List.of(python.toString(), "-B", "-u", runtime.resolve("personal_agent_bridge.py").toString());
            if (containsSecret(command, mounts, token)) {
                audit.refused(ticket, "SECRET_IN_ARGV");
                return "error：沙箱拒绝执行，未启动进程。";
            }
            ProcessLaunch launch = new ProcessLaunch(ticket, command, mounts, ticket.profile().networkAllowed(), false,
                    session, output, properties.getTimeout());
            Process process = null;
            try {
                process = processSandbox.launch(launch);
                audit.started(ticket, true);
                try (var stdin = process.getOutputStream()) {
                    stdin.write(json.writeValueAsBytes(request));
                }
                if (!process.waitFor(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    stop(process);
                    return "error：打车工具超时，操作结果可能不明。请查询当前订单，不要重复下单或取消。";
                }
                if (Files.size(output) > 2_000_000) return "error：工具结果过大，请缩小查询范围。";
                String result = Files.readString(output, StandardCharsets.UTF_8);
                if (!token.isBlank()) result = result.replace(token, "[REDACTED]");
                return result.isBlank() ? "error：打车工具没有返回结果，请先核对当前订单。" : result;
            } catch (SandboxUnavailableException unavailable) {
                audit.unavailable(ticket, unavailable.code());
                log.warn("出行进程沙箱未启动：{} {}", unavailable.getClass().getSimpleName(), unavailable.code());
                return "error：进程沙箱无法启动，已拒绝执行，未回退到宿主机。";
            } catch (SandboxRefusedException refused) {
                audit.refused(ticket, refused.code());
                log.warn("出行进程沙箱未启动：{} {}", refused.getClass().getSimpleName(), refused.code());
                return "error：进程沙箱无法启动，已拒绝执行，未回退到宿主机。";
            } catch (InterruptedException failure) {
                if (process != null) stop(process);
                Thread.currentThread().interrupt();
                throw new IOException("打车操作已中断，请先核对当前订单。", failure);
            } finally {
                if (process != null && process.isAlive()) stop(process);
                Files.deleteIfExists(output);
            }
        } catch (SandboxRefusedException refused) {
            if (ticket != null) audit.refused(ticket, refused.code());
            else audit.refused(sessionId, tool, executor.principal(), refused.code(), "出行沙箱拒绝");
            return "error：沙箱拒绝执行，未启动进程（" + refused.code() + "）。";
        } catch (SandboxUnavailableException unavailable) {
            if (ticket != null) audit.unavailable(ticket, unavailable.code());
            else audit.unavailable(sessionId, null, tool, executor.principal(), unavailable.code());
            return "error：沙箱拒绝执行，未启动进程（" + unavailable.code() + "）。";
        } catch (IOException failure) {
            if (failure.getCause() instanceof InterruptedException) {
                throw failure;
            }
            log.warn("出行脚本未能在沙箱内运行");
            return "error：无法运行腾讯打车脚本，请检查 Python 路径和数据目录权限。";
        }
    }

    private static boolean containsSecret(List<String> command, List<ProcessLaunch.Mount> mounts, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        for (String arg : command) {
            if (arg.contains(token)) return true;
        }
        for (ProcessLaunch.Mount mount : mounts) {
            if (mount.source().toString().contains(token) || mount.target().toString().contains(token)) return true;
        }
        return false;
    }

    private static boolean isSystemPath(Path path) {
        Path normal = path.toAbsolutePath().normalize();
        return normal.startsWith("/usr") || normal.startsWith("/bin");
    }

    private Path resolvePython() {
        String python = properties.getPython();
        if (python == null || python.isBlank()) return null;
        Path given = Path.of(python);
        if (python.contains("/")) {
            return isExecutableFile(given) ? given.toAbsolutePath().normalize() : null;
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path candidate = Path.of(dir, python);
            if (isExecutableFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return null;
    }

    private static boolean isExecutableFile(Path path){return Files.isRegularFile(path)&&Files.isExecutable(path);}

    // 技能在文件系统目录（默认 ./skills，MIO_SKILLS_DIR 可覆盖），修改脚本无需重新构建；
    // 目录缺失时回退到 classpath 内置副本（若打包进 resources）。
    private void copyScript(String name, Path target) throws IOException {
        Path local = skillsDir.resolve("tencent-ride").resolve("scripts").resolve(name);
        if (Files.isRegularFile(local)) {
            Files.copy(local, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        // SDK/HTTP worker threads may not inherit the Boot loader that can read nested JAR resources.
        try (InputStream source = new ClassPathResource("skills/tencent-ride/scripts/" + name,
                TencentRideService.class.getClassLoader()).getInputStream()) {
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
    }

    private static String sessionKey(String id) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
