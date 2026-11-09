from __future__ import annotations

import json
from pathlib import Path

import jsonschema

from cute_evals.models import RunTrace


TRACE_SCHEMA = json.loads(
    (
        Path(__file__).parents[1] / "schemas" / "trace.schema.json"
    ).read_text(encoding="utf-8")
)


def _trace() -> dict:
    return {
        "schemaVersion": "1.1",
        "producer": {"agent": "cute-agent"},
        "run": {
            "caseId": "conversation.source",
            "requestId": "req-conversation-001",
            "sessionId": "session-conversation-001",
            "status": "SUCCESS",
            "finalSummaryText": "done",
        },
        "llmInvocations": [],
        "toolInvocations": [],
        "contextEvents": [],
        "memoryEvents": [],
        "conversation": [
            {
                "messageId": "101",
                "conversationId": "session-conversation-001",
                "turnId": "10",
                "turnIndex": 1,
                "requestId": "req-conversation-001",
                "runId": 7,
                "seqNo": 0,
                "role": "user",
                "actor": "user",
                "content": "hello",
                "toolCallId": None,
                "toolCalls": None,
                "messageKind": "text",
                "visibility": "VISIBLE",
                "tokenEstimate": 3,
                "timestamp": "2026-11-08T13:00:00",
            }
        ],
        "conversationSource": "working_memory",
        "environmentEvents": [],
        "environmentSource": "unavailable",
        "environmentSnapshots": [],
        "snapshotSource": "unavailable",
        "artifacts": [],
        "environment": {},
    }


def test_conversation_and_source_fields_are_valid_trace_facts() -> None:
    raw = _trace()
    jsonschema.validate(raw, TRACE_SCHEMA)

    trace = RunTrace.from_dict(raw)

    assert trace.conversation_source == "working_memory"
    assert trace.environment_source == "unavailable"
    assert trace.snapshot_source == "unavailable"
    assert trace.conversation[0]["turnIndex"] == 1
    assert trace.conversation[0]["requestId"] == "req-conversation-001"


def test_legacy_trace_without_source_fields_remains_valid() -> None:
    raw = _trace()
    for key in (
        "conversationSource",
        "environmentSource",
        "snapshotSource",
    ):
        raw.pop(key)

    jsonschema.validate(raw, TRACE_SCHEMA)
    trace = RunTrace.from_dict(raw)

    assert trace.conversation_source is None
    assert trace.environment_source is None
    assert trace.snapshot_source is None
