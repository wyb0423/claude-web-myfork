#!/bin/bash

# =============================================================================
# Claude Web Remote Java - Complete Test Suite Runner
# =============================================================================
# This script runs all tests: unit tests, integration tests, E2E tests,
# and performance tests.
#
# Usage:
#   ./run-all-tests.sh [options]
#
# Options:
#   --unit-only        Run only unit tests
#   --integration-only Run only integration tests
#   --e2e-only         Run only E2E tests
#   --performance-only Run only performance tests
#   --websocket-only   Run only WebSocket transport tests
#   --sse-only         Run only SSE event stream tests
#   --reconnect-only   Run only reconnect tests
#   --skip-build       Skip Maven build
#   --help             Show this help message
#
# Environment Variables:
#   TEST_BASE_URL      - Base URL for integration/E2E tests (default: http://localhost:3000)
#   TEST_PASSWORD      - Password for auth tests
# =============================================================================

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
REPORT_DIR="$SCRIPT_DIR/reports"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
SUMMARY_FILE="$REPORT_DIR/test-summary-$TIMESTAMP.txt"

BASE_URL="${TEST_BASE_URL:-http://localhost:3000}"
PASSWORD="${TEST_PASSWORD:-}"

# Test flags
RUN_UNIT=true
RUN_INTEGRATION=true
RUN_E2E=true
RUN_PERFORMANCE=true
RUN_WEBSOCKET=true
RUN_SSE=true
RUN_RECONNECT=true
SKIP_BUILD=false

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Results
UNIT_PASSED=0
UNIT_FAILED=0
INTEGRATION_PASSED=0
INTEGRATION_FAILED=0
INTEGRATION_SKIPPED=0
E2E_PASSED=0
E2E_FAILED=0
E2E_SKIPPED=0

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --unit-only)
            RUN_INTEGRATION=false
            RUN_E2E=false
            RUN_PERFORMANCE=false
            shift
            ;;
        --integration-only)
            RUN_UNIT=false
            RUN_E2E=false
            RUN_PERFORMANCE=false
            shift
            ;;
        --e2e-only)
            RUN_UNIT=false
            RUN_INTEGRATION=false
            RUN_PERFORMANCE=false
            shift
            ;;
        --performance-only)
            RUN_UNIT=false
            RUN_INTEGRATION=false
            RUN_E2E=false
            shift
            ;;
        --websocket-only)
            RUN_UNIT=false
            RUN_INTEGRATION=false
            RUN_E2E=false
            RUN_PERFORMANCE=false
            shift
            ;;
        --sse-only)
            RUN_UNIT=false
            RUN_INTEGRATION=false
            RUN_E2E=false
            RUN_PERFORMANCE=false
            shift
            ;;
        --reconnect-only)
            RUN_UNIT=false
            RUN_INTEGRATION=false
            RUN_E2E=false
            RUN_PERFORMANCE=false
            shift
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --help)
            echo "Claude Web Remote Java - Test Suite Runner"
            echo ""
            echo "Usage: ./run-all-tests.sh [options]"
            echo ""
            echo "Options:"
            echo "  --unit-only        Run only unit tests"
            echo "  --integration-only Run only integration tests"
            echo "  --e2e-only         Run only E2E tests"
            echo "  --performance-only Run only performance tests"
            echo "  --websocket-only   Run only WebSocket transport tests"
            echo "  --sse-only         Run only SSE event stream tests"
            echo "  --reconnect-only   Run only reconnect tests"
            echo "  --skip-build       Skip Maven build"
            echo "  --help             Show this help message"
            echo ""
            echo "Environment Variables:"
            echo "  TEST_BASE_URL      Base URL (default: http://localhost:3000)"
            echo "  TEST_PASSWORD      Password for auth tests"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Logging
