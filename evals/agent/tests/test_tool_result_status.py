from __future__ import annotations

import pytest

from cute_evals.models import RunTrace
from cute_evals.multi_agent_view import project_multi_agent_view
from cute_evals.scorers import score_case
from cute_evals.models import BudgetSpec, EvalCase, ExpectedSpec


def _case() -> EvalCase:
    return EvalCase(
        id="react.tool-status",
        suite="react-core",
        version=1,
        hard_gate=True,
        query="answer",
        expected=ExpectedSpec(final_contains=["ok"]),
        budgets=BudgetSpec(max_total_tokens=100),
        script=[{"type": "final", "content": "ok"}],
        source_path="test.yaml",
    )


def _trace(tools: list[dict], *, status: str = "SUCCESS") -> RunTrace:
    return RunTrace.from_dict(
        {
            "run": {
                "caseId": "react.tool-status",
                "requestId": "req-status-1",
                "status": status,
                "finalSummaryText": "done",
            },
            "llmInvocations": [],
            "toolInvocations": tools,
            "artifacts": [],
            "conversation": [
                {"role": "user", "content": "task"},
                {"role": "assistant", "content": "done"},
            ],
        }
    )


@pytest.mark.parametrize(
    "result",
    [
        {"ok": False, "message": "soft fail"},
        {"ok": True, "message": "code_execution 403 — code execution is forbidden"},
        {"ok": True, "message": "连接失败"},
        {"ok": True, "message": "Pytest collection error: no tests ran / pytest 未执行"},
        '{"ok": true, "error": "upload failed"}',
    ],
)
def test_status_success_with_failure_text_is_not_a_successful_tool(result: object) -> None:
    trace = _trace(
        [
            {
                "toolName": "code_execution",
                "status": "SUCCESS",
                "toolResult": result,
            }
        ]
    )

    assert trace.successful_tool_names() == []
    assert trace.tool_success_rate() == 0.0


def test_subagent_hard_failure_marks_parent_run_failed() -> None:
    trace = _trace(
        [
            {
                "toolName": "Agent",
                "status": "SUCCESS",
                "subAgentId": "sub-1",
                "toolResult": {"ok": True, "task_id": "sub-1"},
            },
            {
                "toolName": "code_execution",
                "status": "SUCCESS",
                "subAgentId": "sub-1",
                "toolResult": {"ok": False, "message": "403 forbidden"},
            },
        ]
    )

    view = project_multi_agent_view(trace)

    assert view.status == "FAILED"
    assert any(tool.status == "FAILED" for tool in view.agents[1].tools)


def test_hard_tool_failure_is_a_hard_gate_failure() -> None:
    case = _case()
    trace = _trace(
        [
            {
                "toolName": "read_file",
                "status": "SUCCESS",
                "toolResult": {"ok": True, "message": "403 forbidden"},
            }
        ]
    ).raw
    trace["run"]["caseId"] = case.id
    trace["run"]["finalSummaryText"] = "ok"

    result = score_case(case, RunTrace.from_dict(trace), "trace.json")

    assert result.passed is False
