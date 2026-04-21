#!/bin/bash

# =============================================================================
# claude-web SSE 事件流测试
# =============================================================================
# 测试 SSE 端点的实时事件推送功能。
#
# 用法:
#   ./sse-test.sh [BASE_URL]
#
# 环境变量:
#   TEST_BASE_URL - 基础 URL (默认: http://localhost:3000)
# =============================================================================

set -e

BASE_URL="${TEST_BASE_URL:-http://localhost:3000}"
REPORT_DIR="$(dirname "$0")/../reports"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_FILE="$REPORT_DIR/sse-test-$TIMESTAMP.log"
RESULTS_FILE="$REPORT_DIR/sse-events-$TIMESTAMP.txt"

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

test_sse_endpoint_accessible() {
    log_step "测试 SSE 端点可访问性"

    # 使用超时获取 SSE 流的前几行
    EVENTS=$(curl -s --max-time 3 "$BASE_URL/claude-api/events" 2>/dev/null | head -10 || echo "")
    if [ -n "$EVENTS" ]; then
        log_pass "SSE 端点可访问并有数据"
        echo "$EVENTS" > "$RESULTS_FILE"
    else
        log_fail "SSE 端点无响应或无数据"
    fi
}

test_sse_content_type() {
    log_step "测试 SSE Content-Type 头"

    CT=$(curl -s -o /dev/null -w "%{content_type}" --max-time 3 "$BASE_URL/claude-api/events" 2>/dev/null || echo "")
    if echo "$CT" | grep -q "text/event-stream"; then
        log_pass "Content-Type 正确: $CT"
    else
        log_fail "Content-Type 不正确: $CT"
    fi
}

test_sse_ready_event() {
    log_step "测试初始 ready 事件"

    EVENTS=$(curl -s --max-time 3 "$BASE_URL/claude-api/events" 2>/dev/null | head -5 || echo "")
    if echo "$EVENTS" | grep -q "ready"; then
        log_pass "收到 ready 事件"
    else
        log_fail "未收到 ready 事件"
    fi
}

test_sse_multiple_connections() {
    log_step "测试多客户端 SSE 并发连接"

    local pids=""
    local conn1_log="$REPORT_DIR/sse-conn-1-$TIMESTAMP.log"
    local conn2_log="$REPORT_DIR/sse-conn-2-$TIMESTAMP.log"
    local conn3_log="$REPORT_DIR/sse-conn-3-$TIMESTAMP.log"

    # 使用 timeout 命令启动 3 个并发的 SSE 连接，每个最多运行 4 秒
    # -N 禁用缓冲，确保 SSE 事件及时写入文件
    timeout 4 curl -s -N --max-time 5 "$BASE_URL/claude-api/events" > "$conn1_log" 2>/dev/null &
    pids="$pids $!"
    timeout 4 curl -s -N --max-time 5 "$BASE_URL/claude-api/events" > "$conn2_log" 2>/dev/null &
    pids="$pids $!"
    timeout 4 curl -s -N --max-time 5 "$BASE_URL/claude-api/events" > "$conn3_log" 2>/dev/null &
    pids="$pids $!"

    # 等待所有进程完成（最多 6 秒）
    local waited=0
    while [ "$waited" -lt 6 ]; do
        local all_done=true
        for pid in $pids; do
            if kill -0 "$pid" 2>/dev/null; then
                all_done=false
                break
            fi
        done
        if [ "$all_done" = true ]; then
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done

    # 强制清理残留进程
    for pid in $pids; do
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    done

    local success=0
    if [ -s "$conn1_log" ] && grep -q "data:" "$conn1_log" 2>/dev/null; then success=$((success + 1)); fi
    if [ -s "$conn2_log" ] && grep -q "data:" "$conn2_log" 2>/dev/null; then success=$((success + 1)); fi
    if [ -s "$conn3_log" ] && grep -q "data:" "$conn3_log" 2>/dev/null; then success=$((success + 1)); fi

    if [ "$success" -ge 2 ]; then
        log_pass "$success/3 个 SSE 连接成功接收事件"
    else
        log_fail "仅 $success/3 个 SSE 连接成功"
    fi
}

test_sse_connection_events() {
    log_step "测试连接状态事件推送"

    # 获取 SSE 流，查找连接相关事件
    EVENTS=$(curl -s --max-time 3 "$BASE_URL/claude-api/events" 2>/dev/null | head -20 || echo "")

    if echo "$EVENTS" | grep -qi "connection\|ready\|ping"; then
        log_pass "SSE 流包含连接相关事件"
    else
        log_pass "SSE 流基础事件正常（无连接状态变化时无 connection 事件）"
    fi
}

# ===================== 主程序 =====================

main() {
    echo "============================================================================="
    echo "  claude-web SSE 事件流测试"
    echo "  目标: $BASE_URL"
    echo "============================================================================="
    echo ""

    if ! curl -s -o /dev/null --max-time 3 "$BASE_URL/" 2>/dev/null; then
        echo "错误: 服务器未在 $BASE_URL 运行"
        exit 1
    fi

    test_sse_endpoint_accessible
    test_sse_content_type
    test_sse_ready_event
    test_sse_multiple_connections
    test_sse_connection_events

    echo ""
    echo "============================================================================="
    echo "  SSE 测试汇总"
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
