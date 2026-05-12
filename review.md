# Claude-Web 代码 Review 报告

> 生成时间：2026-05-12
> 覆盖版本：v1.03（当前 `main` 分支）

---

## 一、项目概览

| 维度 | 描述 |
|------|------|
| **技术栈** | Java 17 / Spring Boot 3.2.5 / WebFlux(SSE) / MyBatis / Thymeleaf / Vanilla JS |
| **定位** | Claude Agent（Node.js）的 Web 前端代理，通过 WebSocket 连接后端，向浏览器暴露 REST + SSE 接口 |
| **核心数据流** | 浏览器 → `SessionApiController` → `AppServerProcess` → WebSocket → Claude Agent |
| **部署方式** | 单 JAR，`start.sh` 脚本启动，端口 3000 |

### 目录结构速览

```
src/main/java/com/claude/web/
├── config/         ClaudeProperties, SecurityConfig, WebConfig, JacksonConfig
├── controller/     ClaudeApiController, SessionApiController, SessionApiPostController,
│                   SseController, SpaController, LoginController
├── dto/            JsonRpcRequest/Response, NotificationEvent, PendingServerRequest, ...
├── entity/         AiApp, AiSession
├── mapper/         AiAppMapper(空), AiSessionMapper
├── security/       AuthFilter（已被注释掉）
└── service/        AppServerProcess, SseEventService, MethodCatalogService
    └── transport/  ClaudeTransport(接口), ClaudeAgentTransport(WebSocket实现)

src/main/resources/
├── static/js/      core.js, ui.js, messaging.js, streaming.js, marked.min.js
├── static/css/     style.css
└── templates/      index.html, login.html, session-api-test.html, sse-test.html
```

---

## 二、关键安全问题（必须修复）

### 🔴 S1 — 敏感凭证硬编码在 `application.yml` 中

```yaml
# application.yml
datasource:
  username: root
  password: root           # ← 生产数据库 root 密码明文

claude:
  web:
    password: 111          # ← Web UI 登录密码明文
    claude-agent:
      api-key: 204e28ff... # ← 完整 API Key 硬编码（已提交入 git）
```

**影响**：任何能访问仓库（或 git 历史）的人都能获取这三个凭证。API Key 已经无法撤销历史记录。

**建议**：全部改为环境变量或外部配置，从 `application.yml` 中删除默认值，并立即轮换已泄露的 API Key。

---

### 🔴 S2 — 认证系统实际上完全失效

`AuthFilter.java` 的 `@Component` 和 `@Order(1)` 注解被注释掉：

```java
//@Component
//@Order(1)
public class AuthFilter implements Filter {
```

`SecurityConfig.java` 则允许所有请求：

```java
.authorizeHttpRequests(auth -> auth
    .anyRequest().permitAll()
);
```

**效果**：即使 `application.yml` 里写了 `password-enabled: true`、`password: 111`，实际上任何人不需要密码就能访问所有接口，包括 `/api/claude/v2/sendMessage`（可以向 Claude Agent 发送任意命令）。

**建议**：补全认证——要么重新启用 `AuthFilter`，要么在 `SecurityConfig` 中实现基于 Session/JWT 的认证。

---

### 🔴 S3 — XSS 风险：marked.js 输出未经 sanitize 直接注入 DOM

`ui.js`（第 228 行）：

```js
html += '<div class="message-card assistant markdown-body">' + marked.parse(m.text || '') + '</div>';
```

`marked.js` v5+ 默认不转义 HTML。若 AI 回复中包含 `<script>alert(1)</script>` 或 `<img src=x onerror=...>`，会直接在用户浏览器中执行。

**场景**：如果 Claude Agent 返回了被注入的内容，或后端被攻击者控制，可对所有登录用户执行任意 JS。

**建议**：在 `marked.parse()` 后用 DOMPurify 等库过滤，或启用 marked 的 `sanitize` 选项。

---

### 🟠 S4 — CORS 配置错误（功能性问题）

`WebConfig.java`：

```java
registry.addMapping("/**")
    .allowedOriginPatterns("*")     // 通配符
    .allowCredentials(true);         // 带凭证
```

**问题**：浏览器的 CORS 规范明确禁止同时使用 `*` 通配符和 `allowCredentials(true)`，任何跨域带凭证请求都会被浏览器拒绝。这个配置实际不起作用，但一旦有其他服务需要跨域调用，会遇到问题。

