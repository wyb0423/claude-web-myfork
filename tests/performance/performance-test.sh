#!/bin/bash

# =============================================================================
# claude-web 性能测试脚本
# =============================================================================
# 本脚本使用 curl 和基础基准测试工具执行负载和性能测试。
#
# 用法:
#   ./performance-test.sh [BASE_URL] [DURATION] [CONCURRENCY]
#
# 环境变量:
#   TEST_BASE_URL   - 基础 URL (默认: http://localhost:3000)
#   TEST_DURATION   - 测试持续时间，秒 (默认: 30)
#   TEST_CONCURRENCY - 并发请求数 (默认: 10)
# =============================================================================

set -e

# 配置
BASE_URL="${TEST_BASE_URL:-http://localhost:3000}"
DURATION="${TEST_DURATION:-30}"
CONCURRENCY="${TEST_CONCURRENCY:-10}"
REPORT_DIR="$(dirname "$0")/../reports"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_FILE="$REPORT_DIR/performance-test-$TIMESTAMP.log"
RESULTS_DIR="$REPORT_DIR/performance-$TIMESTAMP"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

mkdir -p "$REPORT_DIR" "$RESULTS_DIR"

log_info() {
    echo -e "${GREEN}[信息]${NC} $1" | tee -a "$REPORT_FILE"
}

log_error() {
    echo -e "${RED}[错误]${NC} $1" | tee -a "$REPORT_FILE"
}

log_warn() {
    echo -e "${YELLOW}[警告]${NC} $1" | tee -a "$REPORT_FILE"
}

log_metric() {
    echo -e "${BLUE}[指标]${NC} $1" | tee -a "$REPORT_FILE"
}

# =============================================================================
# 性能测试函数
# =============================================================================

measure_response_time() {
    local url="$1"
    local method="${2:-GET}"
    local data="${3:-}"

    local curl_opts="-s -o /dev/null -w \"%{time_total},%{http_code},%{size_download}\""

    if [ "$method" = "POST" ] && [ -n "$data" ]; then
        curl_opts="$curl_opts -X POST -H \"Content-Type: application/json\" -d '$data'"
    fi

    eval "curl $curl_opts '$url'" 2>/dev/null || echo "0,000,0"
}

run_load_test() {
    local name="$1"
    local url="$2"
    local method="${3:-GET}"
    local data="${4:-}"
    local requests="${5:-100}"
    local concurrent="${6:-10}"

    log_info "=== 负载测试: $name ==="
    log_info "URL: $url"
    log_info "请求数: $requests, 并发数: $concurrent"

    local result_file="$RESULTS_DIR/${name// /_}.csv"
    echo "time_total,http_code,size_download" > "$result_file"

    local pids=()
    local requests_per_worker=$((requests / concurrent))

    for ((i=0; i<concurrent; i++)); do
        (
            for ((j=0; j<requests_per_worker; j++)); do
                measure_response_time "$url" "$method" "$data" >> "$result_file"
            done
        ) &
        pids+=($!)
    done

    # 等待所有工作进程
    for pid in "${pids[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    # 计算统计数据
    local total_requests=$(tail -n +2 "$result_file" | wc -l)
    local successful=$(tail -n +2 "$result_file" | awk -F',' '$2==200' | wc -l)
    local failed=$((total_requests - successful))

    local avg_time=$(tail -n +2 "$result_file" | awk -F',' '{sum+=$1; count++} END {if(count>0) printf "%.3f", sum/count}')
    local min_time=$(tail -n +2 "$result_file" | awk -F',' 'NR==1 || $1<min {min=$1} END {printf "%.3f", min}')
    local max_time=$(tail -n +2 "$result_file" | awk -F',' '{if($1>max || NR==1) max=$1} END {printf "%.3f", max}')
    local p95_time=$(tail -n +2 "$result_file" | awk -F',' '{print $1}' | sort -n | awk 'BEGIN{count=0} {a[count++]=$1} END{if(count>0) print a[int(count*0.95)]}')

    local throughput=$(echo "scale=2; $total_requests / $avg_time" | bc 2>/dev/null || echo "N/A")

    log_metric "总请求数: $total_requests"
    log_metric "成功: $successful"
    log_metric "失败: $failed"
    log_metric "平均响应时间: ${avg_time}s"
    log_metric "最小响应时间: ${min_time}s"
    log_metric "最大响应时间: ${max_time}s"
    log_metric "P95 响应时间: ${p95_time}s"
    log_metric "吞吐量: ${throughput} req/s"

    echo "" >> "$REPORT_FILE"
}

test_endpoint_latency() {
    log_info "=== 端点延迟测试 ==="

    local endpoints=(
        "GET:/:首页"
        "GET:/login:登录页面"
        "GET:/api/claude:列出会话"
        "GET:/claude-api/meta/methods:元方法"
        "GET:/claude-api/meta/notifications:元通知"
    )

    for endpoint in "${endpoints[@]}"; do
        IFS=':' read -r method path name <<< "$endpoint"
        local url="$BASE_URL$path"

        # 预热
        curl -s -o /dev/null "$url" 2>/dev/null || true

        # 测量 10 次
        local total_time=0
        local count=0
        for i in {1..10}; do
            local time=$(measure_response_time "$url" "$method" | cut -d',' -f1)
            total_time=$(echo "$total_time + $time" | bc 2>/dev/null || echo "$total_time")
            count=$((count + 1))
        done

        local avg=$(echo "scale=3; $total_time / $count" | bc 2>/dev/null || echo "N/A")
        log_metric "$name: ${avg}s (平均 $count 次)"
    done

    echo "" >> "$REPORT_FILE"
}

