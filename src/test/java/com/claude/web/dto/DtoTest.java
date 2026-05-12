package com.claude.web.dto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DtoTest {

    // ─── NotificationEvent ────────────────────────────────────────────────────

    @Test
    void notificationEvent_noArgsConstructor() {
        NotificationEvent e = new NotificationEvent();
        assertThat(e.getMethod()).isNull();
        assertThat(e.getParams()).isNull();
        assertThat(e.getAtIso()).isNull();
    }

    @Test
    void notificationEvent_constructor_setsFields() {
        Map<String, Object> params = Map.of("key", "value");
        NotificationEvent e = new NotificationEvent("test/method", params);

        assertThat(e.getMethod()).isEqualTo("test/method");
        assertThat(e.getParams()).isEqualTo(params);
        assertThat(e.getAtIso()).isNotNull();
    }

    @Test
    void notificationEvent_setters() {
        NotificationEvent e = new NotificationEvent();
        e.setMethod("my/method");
        e.setParams(Map.of("a", 1));
        e.setAtIso("2026-01-01T00:00:00Z");

        assertThat(e.getMethod()).isEqualTo("my/method");
        assertThat(e.getParams()).isEqualTo(Map.of("a", 1));
        assertThat(e.getAtIso()).isEqualTo("2026-01-01T00:00:00Z");
    }

    // ─── PendingServerRequest ─────────────────────────────────────────────────

    @Test
    void pendingServerRequest_noArgsConstructor() {
        PendingServerRequest r = new PendingServerRequest();
        assertThat(r.getId()).isEqualTo(0);
        assertThat(r.getMethod()).isNull();
        assertThat(r.getParams()).isNull();
        assertThat(r.getReceivedAtIso()).isNull();
    }

    @Test
    void pendingServerRequest_constructor_setsFields() {
        Map<String, Object> params = Map.of("threadId", "t-1");
        PendingServerRequest r = new PendingServerRequest(42, "item/tool/call", params, "2026-01-01T00:00:00Z");

        assertThat(r.getId()).isEqualTo(42);
        assertThat(r.getMethod()).isEqualTo("item/tool/call");
        assertThat(r.getParams()).isEqualTo(params);
        assertThat(r.getReceivedAtIso()).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test
    void pendingServerRequest_setters() {
        PendingServerRequest r = new PendingServerRequest();
        r.setId(99);
        r.setMethod("approval/request");
        r.setParams(Map.of("k", "v"));
        r.setReceivedAtIso("2026-05-12T10:00:00Z");

        assertThat(r.getId()).isEqualTo(99);
        assertThat(r.getMethod()).isEqualTo("approval/request");
        assertThat(r.getParams()).isEqualTo(Map.of("k", "v"));
        assertThat(r.getReceivedAtIso()).isEqualTo("2026-05-12T10:00:00Z");
    }

    // ─── JsonRpcRequest ───────────────────────────────────────────────────────

    @Test
    void jsonRpcRequest_noArgsConstructor() {
        JsonRpcRequest req = new JsonRpcRequest();
        assertThat(req.getJsonrpc()).isEqualTo("2.0");
        assertThat(req.getId()).isNull();
        assertThat(req.getMethod()).isNull();
        assertThat(req.getParams()).isNull();
    }

    @Test
    void jsonRpcRequest_allArgsConstructor() {
        JsonRpcRequest req = new JsonRpcRequest(1, "test/method", Map.of("a", "b"));

        assertThat(req.getJsonrpc()).isEqualTo("2.0");
        assertThat(req.getId()).isEqualTo(1);
        assertThat(req.getMethod()).isEqualTo("test/method");
        assertThat(req.getParams()).isEqualTo(Map.of("a", "b"));
    }

    @Test
    void jsonRpcRequest_setters() {
        JsonRpcRequest req = new JsonRpcRequest();
        req.setId(5);
        req.setMethod("chat");
        req.setParams(Map.of());
        req.setJsonrpc("2.0");

        assertThat(req.getId()).isEqualTo(5);
        assertThat(req.getMethod()).isEqualTo("chat");
    }

    // ─── JsonRpcResponse ──────────────────────────────────────────────────────

    @Test
    void jsonRpcResponse_defaultJsonrpc() {
        JsonRpcResponse resp = new JsonRpcResponse();
        assertThat(resp.getJsonrpc()).isEqualTo("2.0");
        assertThat(resp.getId()).isNull();
        assertThat(resp.getResult()).isNull();
        assertThat(resp.getError()).isNull();
    }

    @Test
    void jsonRpcResponse_setters() {
        JsonRpcResponse resp = new JsonRpcResponse();
        resp.setId(10);
        resp.setResult(Map.of("ok", true));
        resp.setMethod("notification");
        resp.setParams(Map.of("data", "value"));

        assertThat(resp.getId()).isEqualTo(10);
        assertThat(resp.getResult()).isEqualTo(Map.of("ok", true));
        assertThat(resp.getMethod()).isEqualTo("notification");
        assertThat(resp.getParams()).isEqualTo(Map.of("data", "value"));
    }

    @Test
    void jsonRpcResponse_error_setters() {
        JsonRpcResponse.JsonRpcError error = new JsonRpcResponse.JsonRpcError();
        error.setCode(-32000);
        error.setMessage("Something went wrong");

        assertThat(error.getCode()).isEqualTo(-32000);
        assertThat(error.getMessage()).isEqualTo("Something went wrong");

        JsonRpcResponse resp = new JsonRpcResponse();
        resp.setError(error);
        assertThat(resp.getError()).isSameAs(error);
    }

    @Test
    void jsonRpcError_allArgsConstructor() {
        JsonRpcResponse.JsonRpcError error = new JsonRpcResponse.JsonRpcError(-32001, "Parse error");
        assertThat(error.getCode()).isEqualTo(-32001);
        assertThat(error.getMessage()).isEqualTo("Parse error");
    }

    // ─── ServerRequestReply ───────────────────────────────────────────────────

    @Test
    void serverRequestReply_setters() {
        ServerRequestReply reply = new ServerRequestReply();
        reply.setResult(Map.of("approved", true));
        assertThat(reply.getResult()).isEqualTo(Map.of("approved", true));
        assertThat(reply.getError()).isNull();
    }

    @Test
    void serverRequestReply_jsonRpcError() {
        ServerRequestReply.JsonRpcError err = new ServerRequestReply.JsonRpcError(-32000, "Rejected");
        assertThat(err.getCode()).isEqualTo(-32000);
        assertThat(err.getMessage()).isEqualTo("Rejected");

        ServerRequestReply reply = new ServerRequestReply();
        reply.setError(err);
        assertThat(reply.getError()).isSameAs(err);
    }

    @Test
    void serverRequestReply_jsonRpcError_noArgsConstructor() {
        ServerRequestReply.JsonRpcError err = new ServerRequestReply.JsonRpcError();
        err.setCode(-32002);
        err.setMessage("Timeout");
        assertThat(err.getCode()).isEqualTo(-32002);
        assertThat(err.getMessage()).isEqualTo("Timeout");
    }
}
