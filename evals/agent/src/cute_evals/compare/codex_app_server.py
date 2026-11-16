from __future__ import annotations

import json
import math
import os
import queue
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass(slots=True)
class AppServerRun:
    status: str
    duration_ms: int
    final_text: str
    thread_id: str | None
    usage: dict[str, Any]
    cumulative_usage: dict[str, int]
    events: list[dict[str, Any]]
    stdout: str
    stderr: str
    return_code: int | None
    error_message: str | None
    soft_finalization: dict[str, Any] = field(default_factory=dict)


class _AppServerClient:
    def __init__(
        self,
        command: list[str],
        *,
        cwd: Path,
        timeout_seconds: int,
    ) -> None:
        self.command = command
        self.cwd = cwd
        self.timeout_seconds = timeout_seconds
        self.process: subprocess.Popen[str] | None = None
        self._messages: queue.Queue[tuple[str, str | None]] = queue.Queue()
        self._notifications: queue.Queue[dict[str, Any]] = queue.Queue()
        self._next_id = 1
        self.stderr_lines: list[str] = []
        self.stdout_lines: list[str] = []

    def start(self) -> None:
        self.process = subprocess.Popen(
            self.command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            cwd=self.cwd,
            env=os.environ.copy(),
        )
        threading.Thread(target=self._read_stream, args=("stdout",), daemon=True).start()
        threading.Thread(target=self._read_stream, args=("stderr",), daemon=True).start()

    def _read_stream(self, name: str) -> None:
        stream = self.process.stdout if name == "stdout" else self.process.stderr
        if stream is None:
            self._messages.put((name, None))
            return
        for line in stream:
            self._messages.put((name, line.rstrip("\n")))
        self._messages.put((name, None))

    def _send(self, payload: dict[str, Any]) -> None:
        if self.process is None or self.process.stdin is None:
            raise RuntimeError("Codex app-server is not running")
        self.process.stdin.write(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"
        )
        self.process.stdin.flush()

    def notify(self, method: str, params: dict[str, Any] | None = None) -> None:
        payload: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            payload["params"] = params
        self._send(payload)

    def request(
        self,
        method: str,
        params: dict[str, Any] | None = None,
        *,
        timeout: float | None = None,
    ) -> dict[str, Any]:
        request_id = self._next_id
        self._next_id += 1
        payload: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
        }
        if params is not None:
            payload["params"] = params
        self._send(payload)
        deadline = time.monotonic() + (timeout or self.timeout_seconds)
        while time.monotonic() < deadline:
            event = self._read_json(max(0.05, deadline - time.monotonic()))
            if event is None:
                continue
            if event.get("id") == request_id:
                error = event.get("error")
                if error:
                    raise RuntimeError(
                        f"{method} failed: {json.dumps(error, ensure_ascii=False)}"
                    )
                result = event.get("result")
                return result if isinstance(result, dict) else {}
            if event.get("method"):
                self._notifications.put(event)
        raise TimeoutError(f"Codex app-server request timed out: {method}")

    def event(self, timeout: float) -> dict[str, Any] | None:
        try:
            return self._notifications.get_nowait()
        except queue.Empty:
            pass
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            event = self._read_json(deadline - time.monotonic())
            if event is None:
                continue
            if event.get("method"):
                return event
        return None

    def _read_json(self, timeout: float) -> dict[str, Any] | None:
        try:
            name, line = self._messages.get(timeout=max(0.01, timeout))
        except queue.Empty:
            return None
        if line is None:
            return None
        if name == "stderr":
            self.stderr_lines.append(line)
            return None
        self.stdout_lines.append(line)
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            return None
        return event if isinstance(event, dict) else None

    def close(self) -> int | None:
        if self.process is None:
            return None
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        return self.process.returncode