**建议**：改为显式指定允许的 Origin，或去掉 `allowCredentials(true)`。

---

### 🟠 S5 — Token 存储为纯文本内存 Set

`AuthFilter.java`（虽然目前被禁用）：

```java
private final Set<String> validTokens = ConcurrentHashMap.newKeySet();
```

- 重启即失效，所有已登录用户需重新认证
- 无过期清理机制（`setMaxAge(7 * 24 * 60 * 60)` 只是 cookie 过期，服务端 Set 永远增长）
- 无登出接口对应的 Token 清理

---

## 三、架构与设计问题

### 🔴 A1 — 两个 Controller 维护两份独立的内存状态（最严重的架构问题）

`SessionApiController`（`/api/claude`）和 `SessionApiPostController`（`/api/claude/v2`）**各自**维护了完全独立的内存状态：

```java
// 两个 Controller 各自都有这四个 Map：
private final Map<String, ClaudeSession> sessions = new ConcurrentHashMap<>();
private final Map<String, List<Map<String, Object>>> sessionMessages = new ConcurrentHashMap<>();
private final Map<String, String> frontendToSdkId = new ConcurrentHashMap<>();
private final Map<String, String> sdkToFrontendId = new ConcurrentHashMap<>();
```

两个 Controller 都通过 `appServerProcess.addNotificationListener(...)` 监听同一个 WebSocket 推送的消息。这意味着：

- 每一条来自 Claude Agent 的消息都会被**两个 Controller 各自处理一遍**
- `session_created` 事件的 placeholder 促成逻辑会在两处各跑一次，产生竞争
- 前端通过 Web UI 使用 `/api/claude`，而第三方通过 `/api/claude/v2` 调用，两者的 `sdkToFrontendId` 映射表完全不同步

**建议**：将 session 状态管理提取为单一 `SessionStateService`，两个 Controller 都从这个 Service 读写。

---

### 🔴 A2 — `sessionMessages` 的 `List` 线程不安全

```java
// ConcurrentHashMap 保护 map 本身，但 List 没有同步
List<Map<String, Object>> msgs = sessionMessages.computeIfAbsent(id, k -> new ArrayList<>());
msgs.add(userMsg);                // HTTP 线程写
// 同时通知监听器线程也在写：
last.put("text", last.get("text") + content); // 拼接 stream_delta
```

两个线程可能同时修改同一个 `ArrayList`，导致数据竞争（`ConcurrentModificationException` 或乱序内容）。

**建议**：换用 `CopyOnWriteArrayList` 或加显式锁。

---

### 🟠 A3 — 大量代码完全重复

以下逻辑在两个 Controller 中几乎完全相同，合计约 600 行重复：

- `handleClaudeAgentMessage()`（session_created 处理逻辑）
- `convertAgentMessages()` / `extractTextFromContent()` / `extractThinkingFromContent()`
- `buildTurns()`
- `findRecentPlaceholder()` / `cleanupPlaceholderSessions()`
- 发送 chat 消息、abort 消息的逻辑

这违反了 DRY 原则，两处 bug 需要修两处。

---

### 🟠 A4 — `ClaudeAgentTransport.connect()` 用忙等待检测连接

```java
while (!connected && waitMs < connectionTimeout) {
    Thread.sleep(100);
    waitMs += 100;
}
```

在 `connectionTimeout=30000` 的情况下，最坏情况会占用线程 30 秒。

**建议**：用 `CompletableFuture<Void>` 或 `CountDownLatch` 在连接成功/失败时立即通知。

---

### 🟠 A5 — `reconnecting` 标志存在竞态窗口

`handleDisconnect()` 将 `reconnecting` 设为 `true`，而定时任务中先调用 `reconnecting.set(false)` 再调用 `connectRemote()`。在这两步之间存在一个窗口，此时 `reconnecting` 为 `false` 且 `connected` 为 `false`，并发的 `rpc()` 或 `sendRaw()` 调用会穿透进去尝试发送消息而失败。

---

### 🟡 A6 — `MethodCatalogService` 无法正常工作

`MethodCatalogService.listMethods()` 调用 `appServerProcess.rpc("system/getSchema", null)`，但 Claude Agent WebSocket 协议并不支持 JSON-RPC 的 `system/getSchema` 方法。`ClaudeApiController.listMethods()` 已经硬编码了方法列表，`MethodCatalogService` 实际上没有被任何地方调用。

