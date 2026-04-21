# Claude Agent + Claude Web 联合测试方案

## 1. 概述

本文档描述 claude-agent（Node.js WebSocket 后端）与 claude-web（Spring Boot Web 前端）两个配套项目的完整测试方案，覆盖单元测试、集成测试、端到端联调测试、性能测试和安全测试。

## 2. 系统架构

```
浏览器/测试客户端
    |
    | HTTP REST API / SSE
    v
claude-web (Spring Boot, 端口 3000)
    |
    | WebSocket (Authorization: Bearer)
    v
claude-agent (Node.js, 端口 3001)
    |
    | Claude Agent SDK
    v
Claude Code / Claude API
```

## 3. 测试环境

| 组件 | 版本要求 | 端口 |
|------|---------|------|
| Java | 17+ | - |
| Maven | 3.6+ | - |
| Node.js | 18+ | - |
| claude-web | 0.1.0 | 3000 |
| claude-agent | 1.0.0 | 3001 |

**环境变量**（用于联调测试）：

```bash
export CLAUDE_AGENT_HOST=127.0.0.1
export CLAUDE_AGENT_PORT=3001
export CLAUDE_AGENT_API_KEY=$(cat /home/sunsw/.codex/jwt_secret.key)
export PORT=3000
```

## 4. 测试策略

### 4.1 测试金字塔

```
        /\
       /  \   端到端联调测试 (E2E)
      /    \  -------------------
     /------\  集成测试 (Integration)
    /        \ -------------------
   /----------\ 单元测试 (Unit)
  /            \------------------
```

### 4.2 测试分类

| 类别 | 目标 | 执行频率 |
|------|------|---------|
| 单元测试 | 单个函数/类 | 每次提交 |
| 集成测试 | 模块间交互 | 每次提交 |
| 端到端测试 | 完整用户场景 | 每日/发布前 |
| 性能测试 | 负载和稳定性 | 每周 |
| 安全测试 | 认证和输入验证 | 每次发布 |
| 联调测试 | 两个项目端到端 | 发布前 |

## 5. 测试范围

### 5.1 claude-agent 测试范围

| 模块 | 单元测试 | 集成测试 | E2E | 性能 |
|------|---------|---------|-----|------|
| 认证 (auth.js) | 已覆盖 | 已覆盖 | - | - |
| 配置 (config.js) | 已覆盖 | - | - | - |
| 消息协议 (protocol.js) | 已覆盖 | - | - | - |
| 会话管理 (session-manager.js) | 已覆盖 | - | - | - |
| 消息处理 (message-handler.js) | 已覆盖 | 已覆盖 | - | - |
| WebSocket 连接 | - | 已覆盖 | 已覆盖 | 已覆盖 |
| HTTP 健康检查 | - | 已覆盖 | - | - |
| Claude SDK 集成 | - | - | 已覆盖 | - |

### 5.2 claude-web 测试范围

| 模块 | 单元测试 | 集成测试 | E2E | 性能 |
|------|---------|---------|-----|------|
| 配置层 (ClaudeProperties) | 已覆盖 | - | - | - |
| 控制器层 (所有 Controller) | 已覆盖 | 已覆盖 | 已覆盖 | - |
| DTO 层 (所有 DTO) | 已覆盖 | - | - | - |
| 安全层 (AuthFilter) | 已覆盖 | 已覆盖 | - | - |
| 服务层 (SseEventService) | 已覆盖 | - | - | - |
| 服务层 (MethodCatalogService) | 已覆盖 | - | - | - |
| 传输层 (ClaudeAgentTransport) | 已覆盖 | 已覆盖 | 已覆盖 | - |
| 连接层 (AppServerProcess) | 部分 | 已覆盖 | 已覆盖 | - |
| WebSocket 重连 | - | 已覆盖 | 已覆盖 | - |
| SSE 事件流 | - | 已覆盖 | 已覆盖 | - |

## 6. 测试用例矩阵

### 6.1 claude-agent 测试用例

