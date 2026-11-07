from __future__ import annotations

import json
from pathlib import Path

from cute_evals.models import (
    BudgetSpec,
    CaseResult,
    EvalCase,
    ExpectedSpec,
    RunTrace,
    ScoreRecord,
)
from cute_evals.ops import (
    build_matrix_report,
    build_trend_report,
    load_history,
    record_release,
    record_report,
    trend_blocks,
)
from cute_evals.report import build_baseline, build_report


def _case() -> EvalCase:
    return EvalCase(
        id="react.ops",
        suite="react-core",
        version=1,
        hard_gate=True,
        query="answer",
        expected=ExpectedSpec(final_contains=["ok"]),
        budgets=BudgetSpec(max_total_tokens=200),
        script=[{"type": "final", "content": "ok"}],
        source_path="test.yaml",
    )


def _result(*, passed: bool, tokens: int, attempt: int = 1) -> CaseResult:
    case = _case()
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "requestId": f"req-{attempt}",
                "status": "SUCCESS",
                "finalSummaryText": "ok" if passed else "bad",
                "totalTokens": tokens,
                "durationMs": 10,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
        }
    )
    score = ScoreRecord(
        case_id=case.id,
        scorer="deterministic",
        name="task_success",
        value=passed,
        unit="boolean",
        passed=passed,
        hard_gate=True,
        status="verified",
    )
    return CaseResult(
        case=case,
        trace=trace,
        scores=[score],
        trace_path="trace.json",
        attempt=attempt,
    )


def test_ops_history_dedup_and_trend_regression(tmp_path: Path) -> None:
    first = build_report(
        profile="nightly",
        agent="cute-agent",
        cases=[_result(passed=True, tokens=100)],
        output_dir=tmp_path / "first",
        environment={"suite": "react-core", "javaMode": "live", "datasetHash": "d1"},
    )
    baseline = build_baseline(first)
    build_report(
        profile="nightly",
        agent="cute-agent",
        cases=[_result(passed=False, tokens=150)],
        output_dir=tmp_path / "second",
        baseline=baseline,
        environment={"suite": "react-core", "javaMode": "live", "datasetHash": "d1"},
    )
    history = tmp_path / "history.jsonl"

    record, created = record_report(
        tmp_path / "first" / "report.json",
        history,
        series="react-core",
        variant="deepseek",
    )
    duplicate, duplicate_created = record_report(
        tmp_path / "first" / "report.json",
        history,
        series="react-core",
        variant="deepseek",
    )
    second_record, second_created = record_report(
        tmp_path / "second" / "report.json",
        history,
        series="react-core",
        variant="deepseek",
    )

    assert created is True
    assert duplicate_created is False
    assert duplicate["recordKey"] == record["recordKey"]
    assert second_created is True
    assert len(load_history(history)) == 2
    assert second_record["summary"]["failed"] == 1

    trend = build_trend_report(history, tmp_path / "trend")

    assert trend["summary"]["groupCount"] == 1
    assert trend["groups"][0]["status"] == "regression"
    assert trend["groups"][0]["regressions"] == ["react.ops"]
    assert trend["groups"][0]["metrics"]["tokenRatio"] == 1.5
    assert trend_blocks(trend) is True
    assert (tmp_path / "trend" / "trend.html").exists()


def _write_ab_report(
    path: Path,
    *,
    experiment_id: str,
    recommendation: str,
) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "schemaVersion": "1.0",
                "experimentId": experiment_id,
                "experimentSpecHash": f"spec-{experiment_id}",
                "generatedAt": "2026-11-06T12:00:00",
                "experiment": {
                    "suite": "tool-discovery",
                    "profile": "nightly",
                    "javaMode": "live",
                    "models": ["group/deepseek"],
                    "repetitions": 3,
                },
                "summary": {},
                "arms": {
                    "base": {"meanTaskScore": 0.8},
                    "candidate": {"meanTaskScore": 0.9},
                },
                "metricDeltas": {
                    "meanTaskScore": 0.1,
                    "passAt1": 0.1,
                    "passAtK": 0.1,
                    "totalTokensRatio": 1.05,
                },
                "tasks": [],
                "statusCounts": {},
                "anomalies": [],
                "recommendation": recommendation,
            }
        ),
        encoding="utf-8",
    )
    return path


def test_ops_matrix_aggregates_multiple_ab_reports(tmp_path: Path) -> None:
    first = _write_ab_report(
        tmp_path / "a" / "report.json",
        experiment_id="prompt-a",
        recommendation="ship",
    )
    second = _write_ab_report(
        tmp_path / "b" / "report.json",
        experiment_id="tool-schema-b",
        recommendation="block",
    )

    matrix = build_matrix_report([first, second], tmp_path / "matrix")

    assert matrix["summary"]["experimentCount"] == 2
    assert matrix["summary"]["recommendations"] == {"block": 1, "ship": 1}
    assert matrix["summary"]["hasBlock"] is True
    assert [row["experimentId"] for row in matrix["rows"]] == [
        "prompt-a",
        "tool-schema-b",
    ]
    assert (tmp_path / "matrix" / "matrix.json").exists()
    assert (tmp_path / "matrix" / "matrix.html").exists()


def test_ops_release_blocks_failed_or_uncompared_report(tmp_path: Path) -> None:
    baseline_report = build_report(
        profile="release",
        agent="cute-agent",
        cases=[_result(passed=True, tokens=100)],
        output_dir=tmp_path / "baseline",
    )
    build_report(
        profile="release",
        agent="cute-agent",
        cases=[_result(passed=True, tokens=100)],
        output_dir=tmp_path / "passing",
        baseline=build_baseline(baseline_report),
    )
    build_report(
        profile="release",
        agent="cute-agent",
        cases=[_result(passed=False, tokens=100)],
        output_dir=tmp_path / "failing",
        baseline=build_baseline(baseline_report),
    )
    build_report(
        profile="release",
        agent="cute-agent",
        cases=[_result(passed=True, tokens=100)],
        output_dir=tmp_path / "uncompared",
    )

    shipped = record_release(
        tmp_path / "passing" / "report.json",
        tmp_path / "release-pass",
        version="v1.0.0",
        commit="abc123",
        model="group/deepseek",
        min_pass_at_k=1.0,
    )
    blocked = record_release(
        tmp_path / "failing" / "report.json",
        tmp_path / "release-fail",
        version="v1.0.1",
        commit="def456",
        model="group/deepseek",
    )
    no_baseline = record_release(
        tmp_path / "uncompared" / "report.json",
        tmp_path / "release-no-baseline",
        version="v1.0.2",
        commit="ghi789",
        model="group/deepseek",
    )

    assert shipped["decision"] == "ship"
    assert all(item["passed"] for item in shipped["checks"])
    assert blocked["decision"] == "block"
    assert no_baseline["decision"] == "block"
    assert (
        tmp_path / "release-pass" / "release-record.json"
    ).exists()
