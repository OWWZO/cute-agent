from __future__ import annotations

import json
import urllib.request
from pathlib import Path

import jsonschema

from cute_evals.benchmarks.base import BenchmarkTask
from cute_evals.models import RunTrace
from cute_evals.runtime import (
    EnvironmentEvent,
    ScriptedUserSimulator,
    UserSimulatorServer,
    run_conversation,
    snapshot_payload,
)


def _task() -> BenchmarkTask:
    return BenchmarkTask(
        id="multi-turn-1",
        category="tau2",
        question="Book the flight.",
        functions=[{"name": "book"}],
        ground_truth=None,
        raw={},
    )


def _trace(turn_id: str, text: str, tokens: int) -> RunTrace:
    return RunTrace.from_dict(
        {
            "run": {
                "caseId": "multi-turn-1",
                "requestId": f"request-{turn_id}",
                "sessionId": "shared-session",
                "status": "SUCCESS",
                "finalSummaryText": text,
                "totalTokens": tokens,
                "durationMs": 10,
            },
            "llmInvocations": [
                {
                    "id": int(turn_id.removeprefix("turn-")),
                    "totalTokens": tokens,
                }
            ],
            "toolInvocations": [
                {
                    "toolCallId": f"call-{turn_id}",
                    "toolName": "book",
                    "inputJson": '{"flight":"HAT001"}',
                    "status": "SUCCESS",
                }
            ],
            "artifacts": [],
            "environment": {"turnId": turn_id},
        }
    )


class FakeEnvironment:
    def __init__(self) -> None:
        self.reset_count = 0
        self.closed = False
        self.current_turn = ""

    def reset(self, task: BenchmarkTask):
        self.reset_count += 1
        return snapshot_payload("db", {"rows": []}, turn_id="reset")

    def snapshot(self, turn_id: str):
        self.current_turn = turn_id
        return snapshot_payload("db", {"rows": [turn_id]}, turn_id=turn_id)

    def events(self, turn_id: str) -> list[EnvironmentEvent]:
        return [
            EnvironmentEvent(
                event_id=f"event-{turn_id}",
                conversation_id="conversation",
                turn_id=turn_id,
                event_type="state_changed",
                payload={"turn": turn_id},
            )
        ]

    def close(self) -> None:
        self.closed = True


class FakeAgent:
    def __init__(self) -> None:
        self.session_ids: list[str] = []
        self.messages: list[str] = []

    def send(
        self,
        *,
        task,
        session_id,
        turn_id,
        message,
        workspace,
        environment,
        timeout_seconds,
    ):
        self.session_ids.append(session_id)
        self.messages.append(message)
        return _trace(turn_id, f"reply to {message}", 120)


def test_multiturn_trace_correlates_user_agent_tools_and_environment(tmp_path: Path) -> None:
    agent = FakeAgent()
    environment = FakeEnvironment()
    trace_path = tmp_path / "trace.json"

    run = run_conversation(
        task=_task(),
        agent=agent,
        simulator=ScriptedUserSimulator(["I need a flight", "Thank you"]),
        workspace=tmp_path / "workspace",
        environment=environment,
        trace_path=trace_path,
        max_turns=4,
    )

    assert run.error is None
    assert run.termination_reason == "script_exhausted"
    assert environment.reset_count == 1
    assert environment.closed is True
    assert agent.session_ids == [run.conversation_id, run.conversation_id]
    assert run.trace.total_tokens == 240
    assert run.trace.turn_count() == 2
    assert [item["role"] for item in run.conversation] == [
        "user",
        "assistant",
        "user",
        "assistant",
    ]
    assert all(item["conversationId"] == run.conversation_id for item in run.conversation)
    assert all(item["turnId"] == "turn-001" for item in run.trace.llm_invocations[:1])
    assert all(item["turnId"] == "turn-002" for item in run.trace.llm_invocations[1:])
    assert len(run.environment_events) == 2
    assert len(run.environment_snapshots) == 3
    payload = json.loads(trace_path.read_text(encoding="utf-8"))
    assert payload["run"]["turnCount"] == 2
    schema = json.loads(
        (Path(__file__).parents[1] / "schemas" / "trace.schema.json").read_text(
            encoding="utf-8"
        )
    )
    jsonschema.validate(payload, schema)


def test_user_simulator_http_adapter_is_script_driven() -> None:
    server = UserSimulatorServer(
        ScriptedUserSimulator(["hello", "next message"]),
        _task(),
    )
    try:
        initial = server.start()
        assert initial == "hello"
        request = urllib.request.Request(
            server.endpoint + "/next",
            data=json.dumps({"assistantMessage": "first reply"}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            payload = json.loads(response.read().decode("utf-8"))
        assert payload == {
            "ok": True,
            "message": "next message",
            "done": False,
            "reason": "script_exhausted",
            "error": None,
        }
    finally:
        server.close()
