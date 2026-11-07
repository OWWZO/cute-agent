from __future__ import annotations

from pathlib import Path

from cute_evals.case_loader import load_case_dirs


def test_production_core_is_stratified_and_loadable() -> None:
    root = Path(__file__).parents[1]
    cases = load_case_dirs(
        [root / "cases" / "production" / "core"],
        suite="production-core",
    )

    assert len(cases) == 24
    assert {case.metadata.get("role") for case in cases} == {"validation"}
    assert {case.metadata.get("taskType") for case in cases} >= {
        "research",
        "recovery",
        "data_analysis",
        "clarification",
        "tool_discovery",
        "memory",
        "skill_use",
        "file_analysis",
    }


def test_private_holdout_is_separate_and_loadable() -> None:
    root = Path(__file__).parents[1]
    cases = load_case_dirs(
        [root / "cases" / "private" / "holdout"],
        suite="holdout-core",
    )

    assert len(cases) == 16
    assert {case.metadata.get("role") for case in cases} == {"holdout"}
    assert {case.metadata.get("taskType") for case in cases} >= {
        "security",
        "research",
        "recovery",
        "data_analysis",
        "clarification",
        "tool_discovery",
    }
