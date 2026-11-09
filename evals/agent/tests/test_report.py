from __future__ import annotations

from pathlib import Path

from cute_evals.models import (
    BudgetSpec,
    CaseResult,
    EvalCase,
    ExpectedSpec,
    RunTrace,
    ScoreRecord,
)
from cute_evals.report import build_baseline, build_report, diff_baseline


def make_case() -> EvalCase:
    return EvalCase(
        id="react.repeat",
        suite="react-core",
        version=1,
        hard_gate=True,
        query="answer",
        expected=ExpectedSpec(final_contains=["ok"]),
        budgets=BudgetSpec(max_total_tokens=100),
        script=[{"type": "final", "content": "ok"}],
        source_path="test.yaml",
    )


def make_result(attempt: int, passed: bool, tokens: int) -> CaseResult:
    case = make_case()
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
        trace_path=f"attempts/{attempt:02d}/trace.json",
        attempt=attempt,
    )


def test_pass_at_k_and_baseline_regression(tmp_path: Path) -> None:
    first = build_report(
        profile="nightly",
        agent="cute-agent",
        cases=[
            make_result(1, True, 80),
            make_result(2, True, 82),
            make_result(3, True, 81),
        ],
        output_dir=tmp_path / "first",
    )
    assert first["summary"]["passAt1"] == 1.0
    assert first["summary"]["passAtK"] == 1.0

    baseline = build_baseline(first)
    current = build_report(
        profile="nightly",
        agent="cute-agent",
        cases=[
            make_result(1, True, 90),
            make_result(2, False, 95),
            make_result(3, True, 91),
        ],
        output_dir=tmp_path / "current",
        baseline=baseline,
    )

    assert current["summary"]["unstable"] == 1
    assert current["summary"]["passAtK"] == 0.0
    difference = diff_baseline(current, baseline)
    assert difference["regressions"] == ["react.repeat"]


def test_report_aggregates_tool_failure_classes(tmp_path: Path) -> None:
    case = make_case()
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "status": "SUCCESS",
                "finalSummaryText": "ok",
                "totalTokens": 10,
                "durationMs": 5,
            },
            "toolInvocations": [
                {"toolName": "a", "status": "TIMEOUT", "errorMsg": "timeout"},
                {"toolName": "b", "status": "FAILED", "errorMsg": "permission denied"},
                {
                    "toolName": "c",
                    "status": "FAILED",
                    "errorMsg": "upstream service unavailable (503)",
                },
                {
                    "toolName": "d",
                    "status": "FAILED",
                    "errorMsg": "Cannot connect to host 127.0.0.1:7890",
                },
            ],
        }
    )
    score = ScoreRecord(
        case_id=case.id,
        scorer="deterministic",
        name="task_success",
        value=True,
        unit="boolean",
        passed=True,
        hard_gate=True,
        status="verified",
    )
    report = build_report(
        profile="pr",
        agent="cute-agent",
        cases=[CaseResult(case=case, trace=trace, scores=[score], trace_path="trace.json")],
        output_dir=tmp_path,
    )

    assert report["summary"]["toolFailureClasses"] == {
        "network": 1,
        "permission": 1,
        "timeout": 1,
        "upstream": 1,
    }


def test_report_stratifies_cases_by_declared_metadata(tmp_path: Path) -> None:
    case = make_case()
    case.metadata = {
        "level": "2",
        "role": "holdout",
        "taskType": "research",
        "lengthBucket": "long",
        "toolCountBucket": "few",
        "risk": "high",
    }
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "status": "SUCCESS",
                "finalSummaryText": "ok",
                "totalTokens": 100,
                "durationMs": 20,
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
        value=True,
        unit="boolean",
        passed=True,
        hard_gate=True,
        status="verified",
    )

    report = build_report(
        profile="release",
        agent="cute-agent",
        cases=[CaseResult(case=case, trace=trace, scores=[score], trace_path="trace.json")],
        output_dir=tmp_path,
    )

    assert report["summary"]["strata"]["role"]["holdout"]["caseCount"] == 1
    assert report["summary"]["strata"]["risk"]["high"]["passAt1"] == 1.0
    assert report["summary"]["strata"]["level"]["2"]["caseCount"] == 1
    assert report["caseSummaries"][0]["metadata"]["taskType"] == "research"


def test_report_includes_wilson_confidence_intervals(tmp_path: Path) -> None:
    report = build_report(
        profile="pr",
        agent="cute-agent",
        cases=[make_result(1, True, 80)],
        output_dir=tmp_path,
    )

    intervals = report["summary"]["confidenceIntervals"]
    assert intervals["level"] == 0.95
    assert intervals["method"] == "wilson"
    assert 0.0 < intervals["passAt1"]["lower"] < 1.0
    assert intervals["passAt1"]["upper"] == 1.0
    assert intervals["passAtK"] == intervals["passAt1"]


