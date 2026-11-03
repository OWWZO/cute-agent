package org.wwz.ai.domain.agent.browser.model;

import java.util.Map;

/**
 * Typed result for a browser command.
 */
public record BrowserCommandResult(String rpcId,
                                   boolean ok,
                                   String error,
                                   String errorCode,
                                   String page,
                                   Object data) {

    public boolean isOk() {
        return ok;
    }

    public String getRpcId() {
        return rpcId;
    }

    public String getError() {
        return error;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getPage() {
        return page;
    }

    public Object getData() {
        return data;
    }

    public static BrowserCommandResult success(String rpcId, Object data) {
        return new BrowserCommandResult(rpcId, true, null, null, null, data);
    }

    public static BrowserCommandResult failure(String rpcId,
                                               String error,
                                               String errorCode) {
        return new BrowserCommandResult(rpcId, false, error, errorCode, null, null);
    }

    public static BrowserCommandResult leaseReleased(String rpcId) {
        return success(rpcId, Map.of());
    }

    public static BrowserCommandResult offline(String rpcId) {
        return failure(rpcId, "浏览器未连接", "browser_offline");
    }

    public static BrowserCommandResult unsupported(String rpcId) {
        return failure(rpcId, "不允许的浏览器动作", "unsupported_action");
    }

    public static BrowserCommandResult invalid(String rpcId, String error) {
        return failure(rpcId, error, "invalid_body");
    }

    public static BrowserCommandResult duplicate(String rpcId) {
        return failure(rpcId, "RPC ID 已存在", "duplicate_rpc_id");
    }

    public static BrowserCommandResult unknown(String rpcId) {
        return failure(rpcId, "浏览器操作结果未知", "command_result_unknown");
    }

    public static BrowserCommandResult rpcFailed(String rpcId, String error) {
        return failure(rpcId, error, "rpc_failed");
    }
}
