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
            "caseId": "memory.fork",
            "requestId": "req-memory-fork-001",
            "sessionId": "session-memory-fork-001",
            "status": "SUCCESS",
            "finalSummaryText": "done",
        },
        "llmInvocations": [],
        "toolInvocations": [],
        "contextEvents": [],
        "memoryEvents": [
            {
                "eventId": "memory-fork:1",
                "type": "memory_fork",
                "sessionId": "session-memory-fork-001",
                "triggerRequestId": "req-memory-fork-001",
                "forkRequestId": "req-memory-fork-001-flush",
                "forkKind": "flush",
                "status": "success",
                "skipReason": None,
                "ownerType": "USER",
                "ownerHash": (
                    "sha256:fcdec6df4d44dbc637c7c5b58efface"
                    "52a7f8a88535423430255be0bb89bedd8"
                ),
                "userTurns": 4,
                "snapshotMessageCount": 10,
                "maxSteps": 8,
                "timeoutSeconds": 30,
                "durationMs": 120,
                "entriesBefore": 3,
                "entriesAfter": 5,
                "appliedCount": 2,
                "errorCode": None,
                "startedAt": None,
                "finishedAt": "2026-11-08T12:00:00",
            },
            {
                "eventId": "memory-fork:2",
                "type": "memory_fork",
                "status": "timeout",
                "forkKind": "bg-review",
                "errorCode": "MEMORY_FORK_TIMEOUT",
            },
        ],
        "artifacts": [],
        "environment": {},
    }


def test_memory_events_are_valid_trace_facts() -> None:
    raw = _trace()
    jsonschema.validate(raw, TRACE_SCHEMA)

    trace = RunTrace.from_dict(raw)

    assert len(trace.memory_events) == 2
    assert trace.memory_events[0]["forkKind"] == "flush"
    assert trace.memory_events[0]["ownerHash"].startswith("sha256:")
    assert trace.memory_events[1]["errorCode"] == "MEMORY_FORK_TIMEOUT"


def test_legacy_trace_without_memory_events_remains_valid() -> None:
    raw = _trace()
    raw.pop("memoryEvents")

    jsonschema.validate(raw, TRACE_SCHEMA)
    trace = RunTrace.from_dict(raw)

    assert trace.memory_events == []
