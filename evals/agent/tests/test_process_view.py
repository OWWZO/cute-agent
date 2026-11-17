from __future__ import annotations

import json
from pathlib import Path

import pytest
from jsonschema import validate

from cute_evals.models import RunTrace
from cute_evals.process_view import (
    ProcessViewError,
    project_ledger_process_view,
    project_process_view,
)


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


def test_scopes_conversation_and_final_text_to_current_request() -> None:
    payload = _trace()
    payload["run"]["requestId"] = "req-2"
    payload["run"]["finalSummaryText"] = "上一轮答案"
    payload["conversation"] = [
        {
            "messageId": "old-assistant",
            "requestId": "req-1",
            "role": "assistant",
            "content": "上一轮答案",
        },
        {
            "messageId": "current-user",
            "requestId": "req-2",
            "role": "user",
            "content": "继续处理",
        },
        {
            "messageId": "current-tool",
            "requestId": "req-2",
            "role": "assistant",
            "content": "",
            "toolCalls": [
                {
                    "id": "call-current",
                    "type": "function",
                    "function": {
                        "name": "read_file",
                        "arguments": "{\"path\":\"current.txt\"}",
                    },
                }
            ],
        },
        {
            "messageId": "current-result",
            "requestId": "req-2",
            "role": "tool",
            "toolCallId": "call-current",
            "content": "done",
        },
        {
            "messageId": "current-final",
            "requestId": "req-2",
            "role": "assistant",
            "content": "当前轮答案",
        },
    ]
    payload["toolInvocations"] = [
        {
            "toolCallId": "call-current",
            "toolName": "read_file",
            "status": "SUCCESS",
            "toolResult": "done",
        }
    ]

    view = project_process_view(RunTrace.from_dict(payload))

    assert [step.content for step in view.steps] == ["", "当前轮答案"]
    assert view.final_text == "当前轮答案"
    assert all(
        step.content != "上一轮答案"
        for step in view.steps
    )


def test_does_not_fall_back_to_previous_turn_final_summary() -> None:
    payload = _trace()
    payload["run"]["requestId"] = "req-2"
    payload["run"]["finalSummaryText"] = "上一轮答案"
    payload["conversation"] = [
        {
            "messageId": "old-assistant",
            "requestId": "req-1",
            "role": "assistant",
            "content": "上一轮答案",
        },
        {
            "messageId": "current-user",
            "requestId": "req-2",
            "role": "user",
            "content": "继续处理",
        },
        {
            "messageId": "current-tool",
            "requestId": "req-2",
            "role": "assistant",
            "content": "",
            "toolCalls": [],
        },
    ]

    view = project_process_view(RunTrace.from_dict(payload))

    assert view.final_text == ""


def test_final_text_can_be_attached_to_assistant_tool_call() -> None:
    payload = _trace()
    payload["run"]["requestId"] = "req-2"
    payload["run"]["finalSummaryText"] = "上一轮答案"
    payload["conversation"] = [
        {
            "messageId": "current-user",
            "requestId": "req-2",
            "role": "user",
            "content": "继续处理",
        },
        {
            "messageId": "current-assistant",
            "requestId": "req-2",
            "role": "assistant",
            "content": "当前轮的可见进度说明",
            "toolCalls": [
                {
                    "id": "call-current",
                    "type": "function",
                    "function": {
                        "name": "read_file",
                        "arguments": "{\"path\":\"current.txt\"}",
                    },
                }
            ],
        },
    ]

    view = project_process_view(RunTrace.from_dict(payload))

    assert view.final_text == "当前轮的可见进度说明"


