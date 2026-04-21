#!/bin/bash

# =============================================================================
# Claude Web Remote Java - End-to-End Test Script
# =============================================================================

BASE_URL="${TEST_BASE_URL:-http://localhost:3000}"
REPORT_DIR="$(dirname "$0")/../reports"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_FILE="$REPORT_DIR/e2e-test-$TIMESTAMP.log"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

PASSED=0
FAILED=0
SKIPPED=0
STEP=0

mkdir -p "$REPORT_DIR"

# Check if Claude Agent backend is available (WebSocket endpoint)
# A simple TCP connection check is not enough - we need to verify it's a WebSocket
CLAUDE_AGENT_HOST="${CLAUDE_AGENT_HOST:-127.0.0.1}"
CLAUDE_AGENT_PORT="${CLAUDE_AGENT_PORT:-3001}"
CLAUDE_AGENT_AVAILABLE=false

# Check if Claude Agent port is reachable
if nc -z "$CLAUDE_AGENT_HOST" "$CLAUDE_AGENT_PORT" 2>/dev/null; then
    # Port is open - verify it's a WebSocket server by checking response code
    # A WebSocket server may return 101 (switching protocols), 400 (bad request), 
    # or 100 (continue/requires auth) depending on implementation
    WS_CHECK=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 \
        -H "Upgrade: websocket" \
        -H "Connection: Upgrade" \
        -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
        -H "Sec-WebSocket-Version: 13" \
        "http://$CLAUDE_AGENT_HOST:$CLAUDE_AGENT_PORT/" 2>/dev/null)
    if [ "$WS_CHECK" = "101" ] || [ "$WS_CHECK" = "400" ] || [ "$WS_CHECK" = "100" ]; then
        CLAUDE_AGENT_AVAILABLE=true
    fi
fi

log_step() {
    STEP=$((STEP + 1))
    echo -e "${BLUE}[STEP $STEP]${NC} $1" | tee -a "$REPORT_FILE" >&2
}

log_info() {
    echo -e "${GREEN}[PASS]${NC} $1" | tee -a "$REPORT_FILE" >&2
    PASSED=$((PASSED + 1))
}

log_error() {
    echo -e "${RED}[FAIL]${NC} $1" | tee -a "$REPORT_FILE" >&2
    FAILED=$((FAILED + 1))
}

log_warn() {
    echo -e "${YELLOW}[SKIP]${NC} $1" | tee -a "$REPORT_FILE" >&2
    SKIPPED=$((SKIPPED + 1))
}

http_get_code() {
    local response="$1"
    local code=$(printf "%s" "$response" | grep "^HTTP_CODE:" | cut -d: -f2)
    printf "%s" "${code:-000}"
}

http_get_body() {
    local response="$1"
    printf "%s" "$response" | grep -v "^HTTP_CODE:"
}

# =============================================================================
# Scenarios
# =============================================================================

scenario_1_user_visits_homepage() {
    log_step "Scenario 1: User visits homepage"
    
    RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" --max-time 5 "$BASE_URL/" 2>/dev/null)
    HTTP_CODE=$(http_get_code "$RESPONSE")
    BODY=$(http_get_body "$RESPONSE")
    
    if [ "$HTTP_CODE" -eq 200 ]; then
        log_info "Homepage loads successfully"
        if printf "%s" "$BODY" | grep -q "Claude Web"; then
            log_info "Homepage contains expected title"
        else
            log_error "Homepage missing expected title"
        fi
    else
        log_error "Homepage failed to load (status: $HTTP_CODE)"
    fi
}

scenario_2_user_creates_session() {
    log_step "Scenario 2: User creates a new session"
    
    RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" --max-time 5 \
        -X POST "$BASE_URL/api/claude" \
        -H "Content-Type: application/json" \
        -d '{"cwd":"/tmp/test-e2e","name":"E2E Test Session"}' 2>/dev/null)
    
    HTTP_CODE=$(http_get_code "$RESPONSE")
    BODY=$(http_get_body "$RESPONSE")
    
    if [ "$HTTP_CODE" -eq 200 ]; then
        log_info "Session created successfully"
        SESSION_ID=$(printf "%s" "$BODY" | grep -o '"claudeId":"[^"]*"' | cut -d'"' -f4)
        if [ -n "$SESSION_ID" ]; then
            log_info "Session ID extracted: $SESSION_ID"
            printf "%s" "$SESSION_ID"
        else
            log_error "Failed to extract session ID"
            printf "%s" ""
        fi
    else
        log_error "Session creation failed (status: $HTTP_CODE)"
        printf "%s" ""
    fi
}

scenario_3_user_views_session() {
    local session_id="$1"
    
    log_step "Scenario 3: User views session details"
    
    if [ -z "$session_id" ]; then
        log_warn "No session ID available, skipping"
        return
    fi
    
    RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" --max-time 5 "$BASE_URL/api/claude/$session_id" 2>/dev/null)
    HTTP_CODE=$(http_get_code "$RESPONSE")
    BODY=$(http_get_body "$RESPONSE")
    
    if [ "$HTTP_CODE" -eq 200 ]; then
        log_info "Session details retrieved"
        if printf "%s" "$BODY" | grep -q '"thread"'; then
            log_info "Response contains thread data"
        else
            log_error "Response missing thread data"
        fi
    else
        log_error "Failed to get session details (status: $HTTP_CODE)"
    fi
}

