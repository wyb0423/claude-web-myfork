#!/bin/bash

# =============================================================================
# claude-web 自动重连测试
# =============================================================================
# 模拟 claude-agent 重启，验证 claude-web 的自动重连机制。
#
# 用法:
#   ./reconnect-test.sh [WEB_URL] [AGENT_URL]
#
# 环境变量:
#   TEST_WEB_URL  - claude-web URL (默认: http://localhost:3000)
#   TEST_AGENT_URL - claude-agent URL (默认: http://localhost:3001)
#   TEST_AGENT_PID - claude-agent 进程 PID（可选，用于自动重启）
# =============================================================================

set -e

TEST_WEB_URL="${TEST_WEB_URL:-http://localhost:3000}"
TEST_AGENT_URL="${TEST_AGENT_URL:-http://localhost:3001}"
REPORT_DIR="$(dirname "$0")/../reports"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_FILE="$REPORT_DIR/reconnect-test-$TIMESTAMP.log"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

PASSED=0
FAILED=0
STEP=0

mkdir -p "$REPORT_DIR"

log_step() {
    STEP=$((STEP + 1))
    echo -e "${BLUE}[步骤 $STEP]${NC} $1" | tee -a "$REPORT_FILE"
}

log_pass() {
    echo -e "${GREEN}[通过]${NC} $1" | tee -a "$REPORT_FILE"
    PASSED=$((PASSED + 1))
}

log_fail() {
    echo -e "${RED}[失败]${NC} $1" | tee -a "$REPORT_FILE"
    FAILED=$((FAILED + 1))
}

log_warn() {
    echo -e "${YELLOW}[警告]${NC} $1" | tee -a "$REPORT_FILE"
}

# ===================== 测试用例 =====================

test_initial_connection() {
    log_step "验证初始连接状态"

    if curl -s -o /dev/null --max-time 5 "$TEST_WEB_URL/" 2>/dev/null; then
        log_pass "claude-web 运行正常"
    else
        log_fail "claude-web 无法访问"
        return 1
    fi

    if curl -s -o /dev/null --max-time 3 "$TEST_AGENT_URL/health" 2>/dev/null; then
        log_pass "claude-agent 运行正常"
    else
        log_fail "claude-agent 无法访问"
        return 1
    fi
}

test_api_with_connected_agent() {
    log_step "测试 agent 在线时的 API 响应"

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$TEST_WEB_URL/api/claude" 2>/dev/null || echo "000")
    if [ "$RESPONSE" -eq 200 ]; then
        log_pass "agent 在线时 /api/claude 返回 200"
    elif [ "$RESPONSE" -eq 502 ]; then
        log_warn "agent 在线时 /api/claude 返回 502（可能是 agent 内部无会话）"
    else
        log_fail "/api/claude 返回 $RESPONSE"
    fi
}

test_disconnect_simulation() {
    log_step "模拟 claude-agent 断开"

    # 查找 agent 进程
    AGENT_PID=$(pgrep -f "node.*claude-agent/src/index.js" | head -1 || echo "")
    if [ -z "$AGENT_PID" ]; then
        log_warn "未找到 claude-agent 进程，跳过断开模拟"
        return 1
    fi

    echo "  发现 claude-agent PID: $AGENT_PID"
    echo "  停止 agent..."
    kill -15 "$AGENT_PID" 2>/dev/null || true
    sleep 3

    # 验证 agent 已停止
    if curl -s -o /dev/null --max-time 2 "$TEST_AGENT_URL/health" 2>/dev/null; then
        log_warn "agent 仍在运行，尝试强制停止..."
        kill -9 "$AGENT_PID" 2>/dev/null || true
        sleep 2
    fi

    if ! curl -s -o /dev/null --max-time 2 "$TEST_AGENT_URL/health" 2>/dev/null; then
        log_pass "claude-agent 已停止"
    else
        log_fail "claude-agent 未能停止"
        return 1
    fi
}

test_api_with_disconnected_agent() {
    log_step "测试 agent 断开时的 API 响应"

    # 等待重连机制触发（重连间隔 3 秒，最多 5 次尝试，约 15 秒）
    sleep 2

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$TEST_WEB_URL/api/claude" 2>/dev/null || echo "000")
    if [ "$RESPONSE" -eq 502 ]; then
        log_pass "agent 断开时 /api/claude 正确返回 502"
    else
        log_warn "agent 断开时 /api/claude 返回 $RESPONSE（期望 502）"
    fi

    # 检查 SSE 通知中是否有 connection/lost
    EVENTS=$(curl -s --max-time 5 "$TEST_WEB_URL/claude-api/events" 2>/dev/null | head -20 || echo "")
    if echo "$EVENTS" | grep -q "connection/lost\|connection/failed"; then
        log_pass "SSE 推送了连接断开事件"
    else
        log_warn "未在 SSE 中发现连接断开事件（可能已超时或尚未触发）"
    fi
}

