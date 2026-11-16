from __future__ import annotations

from pathlib import Path

from cute_evals.compare.compare_report import build_comparison_report
from cute_evals.compare.dataset import load_compare_dataset


DATASET = (
    Path(__file__).resolve().parents[1]
    / "cases"
    / "compare"
    / "pilot.json"
)


def _report(
    status: str,
    duration: int,
    *,
    quality_passed: bool | None = None,
) -> dict:
    if quality_passed is None:
        quality_passed = status == "passed"
    return {
        "summary": {"passed": 1 if status == "passed" else 0},
        "caseSummaries": [
            {
                "caseId": "compare.docs.version",
                "status": status,
                "passAt1": status == "passed",
                "passAtK": status == "passed",
                "qualityPassAt1": quality_passed,
                "qualityPassAtK": quality_passed,
                "attemptCount": 1,
                "medianTotalTokens": 10,
                "medianDurationMs": duration,
                "medianFirstTokenMs": None,
                "failureCategories": {},
                "tracePaths": [],
            }
        ],
    }


def test_compare_report_marks_result_winner_without_overriding_failure() -> None:
    dataset = load_compare_dataset(DATASET)
    dataset.cases = [
        next(case for case in dataset.cases if case.id == "compare.docs.version")
    ]
    payload = build_comparison_report(
        dataset=dataset,
        mode="controlled",
        repeat=1,
        agent_reports={
            "cute": _report("passed", 100),
            "codex": _report("failed", 10),
        },
        agent_results={
            "cute": [
                {
                    "caseId": "compare.docs.version",
                    "toolCallCount": 2,
                }
            ],
            "codex": [
                {
                    "caseId": "compare.docs.version",
                    "toolCallCount": 1,
                }
            ],
        },
        agent_metadata={},
        environment={},
    )

    case = payload["cases"][0]
    assert case["resultWinner"] == "cute"
    assert case["efficiencyWinner"] == "codex"
    assert payload["summary"]["sampleAdequacy"] == "provisional"


def test_capability_compare_uses_quality_not_budget_slo() -> None:
    dataset = load_compare_dataset(DATASET)
    dataset.cases = [
        next(case for case in dataset.cases if case.id == "compare.docs.version")
    ]
    payload = build_comparison_report(
        dataset=dataset,
        mode="controlled",
        repeat=1,
        agent_reports={
            "cute": _report("failed", 100, quality_passed=True),
            "codex": _report("failed", 10, quality_passed=False),
        },
        agent_results={"cute": [], "codex": []},
        agent_metadata={},
        environment={"budgetMode": "capability"},
        quality_only=True,
    )

    assert payload["cases"][0]["resultWinner"] == "cute"