| ID | 用例名称 | 类型 | 说明 |
|----|---------|------|------|
| A-UT-01 | API Key 提取 | 单元 | Bearer Token 和裸 key 提取 |
| A-UT-02 | API Key 验证 | 单元 | 常量时间比较，边界情况 |
| A-UT-03 | 配置默认值 | 单元 | 环境变量读取和默认值 |
| A-UT-04 | 消息协议构建 | 单元 | 所有消息类型的构建器 |
| A-UT-05 | 会话 CRUD | 单元 | 创建、更新、删除、查询 |
| A-UT-06 | 消息路由 | 单元 | chat/abort/ping 等路由 |
| A-IT-01 | HTTP 健康检查 | 集成 | GET /health 返回 200 |
| A-IT-02 | CORS 预检 | 集成 | OPTIONS 请求返回 204 |
| A-IT-03 | 未认证拒绝 | 集成 | 无 Authorization 时拒绝 |
| A-IT-04 | 错误认证拒绝 | 集成 | 错误 API Key 时拒绝 |
| A-IT-05 | 正确认证通过 | 集成 | 正确 API Key 时通过 |
| A-IT-06 | 连接消息 | 集成 | 连接成功后收到 connected |
| A-IT-07 | 心跳 ping-pong | 集成 | ping -> pong |
| A-IT-08 | 无效 JSON | 集成 | 发送非 JSON 返回 error |
| A-IT-09 | 未知消息类型 | 集成 | 发送未知类型返回 error |
| A-IT-10 | 获取活跃会话 | 集成 | get_active_sessions |
| A-E2E-01 | WebSocket 协议流程 | E2E | 完整消息交互 |
| A-E2E-02 | 客户端重连 | E2E | 断开重连后状态恢复 |
| A-SEC-01 | 认证绕过 | 安全 | 尝试绕过认证 |
| A-SEC-02 | 输入验证 | 安全 | 特殊字符、超长输入 |
| A-SEC-03 | 资源限制 | 安全 | 连接数限制 |
| A-PERF-01 | 并发连接 | 性能 | 50 并发连接 |
| A-PERF-02 | 消息吞吐 | 性能 | 消息吞吐量测试 |

### 6.2 claude-web 测试用例

| ID | 用例名称 | 类型 | 说明 |
|----|---------|------|------|
| W-UT-01 | 配置属性 | 单元 | ClaudeProperties getter/setter |
| W-UT-02 | Jackson 配置 | 单元 | JSON 序列化配置 |
| W-UT-03 | Security 配置 | 单元 | Spring Security 配置 |
| W-UT-04 | Web/CORS 配置 | 单元 | CORS 配置 |
| W-UT-05 | 会话 API CRUD | 单元 | SessionApiController 所有端点 |
| W-UT-06 | 内部 API | 单元 | ClaudeApiController 端点 |
| W-UT-07 | 登录页面 | 单元 | LoginController |
| W-UT-08 | SPA 路由 | 单元 | SpaController 回退 |
| W-UT-09 | SSE 端点 | 单元 | SseController 事件流 |
| W-UT-10 | DTO 序列化 | 单元 | 所有 DTO 的 JSON 转换 |
| W-UT-11 | 认证过滤 | 单元 | AuthFilter 密码验证 |
| W-UT-12 | 密码生成 | 单元 | PasswordGenerator |
| W-IT-01 | 首页访问 | 集成 | GET / 返回 200 |
| W-IT-02 | 登录页面 | 集成 | GET /login 返回 200 |
| W-IT-03 | API 端点 | 集成 | 所有 /api/claude 端点 |
| W-IT-04 | 元数据端点 | 集成 | meta/methods, meta/notifications |
| W-IT-05 | 会话 CRUD | 集成 | 创建、获取、删除会话 |
| W-IT-06 | 错误处理 | 集成 | 404、400、403 场景 |
| W-IT-07 | 静态资源 | 集成 | JS/CSS 文件 |
| W-IT-08 | WebSocket 传输 | 集成 | 连接、消息收发 |
| W-IT-09 | SSE 流 | 集成 | 事件流推送 |
| W-IT-10 | 重连恢复 | 集成 | 模拟 agent 重启 |
| W-E2E-01 | 用户访问首页 | E2E | 页面加载 |
| W-E2E-02 | 创建会话 | E2E | POST /api/claude |
| W-E2E-03 | 查看会话 | E2E | GET /api/claude/{id} |
| W-E2E-04 | 发送消息 | E2E | POST /api/claude/{id}/message |
| W-E2E-05 | 取消操作 | E2E | POST /api/claude/{id}/cancel |
| W-E2E-06 | 删除会话 | E2E | DELETE /api/claude/{id} |
| W-E2E-07 | 列出会话 | E2E | GET /api/claude |
| W-E2E-08 | API 元数据 | E2E | meta 端点 |
| W-E2E-09 | 错误处理 | E2E | 无效请求 |
| W-E2E-10 | 并发会话 | E2E | 同时创建多个会话 |
| W-PERF-01 | 端点延迟 | 性能 | 各端点响应时间 |
| W-PERF-02 | 负载测试 | 性能 | 会话创建负载 |
| W-PERF-03 | 静态资源 | 性能 | 静态文件负载 |
| W-PERF-04 | 并发用户 | 性能 | 多用户并发 |
| W-PERF-05 | 稳定性 | 性能 | 30 秒持续请求 |

