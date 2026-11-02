# 沙箱隔离

沙箱回答的是「一次已经被允许的操作，在什么边界里执行」。它不是第二套 allow/ask/deny。唯一裁决点仍是 `PolicyEngine`（`app.security.policy-mode`，默认 `shadow`，本改动不把它改成 `enforce`）。急停开关也仍由 `PolicyEngine` 把非只读操作转成询问；Jev 在急停开启时不会自动放行。

设计是风险 × 运行时矩阵。两边同时出现时，风险和运行时都取更严的那一档，凭据域不一致则直接拒绝。

| 运行时 | 只读 | 可逆写 | 不可逆写（下单/支付/公开发布） |
| --- | --- | --- | --- |
| JVM 内（AgentScope / MCP） | 主体工作区 + 凭据代理 | 工作区 + 执行票据 + 审计 | 工作区 + 人工确认后的受限执行；无票据不出站 |
| 本地子进程（腾讯打车 Python） | 进程沙箱，系统目录只读挂载 | 进程沙箱 + 窄挂载 / 出网 / 超时 | 最严进程沙箱 + 人工确认；默认不挂载宿主机凭据目录 |
| 容器 / gVisor | 接缝，默认不启用 | 同左 | 同左 |

场景落点：

| 场景 | 风险 | 运行时 |
| --- | --- | --- |
| 麦当劳菜单、门店、算价等只读工具 | 只读 | JVM |
| 麦当劳 `create-order` / `mall-create-order` / `party-order-create` | 不可逆 | JVM |
| 微博读取、`prepare_weibo` | 只读 | JVM |
| 微博评论、点赞 | 可逆 | JVM |
| 微博发博、转发、删除 | 不可逆 | JVM |
| 打车询价、地点搜索、查单 | 只读 | 子进程 |
| 打车改地址草稿、保存偏好 | 可逆 | 子进程 |
| 打车下单、取消订单 | 不可逆 | 子进程 |

取消订单会涉及取消费，和创建订单一样要求人工确认后的票据。这跟现有 `ToolPolicies.isJevReviewable` 对 `tencent_ride` + `cancel-order` 的排除一致，不是新的放行规则。

## 失败方向

- 没有执行票据：不可逆操作和所有子进程拒绝；只读返回错误，凭据代理不交出令牌。
- bubblewrap 不存在、用户命名空间不可用，或沙箱进程启动失败：拒绝，**不会**退回裸 `ProcessBuilder`。
- 容器运行时未实现：`ContainerRuntimeHook` 拒绝，不会改用 bubblewrap 或宿主机。
- 路径跳出主体工作区，或挂载 `.env` / `.weibo` / `.ssh` / `.aws` / `.gnupg`：拒绝。

票据只在 `PolicyEngine` 给出 `ALLOW`，或现有权限链已经把调用放进 `callAsync`（人工确认或 Jev 确认）之后签发。不可逆操作还要求这次放行不是 Jev 可自动审核的工具。`shadow` 模式下硬编码 `DENY` 仍只留痕；`enforce` 模式下 `DENY` 会拒绝签发票据。当前硬编码拒绝集为空。

## 代码入口

| 类 | 职责 |
| --- | --- |
| `SceneCatalog` / `SandboxMatrix` | 矩阵与场景分类，冲突取更严 |
| `TicketIssuer` / `ExecutionTicket` | 票据。一票一次进程 |
| `SandboxExecutor` / `SandboxedTool` | JVM 工具调用边界 |
| `CredentialProxy` / `CredentialLease` | 麦当劳、微博、打车令牌只在有票据时交出。麦当劳 MCP 不再使用静态 `Authorization` 头 |
| `WorkspaceLayout` | `{root}/{principal}`。默认主体 `local-owner` 的打车数据仍在 `.tencent-ride`；其他主体使用自己的 `tencent-ride` 目录 |
| `LinuxProcessSandbox` | bubblewrap：`--unshare-user/pid/ipc/uts`，`--clearenv`，系统目录只读，只绑运行脚本、会话目录和账号目录。出网只有布尔 `--unshare-net`，见下一节 |
| `ContainerRuntimeHook` | P2 接缝 |
| `RowIsolationGuard` | P2 行级隔离接缝，`principal_id = ?` 尚未加到现有表 |
| `GET /api/security/sandbox` | 回环只读状态，不授予执行 |

打车 `TencentRideService.execute` 是子进程的唯一启动点，它只调用 `ProcessSandbox`。主对话里的打车工具、生产领域工具，以及离线效果 / ACS 夹具都带上 `SandboxExecutor`（合成工具再包 `SandboxedTool`）。夹具仍 mock `TencentRideService`，默认不启动 bubblewrap。单参数构造函数只留给不作为 ACS 证据的单元测试；效果夹具若绕过票据，`effect_success` 不能带上 `ticket_id`，观察行也不会是 `sandbox_path=sandboxed` 的通过。