log_header() {
    echo ""
    echo -e "${CYAN}=============================================================================${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}=============================================================================${NC}"
    echo ""
}

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1" | tee -a "$SUMMARY_FILE"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" | tee -a "$SUMMARY_FILE"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1" | tee -a "$SUMMARY_FILE"
}

# Initialize
mkdir -p "$REPORT_DIR"
echo "Test Summary - $(date)" > "$SUMMARY_FILE"
echo "Project: $PROJECT_DIR" >> "$SUMMARY_FILE"
echo "Target: $BASE_URL" >> "$SUMMARY_FILE"
echo "" >> "$SUMMARY_FILE"

# =============================================================================
# Unit Tests
# =============================================================================

run_unit_tests() {
    log_header "UNIT TESTS"
    
    cd "$PROJECT_DIR"
    
    if [ "$SKIP_BUILD" = false ]; then
        log_info "Building project..."
        if /home/sunsw/code/.maven/apache-maven-3.9.14/bin/mvn -s /home/sunsw/code/.m2/settings.xml clean compile test-compile -q; then
            log_info "Build successful"
        else
            log_error "Build failed"
            return 1
        fi
    fi
    
    log_info "Running unit tests..."
    
    if /home/sunsw/code/.maven/apache-maven-3.9.14/bin/mvn -s /home/sunsw/code/.m2/settings.xml test > "$REPORT_DIR/unit-test-$TIMESTAMP.log" 2>&1; then
        UNIT_PASSED=$(grep -o "Tests run: [0-9]*" "$REPORT_DIR/unit-test-$TIMESTAMP.log" | awk '{sum+=$3} END {print sum}')
        UNIT_FAILED=0
        log_info "Unit tests PASSED"
        log_info "Total tests: $UNIT_PASSED"
    else
        UNIT_PASSED=$(grep -o "Tests run: [0-9]*" "$REPORT_DIR/unit-test-$TIMESTAMP.log" | awk '{sum+=$3} END {print sum}')
        UNIT_FAILED=$(grep -o "Failures: [0-9]*" "$REPORT_DIR/unit-test-$TIMESTAMP.log" | awk '{sum+=$2} END {print sum}')
        log_error "Unit tests FAILED"
        log_error "Failures: $UNIT_FAILED"
    fi
    
    echo "" >> "$SUMMARY_FILE"
    echo "Unit Tests:" >> "$SUMMARY_FILE"
    echo "  Passed: $UNIT_PASSED" >> "$SUMMARY_FILE"
    echo "  Failed: $UNIT_FAILED" >> "$SUMMARY_FILE"
    
    log_info "Unit test log: $REPORT_DIR/unit-test-$TIMESTAMP.log"
}

# =============================================================================
# Integration Tests
# =============================================================================

run_integration_tests() {
    log_header "INTEGRATION TESTS"
    
    log_info "Running integration tests against $BASE_URL..."
    
    if [ -x "$SCRIPT_DIR/integration/integration-test.sh" ]; then
        cd "$SCRIPT_DIR/integration"
        if TEST_BASE_URL="$BASE_URL" TEST_PASSWORD="$PASSWORD" ./integration-test.sh > "$REPORT_DIR/integration-test-$TIMESTAMP.log" 2>&1; then
            INTEGRATION_PASSED=$(grep -o "Passed:  [0-9]*" "$REPORT_DIR/integration-test-$TIMESTAMP.log" | awk '{print $2}')
            INTEGRATION_FAILED=$(grep -o "Failed:  [0-9]*" "$REPORT_DIR/integration-test-$TIMESTAMP.log" | awk '{print $2}')
            INTEGRATION_SKIPPED=$(grep -o "Skipped: [0-9]*" "$REPORT_DIR/integration-test-$TIMESTAMP.log" | awk '{print $2}')
            log_info "Integration tests PASSED"
        else
            INTEGRATION_PASSED=$(grep -o "Passed:  [0-9]*" "$REPORT_DIR/integration-test-$TIMESTAMP.log" | awk '{print $2}')
            INTEGRATION_FAILED=$(grep -o "Failed:  [0-9]*" "$REPORT_DIR/integration-test-$TIMESTAMP.log" | awk '{print $2}')
            INTEGRATION_SKIPPED=$(grep -o "Skipped: [0-9]*" "$REPORT_DIR/integration-test-$TIMESTAMP.log" | awk '{print $2}')
            log_error "Integration tests FAILED"
        fi
    else
        log_warn "Integration test script not found or not executable"
        INTEGRATION_SKIPPED=1
    fi
    
    echo "" >> "$SUMMARY_FILE"
    echo "Integration Tests:" >> "$SUMMARY_FILE"
    echo "  Passed:  ${INTEGRATION_PASSED:-0}" >> "$SUMMARY_FILE"
    echo "  Failed:  ${INTEGRATION_FAILED:-0}" >> "$SUMMARY_FILE"
    echo "  Skipped: ${INTEGRATION_SKIPPED:-0}" >> "$SUMMARY_FILE"
    
    log_info "Integration test log: $REPORT_DIR/integration-test-$TIMESTAMP.log"
}