### 6.3 联合联调测试用例

| ID | 用例名称 | 说明 |
|----|---------|------|
| J-E2E-01 | 服务启动 | 同时启动 agent 和 web |
| J-E2E-02 | WebSocket 连接 | web 连接到 agent |
| J-E2E-03 | 会话创建流 | web 创建会话 -> agent 处理 -> SSE 通知 |
| J-E2E-04 | 消息发送流 | web 发送消息 -> agent 处理 -> SSE 流式响应 |
| J-E2E-05 | Agent 重启恢复 | 重启 agent -> web 自动重连 -> 会话恢复 |
| J-E2E-06 | SSE 事件链 | agent 事件 -> web 转发 -> 浏览器接收 |
| J-E2E-07 | 权限申请流 | agent 申请权限 -> web 转发 -> 用户响应 |
| J-E2E-08 | 完整对话 | 创建会话 -> 发送消息 -> 接收响应 -> 删除会话 |

## 7. 测试执行命令

### 7.1 claude-agent 单独测试

```bash
cd /home/sunsw/code/claude-agent

# 单元测试
NODE_OPTIONS='--experimental-vm-modules' npm run test:unit

# 集成测试
NODE_OPTIONS='--experimental-vm-modules' npm run test:integration

# 覆盖率
NODE_OPTIONS='--experimental-vm-modules' npm run test:coverage

# 全部测试（交互式菜单）
./tests/scripts/run-all-tests.sh

# 全部测试（命令行）
./tests/scripts/run-all-tests.sh all

# 安全测试
node tests/scripts/security-test.mjs

# 压力测试
node tests/scripts/stress-test.mjs

# 兼容性测试（模拟 claude-web）
node tests/scripts/compatibility-test.mjs
```

### 7.2 claude-web 单独测试

```bash
cd /home/sunsw/code/claude-web

# 单元测试（Maven）
../.maven/apache-maven-3.9.14/bin/mvn test

# 全部测试（含单元/集成/E2E/性能）
./tests/run-all-tests.sh

# 仅单元测试
./tests/run-all-tests.sh --unit-only

# 仅集成测试
./tests/run-all-tests.sh --integration-only

# 仅 E2E 测试
./tests/run-all-tests.sh --e2e-only

# 仅性能测试
./tests/run-all-tests.sh --performance-only

# 单独测试脚本
./tests/integration/integration-test.sh
./tests/e2e/e2e-test.sh
./tests/performance/performance-test.sh
./tests/websocket/websocket-test.sh
./tests/integration/sse-test.sh
./tests/integration/reconnect-test.sh
```

### 7.3 联合联调测试

```bash
cd /home/sunsw/code/claude-web

# 启动两个服务并执行联调测试
./tests/run-joint-tests.sh

# 手动执行步骤：
# 1. 启动 claude-agent
cd /home/sunsw/code/claude-agent && ./start.sh

# 2. 启动 claude-web
cd /home/sunsw/code/claude-web && ./start.sh

# 3. 执行联调测试
./tests/run-joint-tests.sh --skip-start
```

## 8. 测试报告

所有测试报告保存在 `tests/reports/` 目录：

- `unit-test-*.log` - 单元测试日志
- `integration-test-*.log` - 集成测试日志
- `e2e-test-*.log` - 端到端测试日志
- `performance-test-*.log` - 性能测试日志
- `test-summary-*.txt` - 测试汇总报告

## 9. 持续集成建议

```yaml
# 建议的 CI 流水线
stages:
  - build
  - unit-test
  - integration-test
  - e2e-test
  - performance-test

build:
  stage: build
  script:
    - cd claude-web && mvn clean compile
    - cd claude-agent && npm install

unit-test:
  stage: unit-test
  script:
    - cd claude-web && mvn test
    - cd claude-agent && npm run test:unit

integration-test:
  stage: integration-test
  script:
    - cd claude-agent && PORT=13001 API_KEY=test-key node src/index.js &
    - cd claude-web && java -jar target/claude-web-0.1.0.jar &
    - sleep 5
    - cd claude-agent && npm run test:integration
    - cd claude-web && ./tests/integration/integration-test.sh

e2e-test:
  stage: e2e-test
  script:
    - cd claude-web && ./tests/run-joint-tests.sh
```
