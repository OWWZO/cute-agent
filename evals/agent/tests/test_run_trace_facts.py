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
            "caseId": "run.facts",
            "runUid": "run-uid-001",
            "requestId": "req-run-facts",
            "sessionId": "sess-run-facts",
            "userIdHash": (
                "sha256:fcdec6df4d44dbc637c7c5b58efface"
                "52a7f8a88535423430255be0bb89bedd8"
            ),
            "status": "SUCCESS",
            "finalSummaryText": "ok",
        },
        "llmInvocations": [],
        "toolInvocations": [
            {
                "toolCallId": "call-child",
                "parentToolCallId": "call-parent",
                "subAgentId": "agent-child",
                "subAgentType": "research",
                "subAgentDescription": "scan repository",
                "toolName": "workspace_read",
                "status": "SUCCESS",
                "artifactCount": 0,
            },
            {
                "toolCallId": "call-main",
                "toolName": "workspace_read",
                "status": "SUCCESS",
                "artifactCount": None,
            },
        ],
        "artifacts": [],
        "environment": {},
    }


def test_run_and_subagent_facts_are_valid_trace_fields() -> None:
    raw = _trace()
    jsonschema.validate(raw, TRACE_SCHEMA)

    trace = RunTrace.from_dict(raw)

    assert trace.run_uid == "run-uid-001"
    assert trace.user_id_hash.startswith("sha256:")
    assert "userId" not in raw["run"]
    attributed = trace.tool_invocations[0]
    assert attributed["subAgentId"] == "agent-child"
    assert attributed["subAgentType"] == "research"
    assert attributed["subAgentDescription"] == "scan repository"
    assert attributed["artifactCount"] == 0
    assert trace.tool_invocations[1]["artifactCount"] is None


def test_legacy_trace_without_run_and_subagent_facts_remains_valid() -> None:
    raw = _trace()
    raw["schemaVersion"] = "1.0"
    for key in ("runUid", "userIdHash"):
        raw["run"].pop(key)
    for tool in raw["toolInvocations"]:
        for key in (
            "subAgentId",
            "subAgentType",
            "subAgentDescription",
            "artifactCount",
        ):
            tool.pop(key, None)

    jsonschema.validate(raw, TRACE_SCHEMA)
    trace = RunTrace.from_dict(raw)

    assert trace.run_uid is None
    assert trace.user_id_hash is None