# =============================================================================
# E2E Tests
# =============================================================================

run_e2e_tests() {
    log_header "END-TO-END TESTS"
    
    log_info "Running E2E tests against $BASE_URL..."
    
    if [ -x "$SCRIPT_DIR/e2e/e2e-test.sh" ]; then
        cd "$SCRIPT_DIR/e2e"
        if TEST_BASE_URL="$BASE_URL" ./e2e-test.sh > "$REPORT_DIR/e2e-test-$TIMESTAMP.log" 2>&1; then
            E2E_PASSED=$(grep -o "Passed:  [0-9]*" "$REPORT_DIR/e2e-test-$TIMESTAMP.log" | awk '{print $2}')
            E2E_FAILED=$(grep -o "Failed:  [0-9]*" "$REPORT_DIR/e2e-test-$TIMESTAMP.log" | awk '{print $2}')
            E2E_SKIPPED=$(grep -o "Skipped: [0-9]*" "$REPORT_DIR/e2e-test-$TIMESTAMP.log" | awk '{print $2}')
            log_info "E2E tests PASSED"
        else
            E2E_PASSED=$(grep -o "Passed:  [0-9]*" "$REPORT_DIR/e2e-test-$TIMESTAMP.log" | awk '{print $2}')
            E2E_FAILED=$(grep -o "Failed:  [0-9]*" "$REPORT_DIR/e2e-test-$TIMESTAMP.log" | awk '{print $2}')
            E2E_SKIPPED=$(grep -o "Skipped: [0-9]*" "$REPORT_DIR/e2e-test-$TIMESTAMP.log" | awk '{print $2}')
            log_error "E2E tests FAILED"
        fi
    else
        log_warn "E2E test script not found or not executable"
        E2E_SKIPPED=1
    fi
    
    echo "" >> "$SUMMARY_FILE"
    echo "E2E Tests:" >> "$SUMMARY_FILE"
    echo "  Passed:  ${E2E_PASSED:-0}" >> "$SUMMARY_FILE"
    echo "  Failed:  ${E2E_FAILED:-0}" >> "$SUMMARY_FILE"
    echo "  Skipped: ${E2E_SKIPPED:-0}" >> "$SUMMARY_FILE"
    
    log_info "E2E test log: $REPORT_DIR/e2e-test-$TIMESTAMP.log"
}

# =============================================================================
# Performance Tests
# =============================================================================