---

### 🟡 A7 — `AiApp` 实体和 `AiAppMapper` 完全未使用

`AiAppMapper.java` 文件内容为空（只有 1 行），`AiApp` 实体在整个项目中从未被读取或写入。这是早期设计的遗留代码。

---

### 🟡 A8 — MySQL 被 Web UI 忽略，仅 v2 API 写入

- `SessionApiController`（Web UI 使用）：完全不读写 MySQL
- `SessionApiPostController`（v2 API）：在 `create` 和 `sendMessage` 时写入 `t_ai_sessions`

这意味着通过 Web UI 创建的会话不会持久化到数据库，重启后消失。

---

### 🟡 A9 — `cwd` 路径硬编码在多处

以下位置都将 `/home/ubuntu` 作为硬编码字符串：

- `SessionApiController.listSessions()` — `Map.of("cwd", "/home/ubuntu")`（3 处）
- `sendHomeMessage()` in messaging.js — `const cwd = '/home/ubuntu'`
- `renderSidebar()` in ui.js — `const currentCwd = '/home/ubuntu'`
- `SessionApiPostController` 多处

`ClaudeProperties.SessionDefaults.cwd` 虽然有默认值，但 Controller 没有从这里读取，而是直接写死字符串。

---

## 四、前端问题

### 🟠 F1 — 全局轮询每 4 秒请求一次

`streaming.js` `startAutoRefresh()`：

```js
state.autoRefreshTimer = setInterval(() => {
    refreshThreads();
    if (state.selectedThreadId) { loadMessages(state.selectedThreadId); fetchPendingRequests(); }
}, 4000);
```

每 4 秒会触发最多 3 个 HTTP 请求，即使用户没有在操作。在多用户场景下会给服务端带来不必要的压力。

**建议**：只在 SSE 连接断开时才回退到轮询，正常情况下依赖 SSE 推送更新。

---

### 🟠 F2 — `sendMessageStream` 的 SSE 过滤逻辑有竞态风险

`SessionApiPostController.sendMessageStream()` 先订阅全局 SSE 事件流，再发送消息：

```java
// 1. 订阅全局 SSE
sseEventService.createEventStream().subscribe(event -> {
    // 按 sessionId 过滤
});
// 2. 发送消息（消息在订阅之后）
appServerProcess.sendRaw(objectMapper.writeValueAsString(chatMsg));
```

虽然顺序正确，但事件流中所有并发会话的消息都经过此订阅器并被过滤，高并发时效率低。

---

### 🟡 F3 — 侧边栏只展示 `cwd === '/home/ubuntu'` 的会话

`ui.js renderSidebar()`：

```js
const currentCwd = '/home/ubuntu';
let threads = [];
for (const g of state.projectGroups) {
    for (const t of g.threads) {
        if (t.cwd === currentCwd) threads.push(t);  // 过滤掉所有其他目录
    }
}
```

如果 Claude Agent 在其他目录下创建了会话，Web UI 永远看不到它们。

---

### 🟡 F4 — 错误处理使用 `alert()`

`messaging.js` 中大量使用 `alert('发送失败: ' + e.message)`，这会阻塞 UI 且用户体验差。建议改为内联错误提示。

---

### 🟡 F5 — JS 全局状态易受并发操作污染

`state` 对象是全局的，`isSendingMessage` 标志虽然防止了重复发送，但：
- `refreshThreads` 和 `loadMessages` 可能同时在运行
- 快速切换 thread 可能导致一个 thread 的消息显示在另一个 thread 界面

---

## 五、其他发现

### 🟡 M1 — `start.sh` 硬编码特定用户路径

```bash
export CLAUDE_AGENT_API_KEY="${CLAUDE_AGENT_API_KEY:-$(cat /home/sunsw/.codex/jwt_secret.key)}"
```

`/home/sunsw` 是特定机器上的特定用户，换环境运行必须手动修改。

---

### 🟡 M2 — `application.yml` 中 `model: GLM-4.7-AWQ` 无效配置

```yaml
claude:
  model: GLM-4.7-AWQ
```

`ClaudeProperties` 中没有 `model` 字段，这个配置不会被读取，是无效配置，可能是早期遗留。

