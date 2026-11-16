from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import time
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from ..models import EvalCase, RunTrace
from .codex_app_server import normalized_events, run_app_server_case


@dataclass(slots=True)
class CodexOptions:
    command: str = "codex"
    model: str | None = None
    sandbox: str = "workspace-write"
    approval: str = "never"


def _now() -> str:
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def _resolved_command(command: str) -> str:
    resolved = shutil.which(command) or command
    path = Path(resolved)
    if path.suffix.lower() in {".cmd", ".bat"}:
        package_root = path.parent / "node_modules" / "@openai" / "codex"
        candidates = sorted(
            package_root.glob(
                "node_modules/@openai/codex-win32-*/vendor/*/bin/codex.exe"
            )
        )
        if candidates:
            return str(candidates[0])
    return resolved


def _event_type(event: dict[str, Any]) -> str:
    raw = event.get("type") or event.get("method") or ""
    aliases = {
        "thread/started": "thread.started",
        "turn/started": "turn.started",
        "turn/completed": "turn.completed",
        "turn/failed": "turn.failed",
        "item/started": "item.started",
        "item/completed": "item.completed",
    }
    return aliases.get(str(raw), str(raw).replace("/", "."))


def _event_payload(event: dict[str, Any]) -> dict[str, Any]:
    payload = event.get("params")
    if isinstance(payload, dict):
        return payload
    return event


def _item_type(event: dict[str, Any]) -> str:
    payload = _event_payload(event)
    item = payload.get("item")
    if isinstance(item, dict):
        return str(item.get("type") or item.get("itemType") or "")
    return str(payload.get("itemType") or payload.get("item_type") or "")


def _mcp_tool_name(item: dict[str, Any]) -> str | None:
    for key in ("tool", "toolName", "tool_name", "name"):
        value = item.get(key)
        if value:
            return str(value)
    return None


def _mcp_tool_output(value: Any) -> Any:
    if not isinstance(value, dict):
        return value
    content = value.get("content")
    if not isinstance(content, list):
        return value
    texts = [
        str(item.get("text"))
        for item in content
        if isinstance(item, dict)
        and item.get("type") == "text"
        and item.get("text") is not None
    ]
    return "\n".join(texts) if texts else value


def _item(event: dict[str, Any]) -> dict[str, Any]:
    payload = _event_payload(event)
    item = payload.get("item")
    return item if isinstance(item, dict) else payload


