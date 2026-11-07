package org.wwz.ai.trigger.http.browser.bsk;

import com.alibaba.fastjson.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Retains a dispatched tool call while a native dialog is waiting for a decision.
 *
 * <p>This mirrors the Rust daemon's dialog operation registry: the original
 * action is never replayed. The operation keeps the original response future
 * alive and exposes it through {@code tool.operation_await}.</p>
 */
final class BskDialogOperations {

    static final int MAX_OPERATIONS = 64;
    static final long RESULT_TTL_MS = 300_000;

    private final Map<String, Operation> operations = new ConcurrentHashMap<>();

    Operation register(String sessionId, String cliConnectionId,
                       String extensionConnectionId, String cliRpcId, String method) {
        if (sessionId == null || sessionId.isBlank()
                || cliConnectionId == null || extensionConnectionId == null
                || cliRpcId == null || method == null) {
            return null;
        }
        pruneExpired();
        if (operations.size() >= MAX_OPERATIONS) {
            String oldestCompleted = operations.entrySet().stream()
                    .filter(entry -> entry.getValue().response.isDone())
                    .min((left, right) -> Long.compare(
                            left.getValue().completedAtMs, right.getValue().completedAtMs))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldestCompleted != null) {
                operations.remove(oldestCompleted);
            }
        }
        if (operations.size() >= MAX_OPERATIONS) {
            return null;
        }
        Operation operation = new Operation(
                "op-" + UUID.randomUUID(), sessionId, cliConnectionId,
                extensionConnectionId, cliRpcId, method);
        operations.put(operation.id, operation);
        return operation;
    }

    Operation get(String sessionId, String operationId) {
        Operation operation = operations.get(operationId);
        if (operation == null || !operation.sessionId.equals(sessionId)) {
            return null;
        }
        if (operation.response.isDone()
                && System.currentTimeMillis() - operation.completedAtMs >= RESULT_TTL_MS) {
            operations.remove(operationId, operation);
            return null;
        }
        return operation;
    }

    Operation get(String sessionId, String operationId, String cliConnectionId) {
        Operation operation = get(sessionId, operationId);
        if (operation == null || cliConnectionId == null
                || !cliConnectionId.equals(operation.cliConnectionId)) {
            return null;
        }
        return operation;
    }

    void remove(String operationId) {
        if (operationId != null) {
            operations.remove(operationId);
        }
    }

    void removeSession(String sessionId) {
        if (sessionId != null) {
            operations.entrySet().removeIf(entry -> sessionId.equals(entry.getValue().sessionId));
        }
    }

    void removeCliConnection(String connectionId) {
        if (connectionId != null) {
            operations.entrySet().removeIf(entry ->
                    connectionId.equals(entry.getValue().cliConnectionId));
        }
    }

    boolean dialogChanged(String extensionConnectionId, JSONObject payload) {
        if (payload == null) {
            return false;
        }
        String sessionId = payload.getString("session_id");
        String operationId = payload.getString("operation_id");
        Operation operation = get(sessionId, operationId);
        if (operation == null || extensionConnectionId == null
                || !extensionConnectionId.equals(operation.extensionConnectionId)) {
            return false;
        }
        JSONObject current = payload.get("dialog") instanceof JSONObject object ? object : null;
        operation.dialogChanged(current);
        return true;
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        operations.entrySet().removeIf(entry -> {
            Operation operation = entry.getValue();
            return operation.response.isDone()
                    && now - operation.completedAtMs >= RESULT_TTL_MS;
        });
    }

    /** One retained tool call. */
    static final class Operation {
        final String id;
        final String sessionId;
        final String cliConnectionId;
        final String extensionConnectionId;
        final String cliRpcId;
        final String method;
        final CompletableFuture<JSONObject> response = new CompletableFuture<>();
        final AtomicReference<JSONObject> dialog = new AtomicReference<>();
        final AtomicReference<CompletableFuture<JSONObject>> dialogSignal =
                new AtomicReference<>(new CompletableFuture<>());
        volatile long completedAtMs;

        Operation(String id, String sessionId, String cliConnectionId,
                  String extensionConnectionId, String cliRpcId, String method) {
            this.id = id;
            this.sessionId = sessionId;
            this.cliConnectionId = cliConnectionId;
            this.extensionConnectionId = extensionConnectionId;
            this.cliRpcId = cliRpcId;
            this.method = method;
        }

        void finish(JSONObject wireResponse) {
            completedAtMs = System.currentTimeMillis();
            response.complete(wireResponse);
        }

        void dialogChanged(JSONObject currentDialog) {
            if (response.isDone()) {
                return;
            }
            dialog.set(currentDialog);
            CompletableFuture<JSONObject> signal = dialogSignal.get();
            if (currentDialog != null) {
                signal.complete(currentDialog);
            } else {
                dialogSignal.compareAndSet(signal, new CompletableFuture<>());
            }
        }

        /**
         * Wait for the first meaningful state: final response or a dialog.
         *
         * @return true when the final response is ready; false when a dialog is pending
         */
        boolean awaitInitial(long timeoutSeconds) throws TimeoutException, InterruptedException {
            if (response.isDone()) {
                return true;
            }
            if (dialog.get() != null) {
                return false;
            }
            try {
                CompletableFuture<?> initial = CompletableFuture.anyOf(dialogSignal.get(), response);
                if (timeoutSeconds <= 0) {
                    initial.get();
                } else {
                    initial.get(timeoutSeconds, TimeUnit.SECONDS);
                }
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
            return response.isDone();
        }

        /**
         * Wait up to {@code waitMs} for completion. A pending dialog or still-running
         * operation is reported as a structured state, not as a gateway error.
         */
        JSONObject awaitResult(long waitMs) throws InterruptedException {
            if (!response.isDone() && dialog.get() == null && waitMs > 0) {
                try {
                    CompletableFuture.anyOf(dialogSignal.get(), response)
                            .get(waitMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Fall through to the running snapshot.
                } catch (ExecutionException e) {
                    JSONObject failed = new JSONObject();
                    failed.put("operation_id", id);
                    failed.put("method", method);
                    failed.put("state", "failed");
                    JSONObject error = new JSONObject();
                    error.put("code", "protocol_error");
                    error.put("message", String.valueOf(e.getCause()));
                    failed.put("error", error);
                    return failed;
                }
            }
            if (response.isDone()) {
                return completedResult();
            }
            JSONObject currentDialog = dialog.get();
            if (currentDialog != null) {
                return pendingData(currentDialog);
            }
            JSONObject running = new JSONObject();
            running.put("operation_id", id);
            running.put("method", method);
            running.put("state", "running");
            return running;
        }

        JSONObject pendingData(JSONObject currentDialog) {
            JSONObject data = new JSONObject();
            data.put("reason", "dialog_pending");
            data.put("state", "waiting_for_dialog");
            data.put("dispatched", true);
            data.put("session_id", sessionId);
            data.put("operation_id", id);
            data.put("method", method);
            if (currentDialog != null) {
                data.put("dialog", currentDialog);
            }
            return data;
        }

        private JSONObject completedResult() {
            JSONObject wire = response.join();
            JSONObject result = new JSONObject();
            result.put("operation_id", id);
            result.put("method", method);
            if (wire != null && wire.containsKey("error")) {
                result.put("state", "failed");
                result.put("error", wire.getJSONObject("error"));
            } else {
                result.put("state", "completed");
                result.put("result", wire == null ? null : wire.get("result"));
            }
            return result;
        }
    }
}
