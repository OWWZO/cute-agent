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
            "caseId": "context.compaction",
            "requestId": "req-compaction-001",
            "sessionId": "session-compaction-001",
            "status": "SUCCESS",
            "finalSummaryText": "done",
        },
        "llmInvocations": [],
        "toolInvocations": [],
        "contextEvents": [
            {
                "eventId": "compaction:1",
                "type": "compaction",
                "sessionId": "session-compaction-001",
                "triggerRequestId": "req-compaction-001",
                "compactRequestId": "compact-1",
                "status": "success",
                "strategy": "full-llm",
                "beforeTokens": 1200,
                "afterTokens": 300,
                "beforeMessageCount": 12,
                "afterMessageCount": 3,
                "thresholdTokens": 1000,
                "summaryHash": (
                    "sha256:fcdec6df4d44dbc637c7c5b58efface"
                    "52a7f8a88535423430255be0bb89bedd8"
                ),
                "errorCode": None,
                "startedAt": None,
                "finishedAt": "2026-11-08T10:00:00",
            },
            {
                "eventId": "compaction:2",
                "type": "compaction",
                "status": "failed",
                "summaryHash": None,
                "errorCode": "COMPACTION_TIMEOUT",
            },
        ],
        "artifacts": [],
        "environment": {},
    }


def test_context_events_are_valid_trace_facts() -> None:
    raw = _trace()
    jsonschema.validate(raw, TRACE_SCHEMA)

    trace = RunTrace.from_dict(raw)

    assert len(trace.context_events) == 2
    assert trace.context_events[0]["eventId"] == "compaction:1"
    assert trace.context_events[0]["status"] == "success"
    assert trace.context_events[0]["summaryHash"].startswith("sha256:")
    assert trace.context_events[1]["errorCode"] == "COMPACTION_TIMEOUT"


def test_legacy_trace_without_context_events_remains_valid() -> None:
    raw = _trace()
    raw.pop("contextEvents")

    jsonschema.validate(raw, TRACE_SCHEMA)
    trace = RunTrace.from_dict(raw)

    assert trace.context_events == []
