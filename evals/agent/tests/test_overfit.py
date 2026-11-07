from __future__ import annotations

import json
from pathlib import Path

import pytest

from cute_evals.cli import build_parser
from cute_evals.models import BudgetSpec, EvalCase, ExpectedSpec
from cute_evals.overfit import (
    audit_case_separation,
    build_overfit_report,
    overfit_blocks,
)


def make_case(
    case_id: str,
    suite: str,
    query: str,
    *,
    task_type: str = "research",
) -> EvalCase:
    return EvalCase(
        id=case_id,
        suite=suite,
        version=1,
        hard_gate=True,
        query=query,
        expected=ExpectedSpec(final_contains=["ok"]),
        budgets=BudgetSpec(max_total_tokens=100),
        script=[{"type": "final", "content": "ok"}],
        source_path=f"{case_id}.yaml",
        metadata={"taskType": task_type},
    )


def write_report(
    path: Path,
    *,
    suite: str,
    case_count: int,
    pass_at_1: float,
    pass_at_k: float,
) -> None:
    path.write_text(
        json.dumps(
            {
                "environment": {"suite": suite},
                "summary": {
                    "caseCount": case_count,
                    "passAt1": pass_at_1,
                    "passAtK": pass_at_k,
                    "confidenceIntervals": {
                        "level": 0.95,
                        "method": "wilson",
                    },
                },
                "caseSummaries": [
                    {
                        "caseId": f"case-{index}",
                        "medianTotalTokens": 100,
                        "medianDurationMs": 10,
                    }
                    for index in range(case_count)
                ],
            }
        ),
        encoding="utf-8",
    )


def test_case_separation_detects_shared_query_and_clone() -> None:
    validation = [
        make_case("validation.one", "production-core", "读取配置并回答"),
    ]
    holdout = [
        make_case("holdout.one", "holdout-core", "读取配置并回答"),
    ]

    audit = audit_case_separation(validation, holdout)

    assert audit["leak"] is True
    assert audit["sharedQueryPairs"]
    assert audit["clonedCasePairs"]


def test_overfit_report_flags_large_holdout_gap(tmp_path: Path) -> None:
    validation_report = tmp_path / "validation.json"
    holdout_report = tmp_path / "holdout.json"
    write_report(
        validation_report,
        suite="production-core",
        case_count=24,
        pass_at_1=0.95,
        pass_at_k=0.90,
    )
    write_report(
        holdout_report,
        suite="holdout-core",
        case_count=16,
        pass_at_1=0.60,
        pass_at_k=0.50,
    )
    validation = [
        make_case(
            f"validation.{index}",
            "production-core",
            f"validation query {index}",
        )
        for index in range(24)
    ]
    holdout = [
        make_case(
            f"holdout.{index}",
            "holdout-core",
            f"holdout query {index}",
            task_type="security",
        )
        for index in range(16)
    ]

    report = build_overfit_report(
        validation_report_path=validation_report,
        holdout_report_path=holdout_report,
        validation_cases=validation,
        holdout_cases=holdout,
        output_dir=tmp_path / "out",
    )

    assert report["verdict"] == "overfit-risk"
    assert report["gaps"]["passAtK"] == pytest.approx(0.4)
    assert report["sampleLimited"] is True
    assert overfit_blocks(report) is True
    assert (tmp_path / "out" / "overfit-report.html").exists()


def test_ops_overfit_command_is_registered() -> None:
    args = build_parser().parse_args(
        [
            "ops",
            "overfit",
            "--validation-report",
            "validation.json",
            "--holdout-report",
            "holdout.json",
        ]
    )

    assert args.func.__name__ == "ops_overfit_command"
    assert args.fail_on_risk is False
