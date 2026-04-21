#!/bin/bash

# =============================================================================
# claude-agent + claude-web 联合联调测试
# =============================================================================
# 同时启动两个服务，执行端到端联调测试序列。
#
# 用法:
#   ./run-joint-tests.sh [options]
#
# 选项:
#   --skip-start    跳过服务启动（假设服务已在运行）
#   --skip-stop     测试结束后不停止服务
#   --agent-only    仅测试 claude-agent
#   --web-only      仅测试 claude-web
#   --help          显示帮助
#
# 环境变量:
#   TEST_WEB_URL    - claude-web URL (默认: http://localhost:3000)
#   TEST_AGENT_URL  - claude-agent URL (默认: http://localhost:3001)
# =============================================================================

set -e

# 配置
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
REPORT_DIR="$SCRIPT_DIR/reports"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
SUMMARY_FILE="$REPORT_DIR/joint-test-summary-$TIMESTAMP.txt"

TEST_WEB_URL="${TEST_WEB_URL:-http://localhost:3000}"
TEST_AGENT_URL="${TEST_AGENT_URL:-http://localhost:3001}"
TEST_AGENT_WS="${TEST_AGENT_WS:-ws://localhost:3001}"

SKIP_START=false
SKIP_STOP=false
AGENT_ONLY=false
WEB_ONLY=false

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# 结果统计
PASSED=0
FAILED=0
SKIPPED=0

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-start)
            SKIP_START=true
            shift
            ;;
        --skip-stop)
            SKIP_STOP=true
            shift
            ;;
        --agent-only)
            AGENT_ONLY=true
            shift
            ;;
        --web-only)
            WEB_ONLY=true
            shift
            ;;
        --help)
            echo "claude-agent + claude-web 联合联调测试"
            echo ""
            echo "用法: ./run-joint-tests.sh [options]"
            echo ""
            echo "选项:"
            echo "  --skip-start    跳过服务启动"
            echo "  --skip-stop     测试结束后不停止服务"
            echo "  --agent-only    仅测试 claude-agent"
            echo "  --web-only      仅测试 claude-web"
            echo "  --help          显示帮助"
            echo ""
            echo "环境变量:"
            echo "  TEST_WEB_URL    claude-web URL"
            echo "  TEST_AGENT_URL  claude-agent URL"
            exit 0
            ;;
        *)
            echo "未知选项: $1"
            echo "使用 --help 查看帮助"
            exit 1
            ;;
    esac
done

mkdir -p "$REPORT_DIR"
echo "联合联调测试 - $(date)" > "$SUMMARY_FILE"
echo "Web: $TEST_WEB_URL" >> "$SUMMARY_FILE"
echo "Agent: $TEST_AGENT_URL" >> "$SUMMARY_FILE"
echo "" >> "$SUMMARY_FILE"

log_header() {
    echo ""
    echo -e "${CYAN}=============================================================================${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}=============================================================================${NC}"
    echo ""
}

log_info() {
    echo -e "${GREEN}[信息]${NC} $1" | tee -a "$SUMMARY_FILE"
}

log_error() {
    echo -e "${RED}[错误]${NC} $1" | tee -a "$SUMMARY_FILE"
}

log_warn() {
    echo -e "${YELLOW}[警告]${NC} $1" | tee -a "$SUMMARY_FILE"
}

# ===================== 服务管理 =====================

AGENT_PID=""
WEB_PID=""

start_agent() {
    if curl -s -o /dev/null --max-time 2 "$TEST_AGENT_URL/health" 2>/dev/null; then
        log_info "claude-agent 已在运行"
        return 0
    fi

    log_info "启动 claude-agent..."
    AGENT_DIR="/home/sunsw/code/claude-agent"
    cd "$AGENT_DIR"

    export API_KEY="${API_KEY:-$(cat /home/sunsw/.codex/jwt_secret.key 2>/dev/null || echo 'test-key')}"
    export PORT=3001

    nohup node src/index.js > /tmp/claude-agent-joint.log 2>&1 &
    AGENT_PID=$!
    log_info "claude-agent 已启动 (PID: $AGENT_PID)"

    # 等待启动
    for i in {1..30}; do
        if curl -s -o /dev/null --max-time 2 "$TEST_AGENT_URL/health" 2>/dev/null; then
            log_info "claude-agent 启动成功"
            return 0
        fi
        sleep 1
    done

    log_error "claude-agent 启动超时"
    return 1
}