def parse_codex_events(raw_lines: list[str]) -> dict[str, Any]:
    events: list[dict[str, Any]] = []
    parse_errors: list[str] = []
    thread_id: str | None = None
    final_text = ""
    usage: dict[str, Any] = {}
    tools: list[dict[str, Any]] = []
    artifacts: list[dict[str, Any]] = []
    conversation: list[dict[str, Any]] = []
    failed_message: str | None = None
    turn_completed = False

    for line_number, line in enumerate(raw_lines, start=1):
        if not line.strip():
            continue
        try:
            event = json.loads(line)
        except json.JSONDecodeError as exc:
            parse_errors.append(f"line {line_number}: {exc}")
            continue
        if not isinstance(event, dict):
            parse_errors.append(f"line {line_number}: event is not an object")
            continue
        events.append(event)
        event_type = _event_type(event)
        payload = _event_payload(event)
        if event_type == "thread.started":
            thread_id = str(payload.get("thread_id") or payload.get("threadId") or "")
        elif event_type in {"turn.completed", "turn.failed"}:
            if event_type == "turn.completed":
                turn_completed = True
                usage = dict(payload.get("usage") or {})
            else:
                error = payload.get("error") or payload.get("message")
                failed_message = str(error or "Codex turn failed")
        elif event_type == "error":
            failed_message = str(payload.get("message") or payload.get("error") or "Codex error")
        elif event_type in {"item.started", "item.completed"}:
            item = _item(event)
            item_type = _item_type(event)
            if item_type in {"agent_message", "agentMessage"}:
                text = str(item.get("text") or item.get("message") or "")
                if text:
                    final_text = text
                    conversation.append(
                        {
                            "messageId": str(item.get("id") or f"codex-{len(conversation)}"),
                            "role": "assistant",
                            "content": text,
                        }
                    )
                continue
            if item_type in {"userMessage", "user_message"}:
                content = item.get("content") or []
                text = "\n".join(
                    str(part.get("text") or "")
                    for part in content
                    if isinstance(part, dict) and part.get("text")
                )
                conversation.append(
                    {
                        "messageId": str(
                            item.get("id") or f"codex-user-{len(conversation)}"
                        ),
                        "role": "user",
                        "content": text,
                    }
                )
                continue
            if item_type in {
                "reasoning",
                "todo_list",
                "todoList",
                "hookPrompt",
                "plan",
                "contextCompaction",
            }:
                continue
            if event_type != "item.completed":
                continue
            status_raw = str(item.get("status") or "completed").lower()
            status = (
                "SUCCESS"
                if status_raw in {"completed", "success", "succeeded"}
                else "FAILED"
            )
            input_value: Any = (
                item.get("command")
                or item.get("arguments")
                or item.get("changes")
                or item.get("query")
                or item.get("server")
            )
            result_value: Any = (
                item.get("aggregated_output")
                or item.get("aggregatedOutput")
                or item.get("output")
                or item.get("result")
                or item.get("summary")
            )
            if item_type in {"mcp_tool_call", "mcpToolCall"}:
                result_value = _mcp_tool_output(result_value)
            canonical_name = {
                "command_execution": "shell",
                "commandExecution": "shell",
                "file_change": "file_edit",
                "fileChange": "file_edit",
                "mcp_tool_call": "mcp_tool",
                "mcpToolCall": "mcp_tool",
                "web_search": "web_search",
                "webSearch": "web_search",
            }.get(item_type, item_type or "unknown")
            if item_type in {"mcp_tool_call", "mcpToolCall"}:
                canonical_name = _mcp_tool_name(item) or "mcp_tool"
            exit_code = item.get("exit_code", item.get("exitCode"))
            if exit_code not in (None, 0):
                status = "FAILED"
            if (
                item_type in {"mcp_tool_call", "mcpToolCall"}
                and isinstance(item.get("result"), dict)
                and item["result"].get("isError")
            ):
                status = "FAILED"
            error_message = item.get("error") or item.get("error_message")
            tools.append(
                {
                    "toolCallId": item.get("id"),
                    "toolName": canonical_name,
                    "inputJson": json.dumps(input_value, ensure_ascii=False)
                    if input_value is not None
                    else None,
                    "toolResult": str(result_value)[:12000]
                    if result_value is not None
                    else None,
                    "status": status,
                    "errorMsg": str(error_message) if error_message else None,
                    "durationMs": item.get("duration_ms", item.get("durationMs")),
                    "artifactCount": len(item.get("changes") or [])
                    if isinstance(item.get("changes"), list)
                    else None,
                }
            )
            changes = item.get("changes") or item.get("files") or []
            if isinstance(changes, list):
                for change in changes:
                    if isinstance(change, str):
                        path = change
                    elif isinstance(change, dict):
                        path = change.get("path") or change.get("file")
                    else:
                        path = None
                    if path:
                        artifacts.append(
                            {
                                "kind": "workspace-file",
                                "path": str(path),
                                "source": "codex.file_change",
                            }
                        )

    return {
        "events": events,
        "parseErrors": parse_errors,
        "threadId": thread_id or None,
        "finalText": final_text,
        "usage": usage,
        "tools": tools,
        "artifacts": artifacts,
        "conversation": conversation,
        "failedMessage": failed_message,
        "turnCompleted": turn_completed,
    }


def _auth_unavailable(stderr: str, stdout: str) -> bool:
    text = f"{stderr}\n{stdout}".lower()
    return any(
        marker in text
        for marker in (
            "not logged in",
            "authentication",
            "unauthorized",
            "api key",
            "login",
        )
    )


