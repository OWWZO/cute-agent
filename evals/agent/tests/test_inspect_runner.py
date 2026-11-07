from __future__ import annotations

from pathlib import Path

from cute_evals import inspect_runner
from cute_evals.models import BudgetSpec, EvalCase, ExpectedSpec, RunTrace


def test_inspect_runner_executes_solver_and_scorer(tmp_path: Path, monkeypatch) -> None:
    case = EvalCase(
        id="react.inspect",
        suite="react-core",
        version=1,
        hard_gate=True,
        query="say ok",
        expected=ExpectedSpec(final_contains=["ok"]),
        budgets=BudgetSpec(max_total_tokens=100),
        script=[{"type": "final", "content": "ok"}],
        source_path="test.yaml",
    )

    def fake_run_java_cases(cases, output_dir, **kwargs):
        assert cases == [case]
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
            }
        )
        return {case.id: trace}

    monkeypatch.setattr(inspect_runner, "run_java_cases", fake_run_java_cases)
    results = inspect_runner.run_inspect_attempt(
        [case],
        tmp_path,
        profile="pr",
        mode="fixture",
        attempt=1,
        trace_prefix="attempts/01",
        require_judge=False,
        judge=None,
        judge_cache_dir=None,
        timeout_seconds=30,
        build=False,
    )

    assert len(results) == 1
    assert results[0].passed
    assert (tmp_path / "inspect-logs").exists()