start_web() {
    if curl -s -o /dev/null --max-time 2 "$TEST_WEB_URL/" 2>/dev/null; then
        log_info "claude-web 已在运行"
        return 0
    fi

    log_info "启动 claude-web..."
    cd "$PROJECT_DIR"

    export CLAUDE_AGENT_HOST=127.0.0.1
    export CLAUDE_AGENT_PORT=3001
    export CLAUDE_AGENT_API_KEY="${CLAUDE_AGENT_API_KEY:-$(cat /home/sunsw/.codex/jwt_secret.key 2>/dev/null || echo 'test-key')}"
    export CLAUDE_PASSWORD_ENABLED=false
    export PORT=3000

    JAR_FILE="target/claude-web-0.1.0.jar"
    if [ ! -f "$JAR_FILE" ]; then
        log_warn "JAR 文件不存在，尝试构建..."
        if [ -x "$PROJECT_DIR/.maven/apache-maven-3.9.14/bin/mvn" ]; then
            "$PROJECT_DIR/.maven/apache-maven-3.9.14/bin/mvn" -q clean package -DskipTests
        else
            mvn -q clean package -DskipTests
        fi
    fi

    nohup java -jar "$JAR_FILE" --server.port=3000 > /tmp/claude-web-joint.log 2>&1 &
    WEB_PID=$!
    log_info "claude-web 已启动 (PID: $WEB_PID)"

    # 等待启动
    for i in {1..30}; do
        if curl -s -o /dev/null --max-time 2 "$TEST_WEB_URL/" 2>/dev/null; then
            log_info "claude-web 启动成功"
            return 0
        fi
        sleep 1
    done

    log_error "claude-web 启动超时"
    return 1
}

stop_services() {
    if [ "$SKIP_STOP" = true ]; then
        log_info "跳过服务停止（--skip-stop）"
        return 0
    fi

    log_info "停止服务..."

    if [ -n "$WEB_PID" ]; then
        kill "$WEB_PID" 2>/dev/null || true
        wait "$WEB_PID" 2>/dev/null || true
        log_info "claude-web 已停止"
    fi

    if [ -n "$AGENT_PID" ]; then
        kill "$AGENT_PID" 2>/dev/null || true
        wait "$AGENT_PID" 2>/dev/null || true
        log_info "claude-agent 已停止"
    fi

    # 清理可能残留的进程
    pkill -f "claude-web-0.1.0.jar" 2>/dev/null || true
    pkill -f "node.*claude-agent/src/index.js" 2>/dev/null || true
}

# ===================== 联调测试 =====================

run_test() {
    local name="$1"
    local script="$2"
    local env_vars="$3"

    log_header "$name"

    if [ ! -x "$script" ]; then
        log_warn "测试脚本不存在或不可执行: $script"
        SKIPPED=$((SKIPPED + 1))
        return
    fi

    LOG_FILE="$REPORT_DIR/$(basename "$script" .sh)-$TIMESTAMP.log"

    if eval "$env_vars" "$script" > "$LOG_FILE" 2>&1; then
        log_info "$name 通过"
        PASSED=$((PASSED + 1))
    else
        log_error "$name 失败（日志: $LOG_FILE）"
        FAILED=$((FAILED + 1))
    fi
}

# ===================== 主程序 =====================

