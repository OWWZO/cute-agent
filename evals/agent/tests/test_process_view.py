from __future__ import annotations

import json
from pathlib import Path

import pytest
from jsonschema import validate

from cute_evals.models import RunTrace
from cute_evals.process_view import ProcessViewError, project_process_view


def _trace() -> dict:
    return {
        "schemaVersion": "1.0",
        "producer": {"agent": "cute-agent"},
        "run": {
            "caseId": "react.process-view",
            "runUid": "run-1",
            "requestId": "req-1",
            "sessionId": "sess-1",
            "status": "SUCCESS",
            "queryText": "summarize the file",
            "finalSummaryText": "summary",
        },
        "conversationSource": "ledger",
        "conversation": [
            {
                "messageId": "m-user",
                "role": "user",
                "content": "summarize the file",
            },
            {
                "messageId": "m-a1",
                "role": "assistant",
                "content": "",
                "toolCalls": [
                    {
                        "id": "call-1",
                        "type": "function",
                        "function": {
                            "name": "read_file",
                            "arguments": "{\"path\":\"a.txt\"}",
                        },
                    }
                ],
            },
            {
                "messageId": "m-t1",
                "role": "tool",
                "toolCallId": "call-1",
                "content": "alpha",
            },
            {
                "messageId": "m-a2",
                "role": "assistant",
                "content": "summary",
            },
        ],
        "llmInvocations": [
            {
                "id": 11,
                "stepNo": 1,
                "status": "SUCCESS",
                "modelName": "fixture",
                "totalTokens": 30,
            },
            {
                "id": 12,
                "stepNo": 2,
                "status": "SUCCESS",
                "modelName": "fixture",
                "totalTokens": 20,
            },
        ],
        "toolInvocations": [
            {
                "toolCallId": "call-1",
                "toolName": "read_file",
                "status": "SUCCESS",
                "durationMs": 5,
            }
        ],
        "artifacts": [],
        "tools": [
            {
                "type": "function",
                "function": {"name": "read_file"},
            }
        ],
    }


def test_projects_assistant_steps_and_attaches_evidence() -> None:
    view = project_process_view(RunTrace.from_dict(_trace()))

    assert view.assistant_message_indices == [1, 3]
    assert len(view.steps) == 2
    assert [step.ordinal for step in view.steps] == [1, 2]
    assert view.steps[0].evidence[0].kind == "llm"
    assert [item.kind for item in view.steps[0].evidence] == [
        "llm",
        "tool_call",
        "tool_result",
    ]
    assert view.steps[0].evidence[-1].status == "SUCCESS"
    assert view.steps[1].content == "summary"
    assert view.tools[0]["function"]["name"] == "read_file"


def test_projection_is_stable_for_same_trace() -> None:
    first = project_process_view(RunTrace.from_dict(_trace())).to_dict()
    second = project_process_view(RunTrace.from_dict(_trace())).to_dict()

    assert first == second
    assert [step["stepId"] for step in first["steps"]] == [
        step["stepId"] for step in second["steps"]
    ]


def test_process_view_matches_schema() -> None:
    schema_path = (
        Path(__file__).resolve().parents[1]
        / "schemas"
        / "process_view.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    payload = project_process_view(RunTrace.from_dict(_trace())).to_dict()

    validate(payload, schema)


def test_rejects_trace_without_assistant_message() -> None:
    payload = _trace()
    payload["conversation"] = [
        {"messageId": "m-user", "role": "user", "content": "hello"}
    ]

    with pytest.raises(ProcessViewError, match="no assistant messages"):
        project_process_view(RunTrace.from_dict(payload))


def test_rejects_trace_without_conversation() -> None:
    payload = _trace()
    payload["conversation"] = []

    with pytest.raises(ProcessViewError, match="no conversation messages"):
        project_process_view(RunTrace.from_dict(payload))