def run_app_server_case(
    *,
    command: str,
    workspace: Path,
    prompt: str,
    finalization_prompt: str,
    timeout_seconds: int,
    sandbox: str,
    approval: str,
    model: str | None,
    config_overrides: tuple[str, ...],
    resume_session: str | None,
    ephemeral: bool,
    soft_budget_finalize: bool,
    token_usage_baseline: dict[str, int] | None,
    max_total_tokens: int | None,
    max_wall_ms: int | None,
    max_tool_calls: int | None,
    soft_limit_percent: float | None,
) -> AppServerRun:
    app_server_command = [command, "app-server", "--stdio"]
    for override in config_overrides:
        app_server_command.extend(["-c", override])

    client = _AppServerClient(
        app_server_command,
        cwd=workspace,
        timeout_seconds=timeout_seconds,
    )
    started_at = time.perf_counter()
    raw_events: list[dict[str, Any]] = []
    baseline = {
        "totalTokens": int((token_usage_baseline or {}).get("totalTokens") or 0),
        "inputTokens": int((token_usage_baseline or {}).get("inputTokens") or 0),
        "outputTokens": int((token_usage_baseline or {}).get("outputTokens") or 0),
        "cachedInputTokens": int(
            (token_usage_baseline or {}).get("cachedInputTokens") or 0
        ),
    }
    cumulative = dict(baseline)
    previous_turn_tokens = 0
    thread_id: str | None = None
    active_turn_id: str | None = None
    final_text = ""
    last_agent_text = ""
    tool_calls = 0
    token_usage_updates = 0
    token_usage_before_final = 0
    soft_reason: str | None = None
    finalization_started = False
    finalization_turn_id: str | None = None
    error_message: str | None = None
    status = "FAILED"
    soft_enabled = bool(soft_budget_finalize)
    soft_ratio = (
        soft_limit_percent
        if soft_limit_percent is not None and 0 < soft_limit_percent < 1
        else 0.8
    )
    soft = {
        "enabled": soft_enabled,
        "triggered": False,
        "reason": None,
        "interruptSent": False,
        "finalizationStarted": False,
        "initialTurnId": None,
        "finalizationTurnId": None,
        "tokensAtTrigger": None,
        "tokenUsageUpdates": 0,
        "tokenUsageBeforeFinal": 0,
    }

    def fail(message: str) -> None:
        nonlocal error_message
        error_message = message

    try:
        client.start()
        client.request(
            "initialize",
            {
                "clientInfo": {
                    "name": "cute-evals",
                    "version": "0.1.0",
                },
                "capabilities": {"experimentalApi": True},
            },
            timeout=min(30, timeout_seconds),
        )
        client.notify("initialized")
        thread_params: dict[str, Any] = {
            "cwd": str(workspace),
            "sandbox": sandbox,
            "approvalPolicy": approval,
        }
        if model:
            thread_params["model"] = model
        if resume_session:
            thread_params["threadId"] = resume_session
            thread_result = client.request(
                "thread/resume",
                thread_params,
                timeout=min(60, timeout_seconds),
            )
        else:
            thread_params["ephemeral"] = ephemeral
            thread_result = client.request(
                "thread/start",
                thread_params,
                timeout=min(60, timeout_seconds),
            )
        thread = thread_result.get("thread") or {}
        thread_id = str(thread.get("id") or resume_session or "") or None
        if thread_id is None:
            raise RuntimeError("Codex app-server returned no thread id")

        turn_result = client.request(
            "turn/start",
            {
                "threadId": thread_id,
                "input": [{"type": "text", "text": prompt}],
            },
        )
        active_turn_id = str((turn_result.get("turn") or {}).get("id") or "")
        soft["initialTurnId"] = active_turn_id or None

        soft_token_limit = _soft_limit(max_total_tokens, soft_ratio)
        soft_wall_ms = _soft_limit(max_wall_ms, soft_ratio)
        soft_tool_limit = _soft_limit(max_tool_calls, soft_ratio)
        hard_wall_ms = (
            max_wall_ms
            if max_wall_ms is not None
            else timeout_seconds * 1000
        )

        while True:
            elapsed_ms = int((time.perf_counter() - started_at) * 1000)
            if elapsed_ms > hard_wall_ms:
                status = "TIMEOUT"
                fail(
                    f"evaluation budget exceeded: wall_time ({hard_wall_ms}ms)"
                    if max_wall_ms is not None
                    else f"Codex timed out after {hard_wall_ms}ms"
                )
                _interrupt(client, thread_id, active_turn_id, soft)
                break
            event = client.event(0.5)
            if event is None:
                if (
                    soft_enabled
                    and soft_reason is None
                    and not soft["triggered"]
                    and soft_wall_ms is not None
                    and elapsed_ms >= soft_wall_ms
                ):
                    soft_reason = "wall_time_reserve"
                    _trigger_soft_finalization(
                        client,
                        thread_id,
                        active_turn_id,
                        soft,
                        soft_reason,
                        final_text,
                        finalization_started,
                    )
                continue
            raw_events.append(event)
            method = str(event.get("method") or "")
            params = event.get("params") or {}
            if method == "thread/tokenUsage/updated":
                token_usage_updates += 1
                if not final_text.strip():
                    token_usage_before_final += 1
                cumulative = _usage_payload(params.get("tokenUsage") or {})
                delta = _usage_delta(cumulative, baseline)
                increment = max(0, delta["totalTokens"] - previous_turn_tokens)
                previous_turn_tokens = delta["totalTokens"]
                soft["tokensAtTrigger"] = (
                    delta["totalTokens"] if soft["triggered"] else None
                )
                if (
                    max_total_tokens is not None
                    and delta["totalTokens"] > max_total_tokens
                ):
                    fail("evaluation budget exceeded: total_tokens")
                    if (
                        final_text.strip()
                        or finalization_started
                    ):
                        continue
                    if soft_enabled:
                        soft_reason = "hard_token_overshoot"
                        soft["tokensAtTrigger"] = delta["totalTokens"]
                    else:
                        status = "FAILED"
                        _interrupt(
                            client,
                            thread_id,
                            active_turn_id,
                            soft,
                        )
                        break
                if (
                    soft_enabled
                    and not soft["triggered"]
                    and (
                        (
                            soft_token_limit is not None
                            and delta["totalTokens"] >= soft_token_limit
                        )
                        or (
                            max_total_tokens is not None
                            and delta["totalTokens"] + increment
                            > max_total_tokens
                        )
                    )
                ):
                    soft_reason = (
                        "token_reserve"
                        if soft_token_limit is not None
                        and delta["totalTokens"] >= soft_token_limit
                        else "token_projection_reserve"
                    )
                    soft["tokensAtTrigger"] = delta["totalTokens"]
            elif method == "item/completed":
                item = params.get("item") or {}
                if _is_tool_item(item):
                    tool_calls += 1
                    if (
                        max_tool_calls is not None
                        and tool_calls > max_tool_calls
                    ):
                        fail("evaluation budget exceeded: tool_calls")
                        if final_text.strip() or finalization_started:
                            continue
                        if soft_enabled:
                            soft_reason = "hard_tool_overshoot"
                            soft["tokensAtTrigger"] = previous_turn_tokens
                        else:
                            status = "FAILED"
                            _interrupt(
                                client,
                                thread_id,
                                active_turn_id,
                                soft,
                            )
                            break
                if item.get("type") == "agentMessage":
                    text = str(item.get("text") or "")
                    if text:
                        last_agent_text = text
                        if item.get("phase") in {None, "final_answer"}:
                            final_text = text
                if (
                    soft_enabled
                    and not soft["triggered"]
                    and soft_tool_limit is not None
                    and tool_calls >= soft_tool_limit
                ):
                    soft_reason = "tool_reserve"
            elif method == "turn/completed":
                turn = params.get("turn") or {}
                final_turn_status = str(turn.get("status") or "")
                turn_text = _turn_final_text(turn)
                if turn_text:
                    final_text = turn_text
                if active_turn_id is None:
                    active_turn_id = str(turn.get("id") or "") or None
                if (
                    final_turn_status == "interrupted"
                    and soft["triggered"]
                    and not finalization_started
                ):
                    final_turn_status = None
                    finalization_started = True
                    soft["finalizationStarted"] = True
                    turn_result = client.request(
                        "turn/start",
                        {
                            "threadId": thread_id,
                            "input": [
                                {"type": "text", "text": finalization_prompt}
                            ],
                        },
                    )
                    active_turn_id = str(
                        (turn_result.get("turn") or {}).get("id") or ""
                    )
                    finalization_turn_id = active_turn_id or None
                    soft["finalizationTurnId"] = finalization_turn_id
                    continue
                if final_turn_status == "completed":
                    status = "SUCCESS" if final_text.strip() else "FAILED"
                    if status == "FAILED":
                        fail("Codex completed without a final answer")
                elif final_turn_status == "interrupted":
                    status = "FAILED"
                    fail("Codex turn was interrupted before finalization")
                else:
                    status = "FAILED"
                    error = turn.get("error") or {}
                    fail(str(error.get("message") or "Codex turn failed"))
                break
            elif method == "error":
                status = "FAILED"
                fail(
                    str(
                        params.get("message")
                        or params.get("error")
                        or "Codex app-server error"
                    )
                )
                break

            if soft_reason and not finalization_started:
                _trigger_soft_finalization(
                    client,
                    thread_id,
                    active_turn_id,
                    soft,
                    soft_reason,
                    final_text,
                    finalization_started,
                )
    except FileNotFoundError:
        status = "UNAVAILABLE"
        fail(f"Codex executable not found: {command}")
    except TimeoutError as exc:
        status = "TIMEOUT"
        fail(str(exc))
    except Exception as exc:  # noqa: BLE001 - adapter boundary
        text = str(exc)
        status = (
            "UNAVAILABLE"
            if any(
                marker in text.lower()
                for marker in ("authentication", "not logged in", "api key")
            )
            else "FAILED"
        )
        fail(text)
    finally:
        duration_ms = int((time.perf_counter() - started_at) * 1000)
        return_code = client.close()

    usage = _usage_delta(cumulative, baseline)
    soft["tokenUsageUpdates"] = token_usage_updates
    soft["tokenUsageBeforeFinal"] = token_usage_before_final
    return AppServerRun(
        status=status,
        duration_ms=duration_ms,
        final_text=final_text or last_agent_text,
        thread_id=thread_id,
        usage=usage,
        cumulative_usage=cumulative,
        events=raw_events,
        stdout="\n".join(client.stdout_lines),
        stderr="\n".join(client.stderr_lines),
        return_code=return_code,
        error_message=error_message,
        soft_finalization=soft,
    )


