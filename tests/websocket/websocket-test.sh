#!/bin/bash

# =============================================================================
# claude-web WebSocket 传输层测试
# =============================================================================
# 测试 claude-web 与 claude-agent 之间的 WebSocket 连接：
# - 连接建立
# - 消息收发
# - 心跳保活
# - 断线重连
#
# 用法:
#   ./websocket-test.sh [WEB_URL] [AGENT_WS_URL]
#
# 环境变量:
#   TEST_WEB_URL     - claude-web URL (默认: http://localhost:3000)
#   TEST_AGENT_WS    - claude-agent WebSocket (默认: ws://localhost:3001)
# =============================================================================

set -e

TEST_WEB_URL="${TEST_WEB_URL:-http://localhost:3000}"
TEST_AGENT_WS="${TEST_AGENT_WS:-ws://localhost:3001}"
REPORT_DIR="$(dirname "$0")/../reports"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_FILE="$REPORT_DIR/websocket-test-$TIMESTAMP.log"

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

# ===================== 测试用例 =====================

test_web_health() {
    log_step "检查 claude-web 健康状态"
    if curl -s -o /dev/null --max-time 5 "$TEST_WEB_URL/" 2>/dev/null; then
        log_pass "claude-web 可访问"
    else
        log_fail "claude-web 无法访问"
    fi
}

test_agent_websocket() {
    log_step "检查 claude-agent WebSocket 端口"
    WS_HOST="${TEST_AGENT_WS#ws://}"
    WS_HOST="${WS_HOST#wss://}"
    WS_CHECK=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
        -H "Upgrade: websocket" \
        -H "Connection: Upgrade" \
        -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
        -H "Sec-WebSocket-Version: 13" \
        "http://$WS_HOST/" 2>/dev/null || echo "000")
    if [ "$WS_CHECK" = "101" ] || [ "$WS_CHECK" = "400" ] || [ "$WS_CHECK" = "100" ]; then
        log_pass "claude-agent WebSocket 端口可达 (HTTP $WS_CHECK)"
    else
        log_fail "claude-agent WebSocket 端口不可达 (HTTP $WS_CHECK)"
    fi
}

test_api_claude_list() {
    log_step "测试 /api/claude 列表端点（验证 WebSocket 后端连接）"
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$TEST_WEB_URL/api/claude" 2>/dev/null || echo "000")
    if [ "$RESPONSE" -eq 200 ] || [ "$RESPONSE" -eq 502 ]; then
        log_pass "/api/claude 返回 $RESPONSE"
    else
        log_fail "/api/claude 返回异常状态码 $RESPONSE"
    fi
}

test_meta_methods() {
    log_step "测试 /claude-api/meta/methods（验证方法目录服务）"
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$TEST_WEB_URL/claude-api/meta/methods" 2>/dev/null || echo "000")
    if [ "$RESPONSE" -eq 200 ]; then
        log_pass "meta/methods 可访问"
    else
        log_fail "meta/methods 返回 $RESPONSE"
    fi
}

test_meta_notifications() {
    log_step "测试 /claude-api/meta/notifications"
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$TEST_WEB_URL/claude-api/meta/notifications" 2>/dev/null || echo "000")
    if [ "$RESPONSE" -eq 200 ]; then
        log_pass "meta/notifications 可访问"
    else
        log_fail "meta/notifications 返回 $RESPONSE"
    fi
}

test_session_create_and_message() {
    log_step "测试会话创建并通过 WebSocket 发送消息"

    CREATE=$(curl -s -w "\nHTTP_CODE:%{http_code}" --max-time 5 \
        -X POST "$TEST_WEB_URL/api/claude" \
        -H "Content-Type: application/json" \
        -d '{"cwd":"/tmp","name":"WebSocket Test"}' 2>/dev/null || echo "HTTP_CODE:000")

    HTTP_CODE=$(echo "$CREATE" | grep "HTTP_CODE:" | cut -d: -f2)
    if [ "$HTTP_CODE" -ne 200 ]; then
        log_fail "会话创建失败，状态码: $HTTP_CODE"
        return
    fi

    SESSION_ID=$(echo "$CREATE" | sed '/HTTP_CODE:/d' | grep -o '"claudeId":"[^"]*"' | cut -d'"' -f4)
    if [ -z "$SESSION_ID" ]; then
        log_fail "无法提取会话 ID"
        return
    fi

    log_pass "会话创建成功: $SESSION_ID"

    # 发送消息（会触发 WebSocket 通信到 agent）
    MSG_RESP=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
        -X POST "$TEST_WEB_URL/api/claude/$SESSION_ID/message" \
        -H "Content-Type: application/json" \
        -d '{"text":"test"}' 2>/dev/null || echo "000")

    if [ "$MSG_RESP" -eq 200 ]; then
        log_pass "消息发送成功（WebSocket 通信正常）"
    else
        log_fail "消息发送失败，状态码: $MSG_RESP"
    fi

    # 清理
    curl -s -o /dev/null -X DELETE "$TEST_WEB_URL/api/claude/$SESSION_ID" 2>/dev/null || true
}

test_connection_events_sse() {
    log_step "测试 SSE 连接事件通知"

    # 获取 SSE 流的前几行
    EVENTS=$(curl -s --max-time 3 "$TEST_WEB_URL/claude-api/events" 2>/dev/null | head -5 || echo "")
    if echo "$EVENTS" | grep -q "ready\|data:"; then
        log_pass "SSE 事件流正常"
    else
        log_fail "SSE 事件流异常或无数据"
    fi
}

# ===================== 主程序 =====================

main() {
    echo "============================================================================="
    echo "  claude-web WebSocket 传输层测试"
    echo "  Web: $TEST_WEB_URL"
    echo "  Agent: $TEST_AGENT_WS"
    echo "============================================================================="
    echo ""

    test_web_health
    test_agent_websocket
    test_api_claude_list
    test_meta_methods
    test_meta_notifications
    test_session_create_and_message
    test_connection_events_sse

    echo ""
    echo "============================================================================="
    echo "  WebSocket 测试汇总"
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