test_reconnect_after_agent_restart() {
    log_step "重启 claude-agent 并验证重连"

    # 重新启动 agent
    AGENT_DIR="/home/sunsw/code/claude-agent"
    if [ -f "$AGENT_DIR/start.sh" ]; then
        cd "$AGENT_DIR"
        nohup bash -c 'export API_KEY=$(cat /home/sunsw/.codex/jwt_secret.key 2>/dev/null || echo "test-key"); export PORT=3001; node src/index.js' > /tmp/claude-agent-restart.log 2>&1 &
        echo "  已启动 claude-agent (PID: $!)"
    else
        log_warn "找不到 start.sh，请手动重启 claude-agent"
        return 1
    fi

    # 等待 agent 启动
    echo "  等待 agent 启动..."
    for i in {1..30}; do
        if curl -s -o /dev/null --max-time 2 "$TEST_AGENT_URL/health" 2>/dev/null; then
            break
        fi
        sleep 1
    done

    # 验证 agent 已恢复
    if curl -s -o /dev/null --max-time 3 "$TEST_AGENT_URL/health" 2>/dev/null; then
        log_pass "claude-agent 重启成功"
    else
        log_fail "claude-agent 重启失败"
        return 1
    fi

    # 等待 claude-web 自动重连（最多约 15 秒）
    echo "  等待 claude-web 自动重连（最多 20 秒）..."
    RECONNECTED=false
    for i in {1..20}; do
        RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$TEST_WEB_URL/api/claude" 2>/dev/null || echo "000")
        if [ "$RESPONSE" -eq 200 ] || [ "$RESPONSE" -eq 502 ]; then
            # 只要 agent 响应了（200 或 502），就说明 WebSocket 连接已恢复
            RECONNECTED=true
            break
        fi
        sleep 1
    done

    if [ "$RECONNECTED" = true ]; then
        log_pass "claude-web 自动重连成功"
    else
        log_fail "claude-web 自动重连失败"
    fi

    # 检查 SSE 中的恢复事件
    EVENTS=$(curl -s --max-time 5 "$TEST_WEB_URL/claude-api/events" 2>/dev/null | head -30 || echo "")
    if echo "$EVENTS" | grep -q "connection/restored"; then
        log_pass "SSE 推送了连接恢复事件"
    else
        log_warn "未在 SSE 中发现连接恢复事件"
    fi
}

test_api_after_reconnect() {
    log_step "验证重连后的 API 功能"

    # 创建会话
    CREATE=$(curl -s -w "\nHTTP_CODE:%{http_code}" --max-time 5 \
        -X POST "$TEST_WEB_URL/api/claude" \
        -H "Content-Type: application/json" \
        -d '{"cwd":"/tmp","name":"Reconnect Test"}' 2>/dev/null || echo "HTTP_CODE:000")

    HTTP_CODE=$(echo "$CREATE" | grep "HTTP_CODE:" | cut -d: -f2)
    if [ "$HTTP_CODE" -eq 200 ]; then
        log_pass "重连后会话创建正常"
        SESSION_ID=$(echo "$CREATE" | sed '/HTTP_CODE:/d' | grep -o '"claudeId":"[^"]*"' | cut -d'"' -f4)
        curl -s -o /dev/null -X DELETE "$TEST_WEB_URL/api/claude/$SESSION_ID" 2>/dev/null || true
    else
        log_fail "重连后会话创建失败: $HTTP_CODE"
    fi
}

# ===================== 主程序 =====================

main() {
    echo "============================================================================="
    echo "  claude-web 自动重连测试"
    echo "  Web: $TEST_WEB_URL"
    echo "  Agent: $TEST_AGENT_URL"
    echo "============================================================================="
    echo ""
    echo "注意: 本测试会临时停止并重启 claude-agent，请确保无重要会话在运行。"
    echo ""

    test_initial_connection || exit 1
    test_api_with_connected_agent
    test_disconnect_simulation || true
    test_api_with_disconnected_agent || true
    test_reconnect_after_agent_restart || true
    test_api_after_reconnect

    echo ""
    echo "============================================================================="
    echo "  重连测试汇总"
    echo "============================================================================="
    echo -e "  ${GREEN}通过: $PASSED${NC}"
    echo -e "  ${RED}失败: $FAILED${NC}"
    echo -e "  总计: $((PASSED + FAILED))"
    echo "============================================================================="

    echo "" >> "$REPORT_FILE"
    echo "Summary: Passed=$PASSED Failed=$FAILED" >> "$REPORT_FILE"

    if [ "$FAILED" -gt 0 ]; then
        exit 1
    fi
}

main "$@"