def normalized_events(run: AppServerRun) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    if run.thread_id:
        events.append(
            {
                "type": "thread.started",
                "thread_id": run.thread_id,
            }
        )
    for event in run.events:
        if str(event.get("method") or "") in {
            "item/started",
            "item/completed",
        }:
            events.append(event)
    events.append(
        {
            "type": "turn.completed",
            "usage": {
                "input_tokens": run.usage.get("inputTokens"),
                "output_tokens": run.usage.get("outputTokens"),
                "cached_input_tokens": run.usage.get("cachedInputTokens"),
            },
        }
    )
    return events


def _interrupt(
    client: _AppServerClient,
    thread_id: str,
    turn_id: str | None,
    soft: dict[str, Any],
) -> None:
    if not turn_id or soft.get("interruptSent"):
        return
    try:
        client.request(
            "turn/interrupt",
            {"threadId": thread_id, "turnId": turn_id},
            timeout=15,
        )
        soft["interruptSent"] = True
    except Exception:  # noqa: BLE001 - the turn may have completed first
        return


def _trigger_soft_finalization(
    client: _AppServerClient,
    thread_id: str,
    turn_id: str | None,
    soft: dict[str, Any],
    reason: str,
    final_text: str,
    finalization_started: bool,
) -> None:
    if soft.get("triggered") or finalization_started or final_text.strip():
        return
    soft["triggered"] = True
    soft["reason"] = reason
    _interrupt(client, thread_id, turn_id, soft)


