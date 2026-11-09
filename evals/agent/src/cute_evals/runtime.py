from __future__ import annotations

import hashlib
import json
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
from typing import Any, Protocol

from .benchmarks.base import BenchmarkTask
from .models import RunTrace


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds")


def _stable_id(prefix: str, value: str) -> str:
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]
    return f"{prefix}-{digest}"


def _json_hash(payload: Any) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


@dataclass(slots=True)
class ConversationEntry:
    message_id: str
    conversation_id: str
    turn_id: str
    turn_index: int
    role: str
    actor: str
    content: str
    timestamp: str = field(default_factory=_now)
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "messageId": self.message_id,
            "conversationId": self.conversation_id,
            "turnId": self.turn_id,
            "turnIndex": self.turn_index,
            "role": self.role,
            "actor": self.actor,
            "content": self.content,
            "timestamp": self.timestamp,
            "metadata": self.metadata,
        }


@dataclass(slots=True)
class EnvironmentEvent:
    event_id: str
    conversation_id: str
    turn_id: str
    event_type: str
    payload: dict[str, Any] = field(default_factory=dict)
    timestamp: str = field(default_factory=_now)

    def to_dict(self) -> dict[str, Any]:
        return {
            "eventId": self.event_id,
            "conversationId": self.conversation_id,
            "turnId": self.turn_id,
            "type": self.event_type,
            "payload": self.payload,
            "timestamp": self.timestamp,
        }


@dataclass(slots=True)
class EnvironmentSnapshot:
    snapshot_id: str
    kind: str
    payload: Any
    sha256: str
    turn_id: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "snapshotId": self.snapshot_id,
            "kind": self.kind,
            "turnId": self.turn_id,
            "sha256": self.sha256,
            "payload": self.payload,
        }


class UserSimulator(Protocol):
    def start(
        self,
        task: BenchmarkTask,
        environment: TaskEnvironment | None = None,
    ) -> str | None: ...

    def respond(
        self,
        *,
        task: BenchmarkTask,
        history: list[dict[str, Any]],
        assistant_message: str,
        environment: TaskEnvironment | None = None,
    ) -> str | None: ...

    def error(self) -> str | None: ...

    def termination_reason(self) -> str: ...


class ConversationAgent(Protocol):
    def send(
        self,
        *,
        task: BenchmarkTask,
        session_id: str,
        turn_id: str,
        message: str,
        workspace: Path,
        environment: TaskEnvironment | None,
        timeout_seconds: int,
    ) -> RunTrace: ...


class TaskEnvironment(Protocol):
    def reset(self, task: BenchmarkTask) -> EnvironmentSnapshot | None: ...

    def snapshot(self, turn_id: str) -> EnvironmentSnapshot: ...

    def events(self, turn_id: str) -> list[EnvironmentEvent]: ...

    def close(self) -> None: ...


@dataclass(slots=True)
class ConversationRun:
    task: BenchmarkTask
    conversation_id: str
    trace: RunTrace
    turn_traces: list[RunTrace]
    conversation: list[dict[str, Any]]
    environment_events: list[dict[str, Any]]
    environment_snapshots: list[dict[str, Any]]
    termination_reason: str
    error: str | None = None


@dataclass(slots=True)
class ScriptedUserSimulator:
    messages: list[str]
    errors: list[str] = field(default_factory=list)
    _index: int = 0
    _termination_reason: str = "script_exhausted"

    def start(
        self,
        task: BenchmarkTask,
        environment: TaskEnvironment | None = None,
    ) -> str | None:
        self._index = 0
        return self._next()

    def respond(
        self,
        *,
        task: BenchmarkTask,
        history: list[dict[str, Any]],
        assistant_message: str,
        environment: TaskEnvironment | None = None,
    ) -> str | None:
        return self._next()

    def error(self) -> str | None:
        return self.errors[-1] if self.errors else None

    def termination_reason(self) -> str:
        return self._termination_reason

    def _next(self) -> str | None:
        if self._index >= len(self.messages):
            return None
        message = self.messages[self._index]
        self._index += 1
        return message