def _trace_payload(
    case: EvalCase,
    parsed: dict[str, Any],
    *,
    status: str,
    duration_ms: int,
    final_path_text: str,
    command: list[str],
    return_code: int | None,
    error_message: str | None,
    environment: dict[str, Any],
) -> dict[str, Any]:
    usage = parsed["usage"]
    input_tokens = usage.get("input_tokens", usage.get("inputTokens"))
    output_tokens = usage.get("output_tokens", usage.get("outputTokens"))
    total_tokens = (
        int(input_tokens) + int(output_tokens)
        if isinstance(input_tokens, (int, float))
        and isinstance(output_tokens, (int, float))
        else None
    )
    final_text = final_path_text or str(parsed["finalText"] or "")
    started = _now()
    llm_invocations = []
    if usage:
        llm_invocations.append(
            {
                "id": 1,
                "status": "SUCCESS" if status == "SUCCESS" else "FAILED",
                "startedAt": started,
                "firstTokenAt": None,
                "finishedAt": _now(),
                "totalTokens": total_tokens,
                "cachedPromptTokens": usage.get(
                    "cached_input_tokens",
                    usage.get("cachedInputTokens"),
                ),
                "promptTextTokens": input_tokens,
                "completionTextTokens": output_tokens,
            }
        )
    return {
        "schemaVersion": "1.0",
        "producer": {
            "agent": "codex-cli",
            "runtimeVersion": environment.get("agentVersion"),
            "exportedAt": _now(),
        },
        "run": {
            "caseId": case.id,
            "requestId": str(parsed.get("threadId") or f"codex-{case.id}"),
            "sessionId": str(parsed.get("threadId") or ""),
            "status": status,
            "finalSummaryText": final_text,
            "durationMs": duration_ms,
            "promptTokens": input_tokens,
            "completionTokens": output_tokens,
            "totalTokens": total_tokens,
            "llmCallCount": len(llm_invocations),
            "toolCallCount": len(parsed["tools"]),
            "artifactCount": len(parsed["artifacts"]),
            "errorCode": (
                (
                    "CODEX_AUTH"
                    if "login" in str(error_message).lower()
                    or "auth" in str(error_message).lower()
                    else "CODEX_UNAVAILABLE"
                )
                if status == "UNAVAILABLE"
                else "CODEX_TIMEOUT"
                if status == "TIMEOUT"
                else "CODEX_FAILED"
                if status != "SUCCESS"
                else None
            ),
            "errorMessage": error_message,
        },
        "llmInvocations": llm_invocations,
        "toolInvocations": parsed["tools"],
        "artifacts": parsed["artifacts"],
        "conversation": parsed["conversation"],
        "environment": environment,
        "raw": {
            "command": command,
            "returnCode": return_code,
            "events": parsed["events"],
            "parseErrors": parsed["parseErrors"],
        },
    }


def _apply_budget(trace: RunTrace, case: EvalCase) -> RunTrace:
    run = trace.raw.setdefault("run", {})
    existing_error = str(run.get("errorMsg") or "")
    if existing_error.startswith("evaluation budget exceeded: "):
        run["errorCode"] = "EVAL_BUDGET_EXCEEDED"
        trace.status = "FAILED"
        return trace
    if trace.status != "SUCCESS":
        return trace
    limits = case.budgets
    exceeded = None
    if (
        limits.max_wall_ms is not None
        and trace.duration_ms is not None
        and trace.duration_ms > limits.max_wall_ms
    ):
        exceeded = "wall_time"
    elif (
        limits.max_total_tokens is not None
        and trace.total_tokens is not None
        and trace.total_tokens > limits.max_total_tokens
    ):
        exceeded = "total_tokens"
    elif (
        limits.max_tool_calls is not None
        and len(trace.tool_invocations) > limits.max_tool_calls
    ):
        exceeded = "tool_calls"
    if exceeded is None:
        return trace
    run["status"] = "FAILED"
    run["errorCode"] = "EVAL_BUDGET_EXCEEDED"
    run["errorMsg"] = f"evaluation budget exceeded: {exceeded}"
    trace.status = "FAILED"
    return trace