def _usage_payload(value: dict[str, Any]) -> dict[str, int]:
    total = value.get("total") or {}
    return {
        "totalTokens": int(total.get("totalTokens") or 0),
        "inputTokens": int(total.get("inputTokens") or 0),
        "outputTokens": int(total.get("outputTokens") or 0),
        "cachedInputTokens": int(total.get("cachedInputTokens") or 0),
    }


def _usage_delta(
    cumulative: dict[str, int],
    baseline: dict[str, int],
) -> dict[str, int]:
    return {
        key: max(0, int(cumulative.get(key) or 0) - int(baseline.get(key) or 0))
        for key in (
            "totalTokens",
            "inputTokens",
            "outputTokens",
            "cachedInputTokens",
        )
    }


def _turn_final_text(turn: dict[str, Any]) -> str:
    texts = [
        str(item.get("text") or "")
        for item in turn.get("items") or []
        if isinstance(item, dict)
        and item.get("type") == "agentMessage"
        and (
            item.get("phase") == "final_answer"
            or item.get("phase") is None
        )
    ]
    return texts[-1] if texts else ""


def _is_tool_item(item: dict[str, Any]) -> bool:
    return str(item.get("type") or "") in {
        "commandExecution",
        "fileChange",
        "mcpToolCall",
        "dynamicToolCall",
        "webSearch",
        "imageGeneration",
    }


def _soft_limit(value: int | None, ratio: float) -> int | None:
    if value is None:
        return None
    return int(math.ceil(value * ratio))