@dataclass(slots=True)
class OpenAICompatibleUserSimulator:
    model: str
    base_url: str
    api_key: str
    instructions: str
    temperature: float = 0.0
    timeout_seconds: int = 120
    errors: list[str] = field(default_factory=list)
    _termination_reason: str = "user_stop"
    _completed: bool = False

    def start(
        self,
        task: BenchmarkTask,
        environment: TaskEnvironment | None = None,
    ) -> str | None:
        self.errors.clear()
        self._completed = False
        self._termination_reason = "user_stop"
        return self._complete(
            [
                (
                    "user",
                    "Begin the conversation with the first message from the simulated user.",
                )
            ]
        )

    def respond(
        self,
        *,
        task: BenchmarkTask,
        history: list[dict[str, Any]],
        assistant_message: str,
        environment: TaskEnvironment | None = None,
    ) -> str | None:
        messages: list[tuple[str, str]] = []
        for item in history:
            role = "assistant" if item.get("role") == "assistant" else "user"
            content = str(item.get("content") or "")
            if content:
                messages.append((role, content))
        if not messages or messages[-1] != ("assistant", assistant_message):
            messages.append(("assistant", assistant_message))
        return self._complete(messages)

    def error(self) -> str | None:
        return self.errors[-1] if self.errors else None

    def termination_reason(self) -> str:
        return self._termination_reason

    def _complete(self, conversation: list[tuple[str, str]]) -> str | None:
        if self._completed:
            return None
        payload = {
            "model": self.model,
            "temperature": self.temperature,
            "messages": [
                {"role": "system", "content": self._system_prompt()},
                *[
                    {"role": role, "content": content}
                    for role, content in conversation
                ],
            ],
        }
        request = urllib.request.Request(
            self.base_url.rstrip("/") + "/chat/completions",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(
                request,
                timeout=self.timeout_seconds,
            ) as response:
                data = json.loads(response.read().decode("utf-8"))
            content = str(
                data["choices"][0]["message"].get("content") or ""
            ).strip()
        except (
            urllib.error.URLError,
            TimeoutError,
            KeyError,
            IndexError,
            json.JSONDecodeError,
        ) as exc:
            self.errors.append(f"user simulator request failed: {exc}")
            self._termination_reason = "user_simulator_error"
            return None
        if any(marker in content for marker in ("###STOP###", "###TRANSFER###", "###OUT-OF-SCOPE###")):
            self._completed = True
            self._termination_reason = "user_stop"
            return None
        if not content:
            self.errors.append("user simulator returned an empty response")
            self._termination_reason = "user_simulator_error"
            return None
        return content

    def _system_prompt(self) -> str:
        return (
            "You are a user simulator in a benchmark conversation. "
            "Stay in the user's role, reveal only information allowed by the "
            "scenario, and never write the assistant's answer for it. "
            "When the user's goal is complete or the user chooses to end, "
            "reply exactly ###STOP###.\n\n"
            "<scenario>\n"
            f"{self.instructions.strip()}\n"
            "</scenario>"
        )


class UserSimulatorServer:
    """Expose any UserSimulator over local HTTP for the Java conversation runner."""

    def __init__(
        self,
        simulator: UserSimulator,
        task: BenchmarkTask,
        environment: TaskEnvironment | None = None,
    ) -> None:
        self.simulator = simulator
        self.task = task
        self.environment = environment
        self.history: list[dict[str, Any]] = []
        self._initial: str | None = None
        self._server: ThreadingHTTPServer | None = None
        self._thread: Thread | None = None

    @property
    def endpoint(self) -> str:
        if self._server is None:
            raise RuntimeError("user simulator server is not started")
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    def start(self) -> str | None:
        self._initial = self.simulator.start(self.task, self.environment)
        if not self._initial:
            return None
        self.history.append(
            {
                "role": "user",
                "content": self._initial,
                "actor": "user-simulator",
            }
        )
        simulator = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                try:
                    length = int(self.headers.get("Content-Length") or 0)
                    body = json.loads(
                        self.rfile.read(length).decode("utf-8") or "{}"
                    )
                    if self.path != "/next":
                        self._send(404, {"ok": False, "error": "not found"})
                        return
                    assistant_message = str(body.get("assistantMessage") or "")
                    simulator.history.append(
                        {
                            "role": "assistant",
                            "content": assistant_message,
                            "actor": "cute-agent",
                        }
                    )
                    next_message = simulator.simulator.respond(
                        task=simulator.task,
                        history=list(simulator.history),
                        assistant_message=assistant_message,
                        environment=simulator.environment,
                    )
                    if next_message:
                        simulator.history.append(
                            {
                                "role": "user",
                                "content": next_message,
                                "actor": "user-simulator",
                            }
                        )
                    error = simulator.simulator.error()
                    self._send(
                        200,
                        {
                            "ok": error is None,
                            "message": next_message or "",
                            "done": next_message is None,
                            "reason": simulator.simulator.termination_reason(),
                            "error": error,
                        },
                    )
                except Exception as exc:  # noqa: BLE001 - HTTP boundary reports failures
                    self._send(500, {"ok": False, "error": str(exc)})

            def _send(self, status: int, payload: dict[str, Any]) -> None:
                data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, format: str, *args: Any) -> None:
                return

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._thread = Thread(
            target=self._server.serve_forever,
            name="cute-evals-user-simulator",
            daemon=True,
        )
        self._thread.start()
        return self._initial

    def close(self) -> None:
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=5)
        self._server = None
        self._thread = None