def test_reconstructs_ledger_steps_without_conversation() -> None:
    payload = {
        "schemaVersion": "1.1",
        "producer": {"agent": "cute-agent"},
        "run": {
            "runUid": "run-ledger-1",
            "requestId": "req-ledger-1",
            "sessionId": "sess-ledger-1",
            "status": "SUCCESS",
            "queryText": "wake the sub-agent and inspect progress",
            "finalSummaryText": "the sub-agent is still running",
        },
        "llmInvocations": [
            {
                "id": 101,
                "invocationSeq": 1,
                "stepNo": 1,
                "agentName": "react",
                "status": "SUCCESS",
                "responseText": None,
                "reasoningContent": "plan the wake-up",
                "toolCallCount": 1,
                "totalTokens": 30,
            },
            {
                "id": 102,
                "invocationSeq": 2,
                "stepNo": 2,
                "agentName": "react",
                "status": "SUCCESS",
                "responseText": None,
                "reasoningContent": None,
                "toolCallCount": 1,
                "totalTokens": 20,
            },
            {
                "id": 103,
                "invocationSeq": 3,
                "stepNo": 3,
                "agentName": "react",
                "status": "SUCCESS",
                "responseText": "the sub-agent is still running",
                "toolCallCount": 0,
                "totalTokens": 10,
            },
        ],
        "toolInvocations": [
            {
                "id": 201,
                "llmInvocationId": 101,
                "toolCallId": "call-send",
                "stepNo": 1,
                "dispatchIndex": 1,
                "agentName": "react",
                "toolName": "SendMessage",
                "inputJson": "{\"to\":\"agent-1\"}",
                "toolResult": "{\"ok\":true}",
                "status": "SUCCESS",
            },
            {
                "id": 202,
                "llmInvocationId": 102,
                "toolCallId": "call-output",
                "stepNo": 2,
                "dispatchIndex": 1,
                "agentName": "react",
                "toolName": "TaskOutput",
                "inputJson": "{\"task_id\":\"task-1\"}",
                "toolResult": "{\"status\":\"running\"}",
                "status": "SUCCESS",
            },
        ],
        "artifacts": [],
        "tools": [],
    }

    view = project_ledger_process_view(RunTrace.from_dict(payload))

    assert view.source["conversationSource"] == "ledger-reconstructed"
    assert view.source["verification"]["responseTextMissing"] == 2
    assert view.assistant_message_indices == [1, 3, 5]
    assert [step.ordinal for step in view.steps] == [1, 2, 3]
    assert [message.role for message in view.messages] == [
        "user",
        "assistant",
        "tool",
        "assistant",
        "tool",
        "assistant",
    ]
    assert view.steps[0].content == ""
    assert [item.kind for item in view.steps[0].evidence] == [
        "llm",
        "tool_call",
        "tool_result",
    ]
    assert view.steps[2].content == "the sub-agent is still running"

    schema_path = (
        Path(__file__).resolve().parents[1]
        / "schemas"
        / "process_view.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    validate(view.to_dict(), schema)


def test_ledger_projection_preserves_one_llm_with_multiple_tools() -> None:
    payload = {
        "run": {
            "runUid": "run-ledger-2",
            "requestId": "req-ledger-2",
            "status": "SUCCESS",
            "queryText": "read both files",
            "finalSummaryText": "done",
        },
        "llmInvocations": [
            {
                "id": 301,
                "invocationSeq": 1,
                "stepNo": 1,
                "status": "SUCCESS",
                "toolCallCount": 2,
                "totalTokens": 12,
            }
        ],
        "toolInvocations": [
            {
                "id": 401,
                "llmInvocationId": 301,
                "toolCallId": "call-a",
                "dispatchIndex": 1,
                "toolName": "read_file",
                "inputJson": "{\"path\":\"a.txt\"}",
                "toolResult": "alpha",
                "status": "SUCCESS",
            },
            {
                "id": 402,
                "llmInvocationId": 301,
                "toolCallId": "call-b",
                "dispatchIndex": 2,
                "toolName": "read_file",
                "inputJson": "{\"path\":\"b.txt\"}",
                "toolResult": "beta",
                "status": "SUCCESS",
            },
        ],
        "artifacts": [],
    }

    view = project_ledger_process_view(RunTrace.from_dict(payload))

    assert view.assistant_message_indices == [1]
    assert [item.kind for item in view.steps[0].evidence] == [
        "llm",
        "tool_call",
        "tool_result",
        "tool_call",
        "tool_result",
    ]
    assert view.orphan_evidence == []