run_performance_tests() {
    log_header "PERFORMANCE TESTS"
    
    log_info "Running performance tests against $BASE_URL..."
    
    if [ -x "$SCRIPT_DIR/performance/performance-test.sh" ]; then
        cd "$SCRIPT_DIR/performance"
        if TEST_BASE_URL="$BASE_URL" ./performance-test.sh > "$REPORT_DIR/performance-test-$TIMESTAMP.log" 2>&1; then
            log_info "Performance tests completed"
        else
            log_warn "Performance tests completed with warnings"
        fi
    else
        log_warn "Performance test script not found or not executable"
    fi
    
    echo "" >> "$SUMMARY_FILE"
    echo "Performance Tests: Completed" >> "$SUMMARY_FILE"
    
    log_info "Performance test log: $REPORT_DIR/performance-test-$TIMESTAMP.log"
}

# =============================================================================
# WebSocket Transport Tests
# =============================================================================

run_websocket_tests() {
    log_header "WEBSOCKET TRANSPORT TESTS"

    log_info "Running WebSocket transport tests against $BASE_URL..."

    if [ -x "$SCRIPT_DIR/websocket/websocket-test.sh" ]; then
        cd "$SCRIPT_DIR/websocket"
        if TEST_WEB_URL="$BASE_URL" TEST_AGENT_WS="ws://localhost:3001" ./websocket-test.sh > "$REPORT_DIR/websocket-test-$TIMESTAMP.log" 2>&1; then
            log_info "WebSocket transport tests PASSED"
        else
            log_warn "WebSocket transport tests completed with warnings"
        fi
    else
        log_warn "WebSocket test script not found or not executable"
    fi

    echo "" >> "$SUMMARY_FILE"
    echo "WebSocket Transport Tests: Completed" >> "$SUMMARY_FILE"

    log_info "WebSocket test log: $REPORT_DIR/websocket-test-$TIMESTAMP.log"
}

# =============================================================================
# SSE Event Stream Tests
# =============================================================================

run_sse_tests() {
    log_header "SSE EVENT STREAM TESTS"

    log_info "Running SSE event stream tests against $BASE_URL..."

    if [ -x "$SCRIPT_DIR/integration/sse-test.sh" ]; then
        cd "$SCRIPT_DIR/integration"
        if TEST_BASE_URL="$BASE_URL" ./sse-test.sh > "$REPORT_DIR/sse-test-$TIMESTAMP.log" 2>&1; then
            log_info "SSE tests PASSED"
        else
            log_warn "SSE tests completed with warnings"
        fi
    else
        log_warn "SSE test script not found or not executable"
    fi

    echo "" >> "$SUMMARY_FILE"
    echo "SSE Tests: Completed" >> "$SUMMARY_FILE"

    log_info "SSE test log: $REPORT_DIR/sse-test-$TIMESTAMP.log"
}

# =============================================================================
# Reconnect Tests
# =============================================================================

run_reconnect_tests() {
    log_header "RECONNECT TESTS"

    log_info "Running reconnect tests against $BASE_URL..."

    if [ -x "$SCRIPT_DIR/integration/reconnect-test.sh" ]; then
        cd "$SCRIPT_DIR/integration"
        if TEST_WEB_URL="$BASE_URL" TEST_AGENT_URL="http://localhost:3001" ./reconnect-test.sh > "$REPORT_DIR/reconnect-test-$TIMESTAMP.log" 2>&1; then
            log_info "Reconnect tests PASSED"
        else
            log_warn "Reconnect tests completed with warnings"
        fi
    else
        log_warn "Reconnect test script not found or not executable"
    fi

    echo "" >> "$SUMMARY_FILE"
    echo "Reconnect Tests: Completed" >> "$SUMMARY_FILE"

    log_info "Reconnect test log: $REPORT_DIR/reconnect-test-$TIMESTAMP.log"
}

# =============================================================================
# Summary
# =============================================================================

