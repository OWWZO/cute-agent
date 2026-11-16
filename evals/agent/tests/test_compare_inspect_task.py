from __future__ import annotations

from pathlib import Path

from cute_evals.compare.inspect_task import run_compare_inspect
from cute_evals.compare.scoring import score_compare_case
from cute_evals.models import BudgetSpec, EvalCase, ExpectedSpec, RunTrace


def test_compare_inspect_creates_one_sample_per_case(tmp_path: Path) -> None:
    case = EvalCase(
        id="compare.inspect",
        suite="compare-pilot",
        version=1,
        hard_gate=True,
        query="say ok",
        expected=ExpectedSpec(final_contains=["ok"]),
        budgets=BudgetSpec(max_total_tokens=100),
        script=[],
        source_path="pilot.json",
    )
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "requestId": "req-1",
                "status": "SUCCESS",
                "finalSummaryText": "ok",
                "totalTokens": 10,
                "durationMs": 5,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {"agent": "codex"},
        }
    )
    result = score_compare_case(
        case,
        trace,
        "attempts/01/traces/compare.inspect/trace.json",
        attempt=1,
        workspace_check=None,
    )

    log_dir = run_compare_inspect(
        agent="codex",
        suite="compare-pilot",
        case_queries={case.id: case.query},
        results=[result],
        output_dir=tmp_path,
        mode="controlled",
    )

    assert log_dir.exists()
    assert list(log_dir.glob("*.eval"))