def _failed_trace(task: BenchmarkTask, conversation_id: str, error: str) -> RunTrace:
    payload = {
        "schemaVersion": "1.0",
        "producer": {"agent": "cute-agent", "exportedAt": _now()},
        "run": {
            "caseId": task.id,
            "requestId": conversation_id,
            "sessionId": conversation_id,
            "status": "FAILED",
            "finalSummaryText": error,
            "errorMsg": error,
        },
        "llmInvocations": [],
        "toolInvocations": [],
        "artifacts": [],
        "conversation": [],
        "conversationSource": "unavailable",
        "contextEvents": [],
        "memoryEvents": [],
        "environmentEvents": [],
        "environmentSource": "unavailable",
        "environmentSnapshots": [],
        "snapshotSource": "unavailable",
        "environment": {},
    }
    return RunTrace.from_dict(payload)


def _sum_optional(values: list[int | None]) -> int | None:
    if not values or any(value is None for value in values):
        return None
    return sum(int(value) for value in values if value is not None)


def merge_trace(
    *,
    task: BenchmarkTask,
    conversation_id: str,
    turn_traces: list[RunTrace],
    conversation: list[dict[str, Any]],
    environment_events: list[dict[str, Any]],
    environment_snapshots: list[dict[str, Any]],
    termination_reason: str,
    error: str | None = None,
) -> RunTrace:
    if not turn_traces:
        trace = _failed_trace(
            task,
            conversation_id,
            error or "conversation produced no agent turn",
        )
    else:
        llm_invocations: list[dict[str, Any]] = []
        tool_invocations: list[dict[str, Any]] = []
        context_events: list[dict[str, Any]] = []
        memory_events: list[dict[str, Any]] = []
        artifacts: list[dict[str, Any]] = []
        for turn_index, trace in enumerate(turn_traces, start=1):
            turn_id = f"turn-{turn_index:03d}"
            for item in trace.llm_invocations:
                llm_invocations.append(
                    {**item, "conversationId": conversation_id, "turnId": turn_id}
                )
            for item in trace.tool_invocations:
                tool_invocations.append(
                    {**item, "conversationId": conversation_id, "turnId": turn_id}
                )
            for item in trace.context_events:
                context_events.append(
                    {**item, "conversationId": conversation_id, "turnId": turn_id}
                )
            for item in trace.memory_events:
                memory_events.append(
                    {**item, "conversationId": conversation_id, "turnId": turn_id}
                )
            for item in trace.artifacts:
                artifacts.append(
                    {**item, "conversationId": conversation_id, "turnId": turn_id}
                )
        status = (
            "SUCCESS"
            if all(item.status == "SUCCESS" for item in turn_traces) and error is None
            else "FAILED"
        )
        final_text = turn_traces[-1].final_text
        total_tokens = _sum_optional([item.total_tokens for item in turn_traces])
        duration_ms = _sum_optional([item.duration_ms for item in turn_traces])
        payload = {
            "schemaVersion": "1.0",
            "producer": {"agent": "cute-agent", "exportedAt": _now()},
            "run": {
                "caseId": task.id,
                "conversationId": conversation_id,
                "requestId": conversation_id,
                "sessionId": conversation_id,
                "status": status,
                "finalSummaryText": final_text,
                "durationMs": duration_ms,
                "totalTokens": total_tokens,
                "llmCallCount": len(llm_invocations),
                "toolCallCount": len(tool_invocations),
                "artifactCount": len(artifacts),
                "turnCount": len(turn_traces),
                "terminationReason": termination_reason,
                "errorMsg": error,
            },
            "llmInvocations": llm_invocations,
            "toolInvocations": tool_invocations,
            "contextEvents": context_events,
            "memoryEvents": memory_events,
            "artifacts": artifacts,
            "conversation": conversation,
            "conversationSource": "runtime",
            "environmentEvents": environment_events,
            "environmentSource": "runtime" if environment_events else "unavailable",
            "environmentSnapshots": environment_snapshots,
            "snapshotSource": "runtime" if environment_snapshots else "unavailable",
            "environment": {"conversationId": conversation_id},
        }
        trace = RunTrace.from_dict(payload)
    trace.raw["conversation"] = conversation
    trace.raw["conversationSource"] = trace.raw.get("conversationSource") or "unavailable"
    trace.raw["contextEvents"] = list(trace.context_events)
    trace.raw["memoryEvents"] = list(trace.memory_events)
    trace.raw["environmentEvents"] = environment_events
    trace.raw["environmentSource"] = (
        trace.raw.get("environmentSource") or "unavailable"
    )
    trace.raw["environmentSnapshots"] = environment_snapshots
    trace.raw["snapshotSource"] = trace.raw.get("snapshotSource") or "unavailable"
    trace.conversation = conversation
    trace.conversation_source = str(trace.raw["conversationSource"])
    trace.context_events = list(trace.raw.get("contextEvents") or [])
    trace.memory_events = list(trace.raw.get("memoryEvents") or [])
    trace.environment_events = environment_events
    trace.environment_source = str(trace.raw["environmentSource"])
    trace.environment_snapshots = environment_snapshots
    trace.snapshot_source = str(trace.raw["snapshotSource"])
    return trace