main() {
    echo "============================================================================="
    echo "  claude-agent + claude-web 联合联调测试"
    echo "  Web: $TEST_WEB_URL"
    echo "  Agent: $TEST_AGENT_URL"
    echo "============================================================================="
    echo ""

    # 设置 trap 确保退出时停止服务
    trap stop_services EXIT

    # 启动服务
    if [ "$SKIP_START" = false ]; then
        if [ "$WEB_ONLY" = false ]; then
            start_agent || exit 1
        fi
        if [ "$AGENT_ONLY" = false ]; then
            start_web || exit 1
        fi
        sleep 2
    else
        log_info "跳过服务启动（--skip-start）"
    fi

    # 验证服务状态
    log_header "服务状态检查"

    if [ "$WEB_ONLY" = false ]; then
        if curl -s -o /dev/null --max-time 3 "$TEST_AGENT_URL/health" 2>/dev/null; then
            log_info "claude-agent 可访问"
        else
            log_error "claude-agent 不可访问"
            exit 1
        fi
    fi

    if [ "$AGENT_ONLY" = false ]; then
        if curl -s -o /dev/null --max-time 3 "$TEST_WEB_URL/" 2>/dev/null; then
            log_info "claude-web 可访问"
        else
            log_error "claude-web 不可访问"
            exit 1
        fi
    fi

    # 执行测试
    if [ "$AGENT_ONLY" = true ]; then
        run_test "claude-agent 单元测试" \
            "/home/sunsw/code/claude-agent/tests/scripts/run-all-tests.sh" \
            "cd /home/sunsw/code/claude-agent && PORT=3001 API_KEY=test-key"
    elif [ "$WEB_ONLY" = true ]; then
        run_test "claude-web 单元测试" \
            "$SCRIPT_DIR/run-all-tests.sh" \
            "TEST_BASE_URL=$TEST_WEB_URL"
    else
        # 联合联调测试序列
        run_test "claude-web 集成测试" \
            "$SCRIPT_DIR/integration/integration-test.sh" \
            "TEST_BASE_URL=$TEST_WEB_URL"

        run_test "claude-web E2E 测试" \
            "$SCRIPT_DIR/e2e/e2e-test.sh" \
            "TEST_BASE_URL=$TEST_WEB_URL"

        run_test "claude-web WebSocket 传输测试" \
            "$SCRIPT_DIR/websocket/websocket-test.sh" \
            "TEST_WEB_URL=$TEST_WEB_URL TEST_AGENT_WS=$TEST_AGENT_WS"

        run_test "claude-web SSE 事件流测试" \
            "$SCRIPT_DIR/integration/sse-test.sh" \
            "TEST_BASE_URL=$TEST_WEB_URL"

        run_test "claude-agent 兼容性测试" \
            "/home/sunsw/code/claude-agent/tests/scripts/compatibility-test.mjs" \
            "cd /home/sunsw/code/claude-agent"

        # 重连测试可选（会重启 agent）
        log_header "重连测试（可选）"
        echo -e "${YELLOW}重连测试会临时停止并重启 claude-agent，是否执行？[y/N]${NC}"
        read -r -t 5 REPLY || REPLY="n"
        if [[ "$REPLY" =~ ^[Yy]$ ]]; then
            run_test "claude-web 重连测试" \
                "$SCRIPT_DIR/integration/reconnect-test.sh" \
                "TEST_WEB_URL=$TEST_WEB_URL TEST_AGENT_URL=$TEST_AGENT_URL"
        else
            log_warn "跳过重连测试"
            SKIPPED=$((SKIPPED + 1))
        fi
    fi

    # 汇总
    log_header "联合联调测试汇总"
    echo -e "${GREEN}通过: $PASSED${NC}"
    if [ "$FAILED" -gt 0 ]; then
        echo -e "${RED}失败: $FAILED${NC}"
    fi
    if [ "$SKIPPED" -gt 0 ]; then
        echo -e "${YELLOW}跳过: $SKIPPED${NC}"
    fi
    echo -e "总计: $((PASSED + FAILED + SKIPPED))"
    echo ""
    echo -e "报告目录: ${BLUE}$REPORT_DIR${NC}"
    echo -e "汇总文件: ${BLUE}$SUMMARY_FILE${NC}"

    echo "" >> "$SUMMARY_FILE"
    echo "=================================================================" >> "$SUMMARY_FILE"
    echo "  汇总" >> "$SUMMARY_FILE"
    echo "=================================================================" >> "$SUMMARY_FILE"
    echo "  通过: $PASSED" >> "$SUMMARY_FILE"
    echo "  失败: $FAILED" >> "$SUMMARY_FILE"
    echo "  跳过: $SKIPPED" >> "$SUMMARY_FILE"
    echo "=================================================================" >> "$SUMMARY_FILE"

    if [ "$FAILED" -gt 0 ]; then
        exit 1
    fi
}

main "$@"