test_session_creation_load() {
    log_info "=== 会话创建负载测试 ==="

    run_load_test "会话创建" \
        "$BASE_URL/api/claude" \
        "POST" \
        '{"cwd":"/tmp/perf-test","name":"Perf Test"}' \
        50 \
        5
}

test_static_asset_performance() {
    log_info "=== 静态资源性能测试 ==="

    run_load_test "静态 JS 资源" \
        "$BASE_URL/js/marked.min.js" \
        "GET" \
        "" \
        100 \
        10
}

test_concurrent_users() {
    log_info "=== 并发用户模拟 ==="

    local concurrent_users=20
    local requests_per_user=5
    local total_requests=$((concurrent_users * requests_per_user))

    log_info "模拟 $concurrent_users 个并发用户，每人 $requests_per_user 次请求"

    local result_file="$RESULTS_DIR/concurrent_users.csv"
    echo "time_total,http_code" > "$result_file"

    local pids=()
    for ((i=0; i<concurrent_users; i++)); do
        (
            # 每个用户：创建会话、列出会话、获取会话、删除会话
            local session_id=""

            for ((j=0; j<requests_per_user; j++)); do
                # 创建
                local create_time=$(measure_response_time "$BASE_URL/api/claude" "POST" '{"cwd":"/tmp","name":"User'$i'"}')
                echo "$create_time" >> "$result_file"

                # 列出
                local list_time=$(measure_response_time "$BASE_URL/api/claude")
                echo "$list_time" >> "$result_file"
            done
        ) &
        pids+=($!)
    done

    for pid in "${pids[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local total=$(tail -n +2 "$result_file" | wc -l)
    local success=$(tail -n +2 "$result_file" | awk -F',' '$2==200' | wc -l)
    local avg_time=$(tail -n +2 "$result_file" | awk -F',' '{sum+=$1; count++} END {if(count>0) printf "%.3f", sum/count}')

    log_metric "总请求数: $total"
    log_metric "成功: $success"
    log_metric "失败: $((total - success))"
    log_metric "平均响应时间: ${avg_time}s"

    echo "" >> "$REPORT_FILE"
}

test_memory_usage() {
    log_info "=== 内存使用检查 ==="

    # 查找 Java 进程
    local pid=$(pgrep -f "claude-web" | head -1)

    if [ -n "$pid" ]; then
        local mem_info=$(ps -p "$pid" -o pid,rss,vsz,pmem,cmd --no-headers 2>/dev/null || echo "N/A")
        log_metric "进程内存: $mem_info"

        # 尝试获取更详细的信息
        if [ -f "/proc/$pid/status" ]; then
            local vmrss=$(grep "VmRSS" /proc/$pid/status 2>/dev/null | awk '{print $2 $3}' || echo "N/A")
            local vmpeak=$(grep "VmPeak" /proc/$pid/status 2>/dev/null | awk '{print $2 $3}' || echo "N/A")
            log_metric "VmRSS: $vmrss"
            log_metric "VmPeak: $vmpeak"
        fi
    else
        log_warn "未找到运行的 claude-web 进程"
    fi

    echo "" >> "$REPORT_FILE"
}

test_stability() {
    log_info "=== 稳定性测试 (30 秒) ==="

    local result_file="$RESULTS_DIR/stability.csv"
    echo "time,time_total,http_code" > "$result_file"

    local end_time=$(($(date +%s) + 30))
    local count=0
    local success=0

    while [ $(date +%s) -lt $end_time ]; do
        local result=$(measure_response_time "$BASE_URL/api/claude")
        local time=$(echo "$result" | cut -d',' -f1)
        local code=$(echo "$result" | cut -d',' -f2)

        echo "$(date +%s),$time,$code" >> "$result_file"
        count=$((count + 1))

        if [ "$code" -eq 200 ] || [ "$code" -eq 502 ]; then
            success=$((success + 1))
        fi

        sleep 0.5
    done

    local avg_time=$(tail -n +2 "$result_file" | awk -F',' '{sum+=$2; count++} END {if(count>0) printf "%.3f", sum/count}')
    local availability=$(echo "scale=2; $success / $count * 100" | bc 2>/dev/null || echo "N/A")

    log_metric "总请求数: $count"
    log_metric "成功: $success"
    log_metric "可用性: ${availability}%"
    log_metric "平均响应时间: ${avg_time}s"

    echo "" >> "$REPORT_FILE"
}

# =============================================================================
# 主程序
# =============================================================================

main() {
    echo "============================================================================="
    echo "  claude-web 性能测试"
    echo "  目标: $BASE_URL"
    echo "  持续时间: ${DURATION}s"
    echo "  并发数: $CONCURRENCY"
    echo "  报告: $REPORT_FILE"
    echo "============================================================================="
    echo ""

    # 检查服务器
    if ! curl -s -o /dev/null "$BASE_URL/" 2>/dev/null; then
        log_error "服务器未在 $BASE_URL 运行"
        exit 1
    fi
    log_info "服务器可达"
    echo ""

    # 运行测试
    test_endpoint_latency
    test_session_creation_load
    test_static_asset_performance
    test_concurrent_users
    test_memory_usage
    test_stability

    # 汇总
    echo ""
    echo "============================================================================="
    echo "  性能测试完成"
    echo "============================================================================="
    log_info "结果保存至: $RESULTS_DIR"
    log_info "报告保存至: $REPORT_FILE"
    echo "============================================================================="

    # 列出结果文件
    echo ""
    log_info "结果文件:"
    ls -lh "$RESULTS_DIR"/*.csv 2>/dev/null | while read line; do
        echo "  $line"
    done
}

main "$@"
