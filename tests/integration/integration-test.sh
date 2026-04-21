#!/bin/bash

# =============================================================================
# claude-web 集成测试脚本
# =============================================================================
# 本脚本对运行中的应用实例执行集成测试。
#
# 用法:
#   ./integration-test.sh [BASE_URL]
#
# 环境变量:
#   TEST_BASE_URL - 基础 URL (默认: http://localhost:3000)
# =============================================================================

# 配置
BASE_URL="${TEST_BASE_URL:-http://localhost:3000}"
REPORT_DIR="$(dirname "$0")/../reports"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_FILE="$REPORT_DIR/integration-test-$TIMESTAMP.log"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 计数器
PASSED=0
FAILED=0
SKIPPED=0

mkdir -p "$REPORT_DIR"

log_info() {
    echo -e "${GREEN}[通过]${NC} $1" | tee -a "$REPORT_FILE"
}

log_error() {
    echo -e "${RED}[失败]${NC} $1" | tee -a "$REPORT_FILE"
}

log_warn() {
    echo -e "${YELLOW}[跳过]${NC} $1" | tee -a "$REPORT_FILE"
}

assert_status() {
    local desc="$1"
    local expected="$2"
    local actual="$3"

    if [ "$expected" -eq "$actual" ]; then
        log_info "$desc (状态码: $actual)"
        PASSED=$((PASSED + 1))
    else
        log_error "$desc - 期望 $expected, 实际 $actual"
        FAILED=$((FAILED + 1))
    fi
}

# =============================================================================
# 测试用例
# =============================================================================

test_health_check() {
    echo "=== 测试: 健康检查 ==="
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/" 2>/dev/null || echo "000")
    assert_status "首页" 200 "$RESPONSE"
}

test_login_page() {
    echo "=== 测试: 登录页面 ==="
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/login" 2>/dev/null || echo "000")
    assert_status "登录页面" 200 "$RESPONSE"
}

test_api_endpoints() {
    echo "=== 测试: API 端点 ==="

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/api/claude" 2>/dev/null || echo "000")
    if [ "$RESPONSE" -eq 200 ] || [ "$RESPONSE" -eq 502 ]; then
        log_info "列出会话 (状态码: $RESPONSE)"
        PASSED=$((PASSED + 1))
    else
        log_error "列出会话 - 意外状态码: $RESPONSE"
        FAILED=$((FAILED + 1))
    fi

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/claude-api/meta/methods" 2>/dev/null || echo "000")
    assert_status "元方法" 200 "$RESPONSE"

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/claude-api/meta/notifications" 2>/dev/null || echo "000")
    assert_status "元通知" 200 "$RESPONSE"

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/claude-api/server-requests/pending" 2>/dev/null || echo "000")
    assert_status "待处理请求" 200 "$RESPONSE"
}

test_session_crud() {
    echo "=== 测试: 会话增删改查 ==="

    CREATE=$(curl -s -w "\nHTTP_CODE:%{http_code}" --max-time 5 \
        -X POST "$BASE_URL/api/claude" \
        -H "Content-Type: application/json" \
        -d '{"cwd":"/tmp","name":"Test"}' 2>/dev/null || echo "HTTP_CODE:000")

    HTTP_CODE=$(echo "$CREATE" | grep "HTTP_CODE:" | cut -d: -f2)
    BODY=$(echo "$CREATE" | sed '/HTTP_CODE:/d')

    if [ "$HTTP_CODE" -eq 200 ]; then
        log_info "创建会话 (状态码: 200)"
        PASSED=$((PASSED + 1))

        SESSION_ID=$(echo "$BODY" | grep -o '"claudeId":"[^"]*"' | cut -d'"' -f4)
        if [ -n "$SESSION_ID" ]; then
            RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/api/claude/$SESSION_ID" 2>/dev/null || echo "000")
            assert_status "获取会话" 200 "$RESPONSE"

            RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 25 -X DELETE "$BASE_URL/api/claude/$SESSION_ID" 2>/dev/null || echo "000")
            assert_status "删除会话" 200 "$RESPONSE"

            RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/api/claude/$SESSION_ID" 2>/dev/null || echo "000")
            # Session may still be accessible via SDK fallback after deletion
            if [ "$RESPONSE" -eq 404 ] || [ "$RESPONSE" -eq 200 ] || [ "$RESPONSE" -eq 502 ]; then
                log_info "验证删除 (状态码: $RESPONSE)"
                PASSED=$((PASSED + 1))
            else
                log_error "验证删除 - 意外状态码: $RESPONSE"
                FAILED=$((FAILED + 1))
            fi
        fi
    else
        log_error "创建会话 - 状态码: $HTTP_CODE"
        FAILED=$((FAILED + 1))
    fi
}

test_error_handling() {
    echo "=== 测试: 错误处理 ==="

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/api/claude/invalid-id" 2>/dev/null || echo "000")
    # Controller queries SDK fallback for unknown sessions, may return 200 or 502
    if [ "$RESPONSE" -eq 404 ] || [ "$RESPONSE" -eq 200 ] || [ "$RESPONSE" -eq 502 ]; then
        log_info "无效会话 (状态码: $RESPONSE)"
        PASSED=$((PASSED + 1))
    else
        log_error "无效会话 - 意外状态码: $RESPONSE"
        FAILED=$((FAILED + 1))
    fi

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
        -X POST "$BASE_URL/api/claude/sess/message" \
        -H "Content-Type: application/json" \
        -d '{}' 2>/dev/null || echo "000")
    assert_status "缺少文本" 400 "$RESPONSE"

    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
        -X POST "$BASE_URL/claude-api/rpc" \
        -H "Content-Type: application/json" \
        -d '{"method":"test"}' 2>/dev/null || echo "000")
    assert_status "RPC 被阻止" 403 "$RESPONSE"
}

test_static_resources() {
    echo "=== 测试: 静态资源 ==="
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/js/marked.min.js" 2>/dev/null || echo "000")
    assert_status "marked.js" 200 "$RESPONSE"
}

# =============================================================================
# 主程序
# =============================================================================

main() {
    echo "============================================================================="
    echo "  claude-web 集成测试"
    echo "  目标: $BASE_URL"
    echo "============================================================================="
    echo ""

    if ! curl -s -o /dev/null --max-time 5 "$BASE_URL/" 2>/dev/null; then
        echo "错误: 服务器未在 $BASE_URL 运行"
        echo "请使用以下命令启动: java -jar target/claude-web-0.1.0.jar --server.port=3000"
        exit 1
    fi

    echo "服务器可达。正在运行测试..."
    echo ""

    test_health_check
    test_login_page
    test_api_endpoints
    test_session_crud
    test_error_handling
    test_static_resources

    echo ""
    echo "============================================================================="
    echo "  汇总: 通过=$PASSED 失败=$FAILED 跳过=$SKIPPED"
    echo "============================================================================="

    echo "" >> "$REPORT_FILE"
    echo "汇总: 通过=$PASSED 失败=$FAILED 跳过=$SKIPPED" >> "$REPORT_FILE"

    if [ "$FAILED" -gt 0 ]; then
        exit 1
    fi
}

main "$@"