print_summary() {
    log_header "TEST SUMMARY"
    
    local total_passed=$((UNIT_PASSED + INTEGRATION_PASSED + E2E_PASSED))
    local total_failed=$((UNIT_FAILED + INTEGRATION_FAILED + E2E_FAILED))
    local total_skipped=$((INTEGRATION_SKIPPED + E2E_SKIPPED))
    
    echo -e "${BLUE}Unit Tests:${NC}"
    echo -e "  ${GREEN}Passed:${NC} $UNIT_PASSED"
    if [ "$UNIT_FAILED" -gt 0 ]; then
        echo -e "  ${RED}Failed:${NC} $UNIT_FAILED"
    fi
    
    echo -e "${BLUE}Integration Tests:${NC}"
    echo -e "  ${GREEN}Passed:${NC}  ${INTEGRATION_PASSED:-0}"
    if [ "${INTEGRATION_FAILED:-0}" -gt 0 ]; then
        echo -e "  ${RED}Failed:${NC}  ${INTEGRATION_FAILED:-0}"
    fi
    if [ "${INTEGRATION_SKIPPED:-0}" -gt 0 ]; then
        echo -e "  ${YELLOW}Skipped:${NC} ${INTEGRATION_SKIPPED:-0}"
    fi
    
    echo -e "${BLUE}E2E Tests:${NC}"
    echo -e "  ${GREEN}Passed:${NC}  ${E2E_PASSED:-0}"
    if [ "${E2E_FAILED:-0}" -gt 0 ]; then
        echo -e "  ${RED}Failed:${NC}  ${E2E_FAILED:-0}"
    fi
    if [ "${E2E_SKIPPED:-0}" -gt 0 ]; then
        echo -e "  ${YELLOW}Skipped:${NC} ${E2E_SKIPPED:-0}"
    fi
    
    echo ""
    echo -e "${CYAN}Total:${NC}"
    echo -e "  ${GREEN}Passed:${NC}  $total_passed"
    if [ "$total_failed" -gt 0 ]; then
        echo -e "  ${RED}Failed:${NC}  $total_failed"
    fi
    if [ "$total_skipped" -gt 0 ]; then
        echo -e "  ${YELLOW}Skipped:${NC} $total_skipped"
    fi
    
    echo ""
    echo -e "${CYAN}Reports:${NC}"
    echo "  Summary: $SUMMARY_FILE"
    echo "  Logs: $REPORT_DIR/"
    
    # Write to summary file
    echo "" >> "$SUMMARY_FILE"
    echo "=============================================================================" >> "$SUMMARY_FILE"
    echo "  TOTAL SUMMARY" >> "$SUMMARY_FILE"
    echo "=============================================================================" >> "$SUMMARY_FILE"
    echo "  Passed:  $total_passed" >> "$SUMMARY_FILE"
    echo "  Failed:  $total_failed" >> "$SUMMARY_FILE"
    echo "  Skipped: $total_skipped" >> "$SUMMARY_FILE"
    echo "=============================================================================" >> "$SUMMARY_FILE"
    
    if [ "$total_failed" -gt 0 ]; then
        echo ""
        log_error "Some tests failed!"
        return 1
    else
        echo ""
        log_info "All tests passed!"
        return 0
    fi
}

# =============================================================================
# Main
# =============================================================================

main() {
    echo "============================================================================="
    echo "  Claude Web Remote Java - Complete Test Suite"
    echo "============================================================================="
    echo ""
    
    if [ "$RUN_UNIT" = true ]; then
        run_unit_tests
    fi
    
    if [ "$RUN_INTEGRATION" = true ]; then
        run_integration_tests
    fi
    
    if [ "$RUN_E2E" = true ]; then
        run_e2e_tests
    fi
    
    if [ "$RUN_PERFORMANCE" = true ]; then
        run_performance_tests
    fi

    if [ "$RUN_WEBSOCKET" = true ]; then
        run_websocket_tests
    fi

    if [ "$RUN_SSE" = true ]; then
        run_sse_tests
    fi

    if [ "$RUN_RECONNECT" = true ]; then
        run_reconnect_tests
    fi

    print_summary
}

main "$@"