def codex_version(command: str) -> str | None:
    command = _resolved_command(command)
    try:
        completed = subprocess.run(
            [command, "--version"],
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    text = (completed.stdout or completed.stderr).strip()
    match = re.search(r"\b\d+\.\d+\.\d+\b", text)
    return match.group(0) if match else (text or None)


def _finalization_prompt(case: EvalCase) -> str:
    instruction = (case.answer_contract or {}).get("instruction")
    contract = f"\n\n{instruction}" if instruction else ""
    return (
        "Evaluation soft budget reached. Stop all research and tool use now. "
        "Use only the evidence already present in this conversation and produce "
        "the final answer immediately. Do not call any tools and do not keep "
        f"verifying.{contract}"
    )


def _app_server_command(
    options: CodexOptions,
    config_overrides: tuple[str, ...],
) -> list[str]:
    command = [_resolved_command(options.command), "app-server", "--stdio"]
    for override in config_overrides:
        command.extend(["-c", override])
    return command


def run_codex_case(
    case: EvalCase,
    workspace: Path,
    case_root: Path,
    *,
    options: CodexOptions,
    timeout_seconds: int,
    environment: dict[str, Any],
    config_overrides: tuple[str, ...] = (),
    resume_session: str | None = None,
    ephemeral: bool = True,
    app_server: bool = False,
    soft_budget_finalize: bool = False,
    token_usage_baseline: dict[str, int] | None = None,
) -> RunTrace:
    case_root.mkdir(parents=True, exist_ok=True)
    events_path = case_root / "codex-events.jsonl"
    stderr_path = case_root / "codex-stderr.log"
    stdout_path = case_root / "codex-stdout.log"
    last_message_path = case_root / "codex-last-message.txt"
    if last_message_path.exists():
        last_message_path.unlink()

    if app_server:
        app_run = run_app_server_case(
            command=_resolved_command(options.command),
            workspace=workspace,
            prompt=case.query,
            finalization_prompt=_finalization_prompt(case),
            timeout_seconds=timeout_seconds,
            sandbox=options.sandbox,
            approval=options.approval,
            model=options.model,
            config_overrides=config_overrides,
            resume_session=resume_session,
            ephemeral=ephemeral,
            soft_budget_finalize=soft_budget_finalize,
            token_usage_baseline=token_usage_baseline,
            max_total_tokens=case.budgets.max_total_tokens,
            max_wall_ms=case.budgets.max_wall_ms,
            max_tool_calls=case.budgets.max_tool_calls,
            soft_limit_percent=case.budgets.soft_limit_percent,
        )
        stdout_path.write_text(app_run.stdout, encoding="utf-8")
        stderr_path.write_text(app_run.stderr, encoding="utf-8")
        events = normalized_events(app_run)
        events_path.write_text(
            "\n".join(json.dumps(event, ensure_ascii=False) for event in events),
            encoding="utf-8",
        )
        if app_run.final_text:
            last_message_path.write_text(app_run.final_text, encoding="utf-8")
        parsed = parse_codex_events(
            [json.dumps(event, ensure_ascii=False) for event in events]
        )
        parsed["finalText"] = app_run.final_text
        trace_environment = {
            **environment,
            "agent": "codex-cli",
            "agentVersion": environment.get("agentVersion"),
            "workspace": str(workspace),
            "budgets": asdict(case.budgets),
            "budgetPolicy": case.metadata.get("budgetPolicy"),
            "adapter": {
                "kind": "codex-app-server",
                "command": options.command,
                "sandbox": options.sandbox,
                "approval": options.approval,
                "model": options.model,
                "softBudgetFinalize": soft_budget_finalize,
                "softBudgetFinalization": app_run.soft_finalization,
                "cumulativeUsage": app_run.cumulative_usage,
                "stepCountVerified": False,
            },
        }
        payload = _trace_payload(
            case,
            parsed,
            status=app_run.status,
            duration_ms=app_run.duration_ms,
            final_path_text=app_run.final_text,
            command=_app_server_command(options, config_overrides),
            return_code=app_run.return_code,
            error_message=app_run.error_message,
            environment=trace_environment,
        )
        payload["raw"]["appServerEvents"] = app_run.events
        trace = RunTrace.from_dict(payload)
        return _apply_budget(trace, case)

    command = [_resolved_command(options.command), "-a", options.approval]
    if resume_session:
        command.extend(["exec", "resume", resume_session])
    else:
        command.extend(
            [
                "exec",
                "-C",
                str(workspace),
                "-s",
                options.sandbox,
            ]
        )
    command.extend(
        [
            "--json",
            "--skip-git-repo-check",
            "-o",
            str(last_message_path),
        ]
    )
    if not resume_session:
        command.extend(["--color", "never"])
    if ephemeral:
        command.append("--ephemeral")
    for override in config_overrides:
        command.extend(["-c", override])
    if options.model:
        command.extend(["-m", options.model])
    command.append("-")

    started_at = time.perf_counter()
    timed_out = False
    command_missing = False
    completed: subprocess.CompletedProcess[str] | None = None
    error_message: str | None = None
    try:
        completed = subprocess.run(
            command,
            input=case.query,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            timeout=timeout_seconds,
            cwd=workspace,
            env=os.environ.copy(),
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        timed_out = True
        stdout = exc.stdout if isinstance(exc.stdout, str) else ""
        stderr = exc.stderr if isinstance(exc.stderr, str) else ""
        completed = subprocess.CompletedProcess(
            command,
            returncode=None,
            stdout=stdout,
            stderr=stderr,
        )
        error_message = f"Codex timed out after {timeout_seconds}s"
    except FileNotFoundError:
        command_missing = True
        error_message = f"Codex executable not found: {options.command}"
    duration_ms = int((time.perf_counter() - started_at) * 1000)

    stdout = completed.stdout if completed else ""
    stderr = completed.stderr if completed else ""
    stdout_path.write_text(stdout, encoding="utf-8")
    stderr_path.write_text(stderr, encoding="utf-8")
    events_path.write_text(
        "\n".join(
            line
            for line in stdout.splitlines()
            if line.lstrip().startswith("{")
        ),
        encoding="utf-8",
    )
    parsed = parse_codex_events(stdout.splitlines())
    final_text = (
        last_message_path.read_text(encoding="utf-8").strip()
        if last_message_path.exists()
        else ""
    )
    return_code = completed.returncode if completed else None
    if timed_out and not parsed["turnCompleted"]:
        status = "TIMEOUT"
    elif command_missing:
        status = "UNAVAILABLE"
    elif parsed["turnCompleted"] and not parsed["failedMessage"]:
        status = "SUCCESS"
    elif return_code == 0 and not parsed["failedMessage"]:
        status = "SUCCESS"
    elif _auth_unavailable(stderr, stdout):
        status = "UNAVAILABLE"
    else:
        status = "FAILED"
    if status == "SUCCESS" and timed_out and parsed["turnCompleted"]:
        error_message = None
    if error_message is None:
        error_message = parsed["failedMessage"]
    if error_message is None and return_code not in (None, 0):
        error_message = stderr[-4000:] or f"Codex exited with {return_code}"

    trace_environment = {
        **environment,
        "agent": "codex-cli",
        "agentVersion": environment.get("agentVersion"),
        "workspace": str(workspace),
        "budgets": asdict(case.budgets),
        "budgetPolicy": case.metadata.get("budgetPolicy"),
        "adapter": {
            "kind": "codex-exec-json",
            "command": options.command,
            "sandbox": options.sandbox,
            "approval": options.approval,
            "model": options.model,
            "timedOut": timed_out,
            "completedTurnBeforeTimeout": timed_out and parsed["turnCompleted"],
            "stepCountVerified": False,
        },
    }
    payload = _trace_payload(
        case,
        parsed,
        status=status,
        duration_ms=duration_ms,
        final_path_text=final_text,
        command=command,
        return_code=return_code,
        error_message=error_message,
        environment=trace_environment,
    )
    trace = RunTrace.from_dict(payload)
    return _apply_budget(trace, case)