## 出网边界

子进程和 JVM 不是同一种出网控制。两边都还没有按目的地址过滤。

**子进程是一个布尔。** `IsolationProfile.networkAllowed` 为 false 时，`LinuxProcessSandbox.plan` 的 argv 包含 `--unshare-net`；为 true 时不包含这一项。进程参数里的布尔必须和票据隔离档一致，否则拒绝码是 `NETWORK_MISMATCH`，进程不启动。这只表示这个子进程有没有网络命名空间出网，不表示允许哪些地址、域名或端口。argv 里没有 iptables、seccomp 或 gVisor。

**JVM 走凭据代理，没有自己的网络命名空间。** 麦当劳、微博、打车的令牌只在活票据覆盖对应凭据域时由 `CredentialProxy` 交出。没有票据就不出令牌。代理约束的是凭据，不是套接字目的地。

**打车允许出网，仍然要票据。** 询价、改地址草稿、下单这三档子进程都是 `networkAllowed=true`，因为要访问腾讯出行接口。允许出网不能省略执行票据；下单和取消还要人工确认后的票据。

**这次没有建立：** 目的地址或域名过滤、seccomp、gVisor。`GET /api/security/sandbox` 的 `notEstablished` 继续包含 `destination network filter`。不要把状态接口或本段读成已经按目的地过滤，或读成 JVM 有网络命名空间。

## 已建立与未建立

**已建立（有确定性测试）：**

- 主体工作区隔离，以及 `local-owner` 与 `local-owner-2` 这种前缀相邻主体的跨主体拒绝
- 执行票据：只读放行、写操作缺少权限链放行、不可逆缺少人工确认、急停未确认、票据不能重复启动进程
- 审计事件进 `TraceStore`（`sandbox_admit` / `sandbox_refuse` / `sandbox_egress` / `sandbox_start`），明细不含令牌
- 打车桥接强制走进程沙箱；沙箱启动失败或缺少下单票据时不启动进程
- 凭据代理：无票据拒绝出站，且不写 `sandbox_egress` / `sandbox_refuse`（痕迹为空）
- 离线夹具的准入投影复用 `SandboxAuthorityObservations` 字段（`sandbox_path`、`policy_engine`、`admitted`、`ticket_id`、`sandbox_event`、`trace_events`、`effect_success`、`process_starts`，以及可选只读 `network_allowed`），不另开采集通道。`network_allowed` 只回显票据上的布尔出网位；缺省或 null 表示这次观察没有记录它。它不授予出网，也不是目的地过滤。`shadow.py` 不消费这个字段

**未建立（不要当成已经做到）：**

- gVisor / 容器运行时。开关默认关闭，打开也只返回 `CONTAINER_NOT_ESTABLISHED`
- 数据库行级安全、RLS、按主体拆 schema。产品边界仍是单机 `local-owner`
- 按目标地址或域名收紧的网络过滤。子进程只有布尔 `--unshare-net`；打车三档允许出网，但仍要票据。见「出网边界」
- seccomp、capability 白名单
- 询价也要写会话草稿，所以会话目录在只读风险档仍然可写。只读约束的是宿主机和系统挂载，不是一份完全只读的文件系统
- 麦当劳 / 微博 HTTP 客户端若在自定义器抛错后仍把请求发出去，SDK 行为不在本仓库内证明。自定义器在没有票据时不会附带令牌，并抛出拒绝

## 配置

| 变量 | 默认 | 含义 |
| --- | --- | --- |
| `MIO_SANDBOX_ROOT` | `.mio/sandbox` | 工作区根，不能是文件系统根 |
| `MIO_SANDBOX_PRINCIPAL` | `local-owner` | 默认主体 |
| `MIO_SANDBOX_BWRAP` | `bwrap` | bubblewrap |
| `MIO_SANDBOX_CONTAINER_ENABLED` | `false` | 容器接缝 |
| `MIO_SANDBOX_CONTAINER_RUNTIME` | 空 | 仅在开关打开时检查是否存在，不会被执行 |
| `MIO_SANDBOX_TICKET_TTL` | `2m` | 票据寿命 |
| `MIO_SECURITY_POLICY_MODE` | `shadow` | 与沙箱无关，保持原默认 |

`.mio/` 已在 `.gitignore`。

## 测试

不需要 Docker、PostgreSQL 或 gVisor。bubblewrap 可用时会多跑一个真实挂载隔离用例；不可用时该用例跳过，其余用例仍断言「不可用即拒绝」。

```bash
mvn -Dtest=org.celanwang.mio.sandbox.** test
```

状态接口（应用启动后，仅本机）：

```bash
curl -s http://127.0.0.1:8080/api/security/sandbox
```
