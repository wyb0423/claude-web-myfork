#!/bin/bash

# claude-web 启动脚本（仅连接 Claude Agent）

# 默认配置
JAR_FILE="target/claude-web-1.03.jar"
PID_FILE="/tmp/claude-web.pid"
LOG_FILE="/tmp/claude-web.log"

# ============================================
# Claude Agent 配置
# ============================================
export CLAUDE_AGENT_HOST="${CLAUDE_AGENT_HOST:-127.0.0.1}"
export CLAUDE_AGENT_PORT="${CLAUDE_AGENT_PORT:-8011}"

# API 密钥：默认从 Claude Code 的 JWT 密钥文件读取
# 此密钥与 claude-agent 共享，用于 WebSocket 握手认证
# 如果文件不存在，请手动设置 CLAUDE_AGENT_API_KEY
export CLAUDE_AGENT_API_KEY="${CLAUDE_AGENT_API_KEY:-$(cat /home/sunsw/.codex/jwt_secret.key)}"

# Web UI 配置
export CLAUDE_PASSWORD_ENABLED=false
export CLAUDE_AUTO_APPROVE=false
export PORT=3000
# ============================================

# 检查是否已在运行
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if ps -p "$PID" > /dev/null 2>&1; then
        echo "服务已在运行中 (PID: $PID)"
        echo "请先执行 './stop.sh' 停止，或执行 './restart.sh' 重启"
        exit 1
    else
        rm -f "$PID_FILE"
    fi
fi

# 检查 JAR 文件
if [ ! -f "$JAR_FILE" ]; then
    echo "错误：找不到 JAR 文件: $JAR_FILE"
    echo "请先构建项目: mvn clean package"
    exit 1
fi

# 运行应用
echo "正在启动 claude-web..."
echo "Claude Agent: $CLAUDE_AGENT_HOST:$CLAUDE_AGENT_PORT"
echo "本地端口: $PORT"
echo "日志文件: $LOG_FILE"
echo ""

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 设置 Java 和 Maven 路径（相对于项目根目录）
export JAVA_HOME="$PROJECT_ROOT/.jdk/jdk-17"
export PATH="$JAVA_HOME/bin:$PROJECT_ROOT/.maven/apache-maven-3.9.14/bin:$PATH"

# 启动服务
nohup java -jar "$JAR_FILE" --server.port="$PORT" > "$LOG_FILE" 2>&1 &

PID=$!
echo $PID > "$PID_FILE"

# 等待确保 nohup 完成
disown $PID 2>/dev/null || true

echo "服务已启动，PID: $PID"
echo ""
echo "等待服务初始化..."
sleep 3

# 检查进程是否仍在运行
if ps -p "$PID" > /dev/null 2>&1; then
    echo "服务启动成功！"
    echo "访问地址: http://localhost:$PORT"
else
    echo "错误：服务启动失败。请查看日志: $LOG_FILE"
    rm -f "$PID_FILE"
    exit 1
fi
