from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from .benchmarks.workbuddybench import WorkBuddyBenchAdapter
from .models import (
    BudgetSpec,
    CaseResult,
    EvalCase,
    ExpectedSpec,
    RunTrace,
    ScoreRecord,
)
from .report import build_report


def _score_from_dict(case_id: str, raw: dict[str, Any]) -> ScoreRecord:
    return ScoreRecord(
        case_id=case_id,
        scorer=str(raw.get("scorer") or ""),
        name=str(raw.get("name") or ""),
        value=raw.get("value"),
        unit=str(raw.get("unit") or ""),
        passed=raw.get("pass"),
        hard_gate=bool(raw.get("hardGate")),
        status=str(raw.get("status") or "verified"),
        evidence=dict(raw.get("evidence") or {}),
    )


def _case_from_dict(raw: dict[str, Any]) -> EvalCase:
    metadata = dict(raw.get("metadata") or {})
    budgets = dict((metadata.get("budgetPolicy") or {}).get("effectiveBudgets") or {})
    return EvalCase(
        id=str(raw["caseId"]),
        suite=str(raw.get("suite") or "workbuddybench-internal"),
        version=1,
        hard_gate=True,
        query="",
        expected=ExpectedSpec(),
        budgets=BudgetSpec(
            max_total_tokens=budgets.get("maxTotalTokens"),
            max_wall_ms=budgets.get("maxWallMs"),
            max_tool_calls=budgets.get("maxToolCalls"),
            soft_limit_percent=budgets.get("softLimitPercent"),
        ),
        script=[],
        source_path="trace.json",
        metadata=metadata,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", required=True)
    parser.add_argument("--report-dir", required=True)
    args = parser.parse_args()

    report_dir = Path(args.report_dir).resolve()
    previous = json.loads((report_dir / "report.json").read_text(encoding="utf-8"))
    adapter = WorkBuddyBenchAdapter(source_root=Path(args.source_dir).resolve())
    expected_queries = {
        task.id: adapter.case_for_task(task).query
        for task in adapter.tasks("internal")
    }
    cases: list[CaseResult] = []
    for raw in previous["caseResults"]:
        case = _case_from_dict(raw)
        case.query = expected_queries.get(case.id, "")
        trace_path = report_dir / str(raw["tracePath"]).replace("/", "\\")
        trace = RunTrace.from_dict(
            json.loads(trace_path.read_text(encoding="utf-8"))
        )
        cases.append(
            CaseResult(
                case=case,
                trace=trace,
                scores=[
                    _score_from_dict(case.id, score) for score in raw.get("scores") or []
                ],
                trace_path=str(trace_path.relative_to(report_dir)),
                attempt=int(raw.get("attempt") or 1),
            )
        )

    build_report(
        profile=str(previous["profile"]),
        agent=str(previous["agent"]),
        cases=cases,
        output_dir=report_dir,
        environment=dict(previous.get("environment") or {}),
        benchmark=previous.get("benchmark"),
        inspect_log=previous.get("inspectLog"),
    )


if __name__ == "__main__":
    main()