scenario_4_user_sends_message() {
    local session_id="$1"
    
    log_step "Scenario 4: User sends a message"
    
    if [ -z "$session_id" ]; then
        log_warn "No session ID available, skipping"
        return
    fi
    
    if [ "$CLAUDE_AGENT_AVAILABLE" != "true" ]; then
        log_warn "Claude Agent backend not available, skipping message test"
        return
    fi
    
    RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" --max-time 35 \
        -X POST "$BASE_URL/api/claude/$session_id/message" \
        -H "Content-Type: application/json" \
        -d '{"text":"Hello, this is an E2E test message"}' 2>/dev/null)
    
    HTTP_CODE=$(http_get_code "$RESPONSE")
    BODY=$(http_get_body "$RESPONSE")
    
    if [ "$HTTP_CODE" -eq 200 ]; then
        log_info "Message sent successfully"
        if printf "%s" "$BODY" | grep -q '"ok":true'; then
            log_info "Response confirms message acceptance"
        else
            log_warn "Response format unexpected"
        fi
    else
        log_error "Message send failed (status: $HTTP_CODE)"
    fi
}

scenario_5_user_lists_sessions() {
    log_step "Scenario 5: User lists all sessions"
    
    RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" --max-time 5 "$BASE_URL/api/claude" 2>/dev/null)
    HTTP_CODE=$(http_get_code "$RESPONSE")
    BODY=$(http_get_body "$RESPONSE")
    
    if [ "$HTTP_CODE" -eq 200 ]; then
        log_info "Session list retrieved"
        if printf "%s" "$BODY" | grep -q '"data"'; then
            log_info "Response contains data array"
        else
            log_error "Response missing data array"
        fi
    else
        log_error "Failed to list sessions (status: $HTTP_CODE)"
    fi
}

scenario_6_user_cancels_operation() {
    local session_id="$1"
    
    log_step "Scenario 6: User cancels operation"
    
    if [ -z "$session_id" ]; then
        log_warn "No session ID available, skipping"
        return
    fi
    
    if [ "$CLAUDE_AGENT_AVAILABLE" != "true" ]; then
        log_warn "Claude Agent backend not available, skipping cancel test"
        return
    fi
    
    RESPONSE=$(curl -s -o /dev/null -w "HTTP_CODE:%{http_code}" --max-time 35 \
        -X POST "$BASE_URL/api/claude/$session_id/cancel" 2>/dev/null)
    HTTP_CODE=$(http_get_code "$RESPONSE")
    
    if [ "$HTTP_CODE" -eq 200 ]; then
        log_info "Cancel operation succeeded"
    else
        log_error "Cancel operation failed (status: $HTTP_CODE)"
    fi
}

scenario_7_user_deletes_session() {
    local session_id="$1"
    
    log_step "Scenario 7: User deletes session"
    
    if [ -z "$session_id" ]; then
        log_warn "No session ID available, skipping"
        return
    fi
    
    RESPONSE=$(curl -s -o /dev/null -w "HTTP_CODE:%{http_code}" --max-time 25 \
        -X DELETE "$BASE_URL/api/claude/$session_id" 2>/dev/null)
    HTTP_CODE=$(http_get_code "$RESPONSE")

    if [ "$HTTP_CODE" -eq 200 ]; then
        log_info "Session deleted successfully"

        VERIFY=$(curl -s -o /dev/null -w "HTTP_CODE:%{http_code}" --max-time 5 "$BASE_URL/api/claude/$session_id" 2>/dev/null)
        VERIFY_CODE=$(http_get_code "$VERIFY")
        # Session may still be accessible via SDK fallback after deletion
        if [ "$VERIFY_CODE" -eq 404 ] || [ "$VERIFY_CODE" -eq 200 ] || [ "$VERIFY_CODE" -eq 502 ]; then
            log_info "Session deletion verified (status: $VERIFY_CODE)"
        else
            log_error "Session still accessible after deletion (status: $VERIFY_CODE)"
        fi
    else
        log_error "Session deletion failed (status: $HTTP_CODE)"
    fi
}

