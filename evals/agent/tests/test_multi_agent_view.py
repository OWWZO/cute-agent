from __future__ import annotations

import json
from pathlib import Path

import pytest
from jsonschema import validate

from cute_evals.models import RunTrace
from cute_evals.multi_agent_view import (
    MultiAgentViewError,
    project_multi_agent_view,
)


def _trace() -> dict:
    return {
        "schemaVersion": "1.0",
        "producer": {"agent": "cute-agent"},
        "run": {
            "runUid": "run-ma",
            "requestId": "req-ma",
            "sessionId": "sess-ma",
            "status": "SUCCESS",
            "queryText": "delegate the scan",
            "finalSummaryText": "done",
        },
        "conversation": [
            {"role": "user", "content": "delegate the scan"},
            {
                "role": "assistant",
                "content": "",
                "toolCalls": [
                    {
                        "id": "main-call",
                        "type": "function",
                        "function": {"name": "read_file", "arguments": "{}"},
                    }
                ],
            },
            {"role": "tool", "toolCallId": "main-call", "content": "ok"},
            {"role": "assistant", "content": "done"},
        ],
        "llmInvocations": [],
        "toolInvocations": [
            {
                "toolCallId": "main-call",
                "toolName": "read_file",
                "status": "SUCCESS",
                "durationMs": 1,
            },
            {
                "toolCallId": "child-call",
                "subAgentId": "agent-child",
                "subAgentType": "research",
                "subAgentDescription": "scan repository",
                "toolName": "grep",
                "status": "SUCCESS",
                "durationMs": 2,
            },
        ],
    }


def test_projects_agent_tree_tools_and_handoff() -> None:
    view = project_multi_agent_view(RunTrace.from_dict(_trace()))

    assert [agent.agent_id for agent in view.agents] == ["main", "agent-child"]
    assert view.agents[0].tools[0].tool_name == "read_file"
    assert view.agents[1].agent_type == "research"
    assert view.agents[1].tools[0].tool_name == "grep"
    assert view.handoffs[0].to_agent_id == "agent-child"
    assert view.metrics == {
        "agentCount": 2,
        "subAgentCount": 1,
        "toolCount": 2,
        "toolSuccessRate": 1.0,
        "handoffCount": 1,
    }


def test_multi_agent_view_matches_schema() -> None:
    schema_path = (
        Path(__file__).resolve().parents[1]
        / "schemas"
        / "multi_agent_trace.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))

    validate(
        project_multi_agent_view(RunTrace.from_dict(_trace())).to_dict(),
        schema,
    )


def test_rejects_conflicting_subagent_identity() -> None:
    payload = _trace()
    payload["toolInvocations"].append(
        {
            "toolCallId": "child-call-2",
            "subAgentId": "agent-child",
            "subAgentType": "coding",
            "toolName": "write_file",
            "status": "SUCCESS",
        }
    )

    with pytest.raises(MultiAgentViewError, match="conflicting types"):
        project_multi_agent_view(RunTrace.from_dict(payload))