---

### 🟡 M3 — 无任何单元测试或集成测试

`pom.xml` 声明了 `spring-boot-starter-test` 和 `spring-security-test` 依赖，但 `src/test/` 目录下没有任何测试类。

---

### 🟡 M4 — `session-api-test.html` 和 `sse-test.html` 暴露在生产环境

`SpaController` 映射了 `/session-api-test`（`session-api-test.html`），任何人都可以访问这个调试页面（无需认证，因为认证已关闭）。

---

### 🟡 M5 — `PasswordGenerator.java` 存在但未使用

该工具类提供了随机密码生成逻辑，但启动时没有任何代码调用它。`ClaudeProperties` 中 `password` 默认值为空字符串，当 `password-enabled: true` 且密码为空时，`isAuthEnabled()` 返回 `false`，实际等于关闭认证。

---

## 六、问题优先级汇总

| 优先级 | 编号 | 问题 |
|--------|------|------|
| 🔴 必须修复 | S1 | 敏感凭证（API Key、DB密码、Web密码）硬编码 |
| 🔴 必须修复 | S2 | 认证系统完全失效（AuthFilter 被注释） |
| 🔴 必须修复 | S3 | marked.js 输出未 sanitize，存在 XSS |
| 🔴 必须修复 | A1 | 两个 Controller 各自维护独立状态，双重监听 |
| 🔴 必须修复 | A2 | `sessionMessages` ArrayList 非线程安全 |
| 🟠 应该修复 | A3 | 两 Controller 大量代码重复（~600行） |
| 🟠 应该修复 | A4 | WebSocket 连接建立用忙等待 |
| 🟠 应该修复 | A5 | `reconnecting` 标志竞态窗口 |
| 🟠 应该修复 | S4 | CORS 配置无效（通配符+凭证） |
| 🟠 应该修复 | F1 | 4 秒全局轮询 |
| 🟠 应该修复 | F2 | sendMessageStream 广播过滤效率 |
| 🟡 建议优化 | A6-A9 | MethodCatalogService 无效、AiApp 未使用、MySQL 不一致、cwd 硬编码 |
| 🟡 建议优化 | F3-F5 | 侧边栏 cwd 过滤、alert 错误处理、全局状态竞争 |
| 🟡 建议优化 | M1-M5 | 路径硬编码、无效配置、无测试、测试页面暴露 |

---

## 七、建议下一步行动

### 近期（安全修复，1-2天）

1. **轮换 API Key**：立即在 Claude Agent 侧撤销 `204e28ff...` 这个已泄露的 Key，生成新 Key
2. **从 git 历史清除凭证**：使用 `git filter-repo` 或 BFG 工具清理 `application.yml` 历史提交
3. **使用环境变量**：`application.yml` 中所有敏感值改为 `${ENV_VAR:}` 占位符
4. **重新启用认证**：在 `AuthFilter` 上恢复 `@Component`、`@Order(1)` 注解，并在 `SecurityConfig` 限制 `/api/**` 只对已认证用户开放
5. **marked.js 加 sanitize**：引入 DOMPurify 或使用 `marked.setOptions({ hooks: { postprocess: DOMPurify.sanitize } })`

### 中期（架构整理，1-2周）

6. **提取 `SessionStateService`**：将 `sessions`、`sessionMessages`、`frontendToSdkId`、`sdkToFrontendId` 和相关逻辑移入单一 Service，两个 Controller 只调用这个 Service
7. **线程安全修复**：将 `sessionMessages` 的 `ArrayList` 替换为 `CopyOnWriteArrayList` 或加锁
8. **去掉全局轮询**：SSE 连接正常时移除 `setInterval`，仅在 `connection/restored` 或重连后做一次刷新
9. **消除 cwd 硬编码**：统一从 `claudeProperties.getSessionDefaults().getCwd()` 读取

### 长期（质量提升）

10. **补充测试**：至少为 `SessionApiController` 的消息发送/session 映射逻辑和 `AppServerProcess` 的重连逻辑加集成测试
11. **删除死代码**：`AiApp`、`AiAppMapper`（空文件）、`MethodCatalogService`（无调用者）
12. **统一 API 版本**：考虑是否需要同时维护 v1（`/api/claude`）和 v2（`/api/claude/v2`），或废弃其中一个