def test_report_separates_official_scores_from_advisory_diagnostics(
    tmp_path: Path,
) -> None:
    case = make_case()
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "status": "SUCCESS",
                "finalSummaryText": "ok",
                "totalTokens": 100,
                "durationMs": 20,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
        }
    )
    scores = [
        ScoreRecord(
            case_id=case.id,
            scorer="memoryagentbench.official-text",
            name="official_accuracy",
            value=0.5,
            unit="ratio",
            passed=False,
            hard_gate=True,
            status="verified",
        ),
        ScoreRecord(
            case_id=case.id,
            scorer="memoryagentbench.diagnostic",
            name="diagnostic_format_normalized_accuracy",
            value=0.75,
            unit="ratio",
            passed=None,
            hard_gate=False,
            status="verified",
            evidence={"correct": 3, "checked": 4},
        ),
    ]

    report = build_report(
        profile="nightly",
        agent="cute-agent",
        cases=[
            CaseResult(
                case=case,
                trace=trace,
                scores=scores,
                trace_path="trace.json",
            )
        ],
        output_dir=tmp_path,
    )

    diagnostics = report["summary"]["diagnostics"]
    assert len(diagnostics["scores"]) == 1
    assert diagnostics["scores"][0]["name"] == (
        "diagnostic_format_normalized_accuracy"
    )
    assert diagnostics["metrics"][
        "diagnostic_format_normalized_accuracy"
    ]["mean"] == 0.75
    assert report["summary"]["passAt1"] == 0.0
    html = (tmp_path / "report.html").read_text(encoding="utf-8")
    assert "Official and gate scores" in html
    assert "Diagnostics (advisory only)" in html


def test_report_separates_answer_quality_from_slo_budget(tmp_path: Path) -> None:
    case = make_case()
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "requestId": "req-budget",
                "status": "FAILED",
                "errorCode": "EVAL_BUDGET_EXCEEDED",
                "finalSummaryText": "FINAL_ANSWER: ok",
                "totalTokens": 120,
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
        value=True,
        unit="boolean",
        passed=True,
        hard_gate=True,
        status="verified",
    )
    report = build_report(
        profile="nightly",
        agent="cute-agent",
        cases=[
            CaseResult(
                case=case,
                trace=trace,
                scores=[score],
                trace_path="trace.json",
            )
        ],
        output_dir=tmp_path,
    )

    assert report["summary"]["qualityPassAt1"] == 1.0
    assert report["summary"]["sloPassAt1"] == 0.0
    assert report["summary"]["passAt1"] == 0.0
    assert report["caseSummaries"][0]["qualityStatus"] == "passed"
    assert report["caseSummaries"][0]["sloStatus"] == "failed"
    assert report["caseSummaries"][0]["status"] == "failed"


def test_report_summarizes_compaction_and_control(tmp_path: Path) -> None:
    compressed_case = make_case()
    compressed_case.id = "compaction.compressed"
    compressed_case.suite = "compaction-carry-forward"
    compressed_case.metadata = {"taskType": "compaction"}
    compressed_trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": compressed_case.id,
                "status": "SUCCESS",
                "finalSummaryText": "ok",
                "totalTokens": 100,
                "durationMs": 10,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
        }
    )
    compressed_scores = [
        ScoreRecord(
            case_id=compressed_case.id,
            scorer="deterministic",
            name="task_success",
            value=True,
            unit="boolean",
            passed=True,
            hard_gate=True,
            status="verified",
        ),
        ScoreRecord(
            case_id=compressed_case.id,
            scorer="deterministic",
            name="compaction_triggered",
            value=True,
            unit="boolean",
            passed=True,
            hard_gate=True,
            status="verified",
            evidence={"strategies": ["full-llm"]},
        ),
        ScoreRecord(
            case_id=compressed_case.id,
            scorer="deterministic",
            name="compression_ratio",
            value=4.0,
            unit="ratio",
            passed=None,
            hard_gate=False,
            status="verified",
            evidence={"beforeTokens": 400, "afterTokens": 100},
        ),
    ]
    control_case = make_case()
    control_case.id = "compaction.control"
    control_case.suite = "compaction-carry-forward"
    control_trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": control_case.id,
                "status": "SUCCESS",
                "finalSummaryText": "ok",
                "totalTokens": 90,
                "durationMs": 9,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
        }
    )
    control_scores = [
        ScoreRecord(
            case_id=control_case.id,
            scorer="deterministic",
            name="task_success",
            value=True,
            unit="boolean",
            passed=True,
            hard_gate=True,
            status="verified",
        ),
        ScoreRecord(
            case_id=control_case.id,
            scorer="deterministic",
            name="compaction_control_clean",
            value=True,
            unit="boolean",
            passed=True,
            hard_gate=True,
            status="verified",
        ),
    ]

    report = build_report(
        profile="nightly",
        agent="cute-agent",
        cases=[
            CaseResult(
                case=compressed_case,
                trace=compressed_trace,
                scores=compressed_scores,
                trace_path="compressed/trace.json",
            ),
            CaseResult(
                case=control_case,
                trace=control_trace,
                scores=control_scores,
                trace_path="control/trace.json",
            ),
        ],
        output_dir=tmp_path,
    )

    compaction = report["summary"]["compaction"]
    assert compaction["triggeredCaseCount"] == 1
    assert compaction["carryForwardQualityPassCount"] == 1
    assert compaction["controlCleanCount"] == 1
    assert compaction["fullLlmCount"] == 1
    assert compaction["medianCompressionRatio"] == 4.0
    assert compaction["reclaimedTokens"] == 300
    assert "Compaction:" in (tmp_path / "report.html").read_text(encoding="utf-8")