def run_conversation(
    *,
    task: BenchmarkTask,
    agent: ConversationAgent,
    simulator: UserSimulator,
    workspace: Path,
    environment: TaskEnvironment | None = None,
    trace_path: Path,
    max_turns: int = 20,
    timeout_seconds: int = 600,
    conversation_id: str | None = None,
) -> ConversationRun:
    if max_turns < 1:
        raise ValueError("max_turns must be >= 1")
    workspace.mkdir(parents=True, exist_ok=True)
    conversation_id = conversation_id or f"eval-conversation-{uuid.uuid4().hex}"
    conversation: list[ConversationEntry] = []
    turn_traces: list[RunTrace] = []
    environment_events: list[EnvironmentEvent] = []
    environment_snapshots: list[EnvironmentSnapshot] = []
    error = None
    termination_reason = "agent_failure"

    try:
        if environment is not None:
            reset_snapshot = environment.reset(task)
            if reset_snapshot is not None:
                environment_snapshots.append(reset_snapshot)

        user_message = simulator.start(task, environment)
        if not user_message:
            raise RuntimeError("user simulator produced no initial message")

        for turn_index in range(1, max_turns + 1):
            turn_id = f"turn-{turn_index:03d}"
            conversation.append(
                ConversationEntry(
                    message_id=_stable_id(
                        "msg", f"{conversation_id}:{turn_index}:user"
                    ),
                    conversation_id=conversation_id,
                    turn_id=turn_id,
                    turn_index=turn_index,
                    role="user",
                    actor="user-simulator",
                    content=user_message,
                )
            )
            trace = agent.send(
                task=task,
                session_id=conversation_id,
                turn_id=turn_id,
                message=user_message,
                workspace=workspace,
                environment=environment,
                timeout_seconds=timeout_seconds,
            )
            turn_traces.append(trace)
            assistant_message = trace.final_text
            conversation.append(
                ConversationEntry(
                    message_id=_stable_id(
                        "msg", f"{conversation_id}:{turn_index}:assistant"
                    ),
                    conversation_id=conversation_id,
                    turn_id=turn_id,
                    turn_index=turn_index,
                    role="assistant",
                    actor="cute-agent",
                    content=assistant_message,
                    metadata={"runStatus": trace.status},
                )
            )
            if environment is not None:
                environment_events.extend(environment.events(turn_id))
                environment_snapshots.append(environment.snapshot(turn_id))
            if trace.status != "SUCCESS":
                termination_reason = "agent_failure"
                error = f"agent turn {turn_index} ended with {trace.status}"
                break
            next_message = simulator.respond(
                task=task,
                history=[item.to_dict() for item in conversation],
                assistant_message=assistant_message,
                environment=environment,
            )
            if simulator.error():
                termination_reason = "user_simulator_error"
                error = simulator.error()
                break
            if not next_message:
                termination_reason = simulator.termination_reason()
                break
            user_message = next_message
        else:
            termination_reason = "max_turns"
            error = f"conversation exceeded {max_turns} turns"
    except Exception as exc:  # noqa: BLE001 - conversation boundary records failure
        error = str(exc)
        termination_reason = "environment_error"
    finally:
        if environment is not None:
            try:
                environment.close()
            except Exception as exc:  # noqa: BLE001 - cleanup must preserve primary error
                error = error or f"environment close failed: {exc}"

    conversation_payload = [item.to_dict() for item in conversation]
    event_payload = [item.to_dict() for item in environment_events]
    snapshot_payload = [item.to_dict() for item in environment_snapshots]
    trace = merge_trace(
        task=task,
        conversation_id=conversation_id,
        turn_traces=turn_traces,
        conversation=conversation_payload,
        environment_events=event_payload,
        environment_snapshots=snapshot_payload,
        termination_reason=termination_reason,
        error=error,
    )
    trace_path.parent.mkdir(parents=True, exist_ok=True)
    trace_path.write_text(
        json.dumps(trace.raw, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return ConversationRun(
        task=task,
        conversation_id=conversation_id,
        trace=trace,
        turn_traces=turn_traces,
        conversation=conversation_payload,
        environment_events=event_payload,
        environment_snapshots=snapshot_payload,
        termination_reason=termination_reason,
        error=error,
    )


def snapshot_payload(kind: str, payload: Any, *, turn_id: str) -> EnvironmentSnapshot:
    digest = _json_hash(payload)
    return EnvironmentSnapshot(
        snapshot_id=_stable_id("snapshot", f"{turn_id}:{digest}"),
        kind=kind,
        payload=payload,
        sha256=digest,
        turn_id=turn_id,
    )