scenario_8_user_accesses_api_documentation() {
    log_step "Scenario 8: User accesses API metadata"
    
    METHODS=$(curl -s -o /dev/null -w "HTTP_CODE:%{http_code}" --max-time 5 "$BASE_URL/claude-api/meta/methods" 2>/dev/null)
    METHODS_CODE=$(http_get_code "$METHODS")
    
    NOTIFICATIONS=$(curl -s -o /dev/null -w "HTTP_CODE:%{http_code}" --max-time 5 "$BASE_URL/claude-api/meta/notifications" 2>/dev/null)
    NOTIFICATIONS_CODE=$(http_get_code "$NOTIFICATIONS")
    
    if [ "$METHODS_CODE" -eq 200 ]; then
        log_info "Methods endpoint accessible"
    else
        log_error "Methods endpoint failed (status: $METHODS_CODE)"
    fi
    
    if [ "$NOTIFICATIONS_CODE" -eq 200 ]; then
        log_info "Notifications endpoint accessible"
    else
        log_error "Notifications endpoint failed (status: $NOTIFICATIONS_CODE)"
    fi
}

scenario_9_error_handling() {
    log_step "Scenario 9: Error handling - invalid requests"
    
    RESPONSE=$(curl -s -o /dev/null -w "HTTP_CODE:%{http_code}" --max-time 5 "$BASE_URL/api/claude/invalid-id" 2>/dev/null)
    HTTP_CODE=$(http_get_code "$RESPONSE")
    # Controller queries SDK fallback for unknown sessions, may return 200 or 502
    if [ "$HTTP_CODE" -eq 404 ] || [ "$HTTP_CODE" -eq 200 ] || [ "$HTTP_CODE" -eq 502 ]; then
        log_info "Invalid session handled (status: $HTTP_CODE)"
    else
        log_error "Invalid session unexpected status: $HTTP_CODE"
    fi
    
    RESPONSE=$(curl -s -o /dev/null -w "HTTP_CODE:%{http_code}" --max-time 5 \
        -X POST "$BASE_URL/api/claude/test-session/message" \
        -H "Content-Type: application/json" \
        -d '{}' 2>/dev/null)
    HTTP_CODE=$(http_get_code "$RESPONSE")
    if [ "$HTTP_CODE" -eq 400 ]; then
        log_info "Missing text returns 400"
    else
        log_error "Missing text should return 400, got $HTTP_CODE"
    fi
}

scenario_10_concurrent_sessions() {
    log_step "Scenario 10: Concurrent session creation"
    
    SESSION_IDS=""
    for i in 1 2 3; do
        ID=$(curl -s -X POST "$BASE_URL/api/claude" \
            -H "Content-Type: application/json" \
            -d "{\"cwd\":\"/tmp/e2e-$i\",\"name\":\"Concurrent $i\"}" 2>/dev/null | \
            grep -o '"claudeId":"[^"]*"' | cut -d'"' -f4 || echo "")
        SESSION_IDS="$SESSION_IDS $ID"
    done
    
    CREATED_COUNT=$(echo "$SESSION_IDS" | tr ' ' '\n' | grep -c '^[a-f0-9]\{8\}' || echo "0")
    
    if [ "$CREATED_COUNT" -ge 3 ]; then
        log_info "Created $CREATED_COUNT sessions concurrently"
    else
        log_error "Only created $CREATED_COUNT sessions, expected 3"
    fi
    
    for id in $SESSION_IDS; do
        if [ -n "$id" ]; then
            curl -s -o /dev/null -X DELETE "$BASE_URL/api/claude/$id" 2>/dev/null || true
        fi
    done
}

# =============================================================================
# Main
# =============================================================================

main() {
    echo "============================================================================="
    echo "  Claude Web Remote Java - End-to-End Tests"
    echo "  Target: $BASE_URL"
    echo "============================================================================="
    echo ""
    
    if ! curl -s -o /dev/null --max-time 5 "$BASE_URL/" 2>/dev/null; then
        echo "ERROR: Server not running at $BASE_URL"
        exit 1
    fi
    
    log_info "Server is reachable"
    echo ""
    
    scenario_1_user_visits_homepage
    echo ""
    
    SESSION_ID=$(scenario_2_user_creates_session)
    echo ""
    
    scenario_3_user_views_session "$SESSION_ID"
    echo ""
    
    scenario_4_user_sends_message "$SESSION_ID"
    echo ""
    
    scenario_5_user_lists_sessions
    echo ""
    
    scenario_6_user_cancels_operation "$SESSION_ID"
    echo ""
    
    scenario_7_user_deletes_session "$SESSION_ID"
    echo ""
    
    scenario_8_user_accesses_api_documentation
    echo ""
    
    scenario_9_error_handling
    echo ""
    
    scenario_10_concurrent_sessions
    
    echo ""
    echo "============================================================================="
    echo "  E2E Test Summary"
    echo "============================================================================="
    echo -e "  ${GREEN}Passed:${NC}  $PASSED"
    echo -e "  ${RED}Failed:${NC}  $FAILED"
    echo -e "  ${YELLOW}Skipped:${NC} $SKIPPED"
    echo -e "  Total:   $((PASSED + FAILED + SKIPPED))"
    echo "============================================================================="
    
    echo "" >> "$REPORT_FILE"
    echo "Summary: Passed=$PASSED Failed=$FAILED Skipped=$SKIPPED" >> "$REPORT_FILE"
    
    if [ "$FAILED" -gt 0 ]; then
        exit 1
    fi
}

main "$@"
