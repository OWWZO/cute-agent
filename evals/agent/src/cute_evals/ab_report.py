from __future__ import annotations

from collections import Counter
from datetime import datetime
from html import escape
from pathlib import Path
from statistics import median
from typing import Any

from .experiment import CellResult, ExperimentSpec, write_json_atomic

FAILURE_STATUSES = {"infra_error", "adapter_error", "oracle_error", "skipped"}


def _mean(values: list[float | int | None]) -> float | None:
    present = [float(value) for value in values if value is not None]
    return sum(present) / len(present) if present else None


def _median(values: list[float | int | None]) -> float | None:
    present = [float(value) for value in values if value is not None]
    return median(present) if present else None


def _task_summary(
    cells: list[CellResult],
    *,
    repetitions: int,
    model_count: int,
) -> dict[str, Any]:
    denominator = [
        cell for cell in cells if cell.in_accuracy_denominator
    ]
    by_model_rep = {
        (cell.model, cell.repetition): cell for cell in cells
    }
    first_rep = [
        cell for cell in cells if cell.repetition == 1
    ]
    complete = len(by_model_rep) >= repetitions * model_count
    pass_at_1 = bool(
        first_rep
        and all(
            cell.in_accuracy_denominator and cell.hard_gate_passed
            for cell in first_rep
        )
    )
    pass_at_k = bool(
        complete
        and all(
            cell.in_accuracy_denominator and cell.hard_gate_passed
            for cell in by_model_rep.values()
        )
    )
    return {
        "n": len(cells),
        "validN": len(denominator),
        "complete": complete,
        "meanTaskScore": _mean([cell.task_score for cell in denominator]),
        "passAt1": pass_at_1,
        "passAtK": pass_at_k,
        "passCount": sum(1 for cell in denominator if cell.hard_gate_passed),
        "failureCount": sum(
            1 for cell in cells if cell.status in FAILURE_STATUSES
        ),
        "retryCount": sum(len(cell.retry_history) for cell in cells),
        "medianTotalTokens": _median(
            [cell.metrics.get("totalTokens") for cell in denominator]
        ),
        "medianWallMs": _median(
            [cell.metrics.get("wallMs") for cell in denominator]
        ),
        "medianToolCalls": _median(
            [cell.metrics.get("toolCalls") for cell in denominator]
        ),
        "medianToolErrors": _median(
            [cell.metrics.get("toolErrors") for cell in denominator]
        ),
        "medianApiTurns": _median(
            [cell.metrics.get("apiTurns") for cell in denominator]
        ),
        "medianEstimatedCost": _median(
            [cell.metrics.get("estimatedCost") for cell in denominator]
        ),
        "medianFirstTokenMs": _median(
            [cell.metrics.get("firstTokenMs") for cell in denominator]
        ),
        "statusCounts": dict(Counter(cell.status for cell in cells)),
    }


def _arm_summary(
    cells: list[CellResult],
    case_ids: list[str],
    *,
    repetitions: int,
    model_count: int,
) -> dict[str, Any]:
    tasks = {}
    for case_id in case_ids:
        task_cells = [cell for cell in cells if cell.case_id == case_id]
        tasks[case_id] = _task_summary(
            task_cells,
            repetitions=repetitions,
            model_count=model_count,
        )
    task_means = [
        item["meanTaskScore"]
        for item in tasks.values()
        if item["meanTaskScore"] is not None
    ]
    return {
        "taskCount": len(tasks),
        "cellCount": len(cells),
        "meanTaskScore": _mean(task_means),
        "passAt1": (
            sum(1 for item in tasks.values() if item["passAt1"]) / len(tasks)
            if tasks
            else 0.0
        ),
        "passAtK": (
            sum(1 for item in tasks.values() if item["passAtK"]) / len(tasks)
            if tasks
            else 0.0
        ),
        "validCellCount": sum(item["validN"] for item in tasks.values()),
        "tasks": tasks,
    }


def _direction(base: float | None, candidate: float | None) -> str:
    if base is None or candidate is None:
        return "unknown"
    if candidate > base:
        return "win"
    if candidate < base:
        return "loss"
    return "tie"


def _ratio(candidate: float | None, base: float | None) -> float | None:
    if candidate is None or base in (None, 0):
        return None
    return candidate / base


def _recommend(
    spec: ExperimentSpec,
    *,
    cells: list[CellResult],
    expected_cell_count: int,
    base: dict[str, Any],
    candidate: dict[str, Any],
    metric_deltas: dict[str, Any],
    same_denominator: bool,
) -> str:
    non_model_failures = [
        cell for cell in cells if cell.status in FAILURE_STATUSES
    ]
    incomplete = len(cells) < expected_cell_count
    if non_model_failures or incomplete or not same_denominator:
        return "needs-more-runs"
    base_score = base.get("meanTaskScore")
    candidate_score = candidate.get("meanTaskScore")
    if base_score is None or candidate_score is None:
        return "inconclusive"
    if candidate_score < base_score - float(spec.thresholds["maxAccuracyRegression"]):
        return "block"
    token_ratio = metric_deltas.get("totalTokensRatio")
    if (
        token_ratio is not None
        and token_ratio > 1 + float(spec.thresholds["maxTokenRegressionRatio"])
    ):
        return "block"
    if candidate_score > base_score:
        return "ship"
    return "inconclusive"


def build_ab_report(
    spec: ExperimentSpec,
    cells: list[CellResult],
) -> dict[str, Any]:
    case_ids = sorted({cell.case_id for cell in cells})
    base_cells = [cell for cell in cells if cell.arm == "base"]
    candidate_cells = [cell for cell in cells if cell.arm == "candidate"]
    model_count = len(spec.models)
    base = _arm_summary(
        base_cells,
        case_ids,
        repetitions=spec.repetitions,
        model_count=model_count,
    )
    candidate = _arm_summary(
        candidate_cells,
        case_ids,
        repetitions=spec.repetitions,
        model_count=model_count,
    )
    task_rows = []
    anomalies = []
    for case_id in case_ids:
        base_task = base["tasks"].get(case_id) or {}
        candidate_task = candidate["tasks"].get(case_id) or {}
        task_rows.append(
            {
                "caseId": case_id,
                "base": base_task,
                "candidate": candidate_task,
                "direction": _direction(
                    base_task.get("meanTaskScore"),
                    candidate_task.get("meanTaskScore"),
                ),
            }
        )
        if task_rows[-1]["direction"] == "loss":
            anomalies.append(
                {
                    "cellKey": f"task:{case_id}",
                    "caseId": case_id,
                    "arm": "comparison",
                    "repetition": 0,
                    "status": "direction_reversal",
                    "reasons": ["candidate_mean_below_base"],
                    "tracePath": None,
                }
            )
        for cell in cells:
            if cell.case_id != case_id:
                continue
            reasons = []
            if cell.status in FAILURE_STATUSES:
                reasons.append(cell.status)
            elif not cell.hard_gate_passed:
                reasons.append("hard_gate_failed")
            if cell.task_score < 1.0:
                reasons.append("sub_100_score")
            if cell.retry_history:
                reasons.append("retried")
            if cell.metrics.get("estimatedCost") and float(cell.metrics["estimatedCost"]) > float(
                spec.budgets.get("maxUsd") or 0
            ):
                reasons.append("cost_budget")
            if reasons:
                anomalies.append(
                    {
                        "cellKey": cell.cell_key,
                        "caseId": cell.case_id,
                        "arm": cell.arm,
                        "repetition": cell.repetition,
                        "status": cell.status,
                        "reasons": reasons,
                        "tracePath": cell.trace_path,
                    }
                )

    # Aggregate the per-task medians so a multi-model experiment still compares
    # the same set of tasks rather than whichever task happened to finish last.
    base_tokens = _median(
        [item.get("medianTotalTokens") for item in base["tasks"].values()]
    )
    candidate_tokens = _median(
        [item.get("medianTotalTokens") for item in candidate["tasks"].values()]
    )
    base_denominator_keys = {
        (cell.model, cell.case_id, cell.repetition)
        for cell in base_cells
        if cell.in_accuracy_denominator
    }
    candidate_denominator_keys = {
        (cell.model, cell.case_id, cell.repetition)
        for cell in candidate_cells
        if cell.in_accuracy_denominator
    }
    same_denominator = base_denominator_keys == candidate_denominator_keys
    if not same_denominator:
        anomalies.append(
            {
                "cellKey": "denominator:mismatch",
                "caseId": None,
                "arm": "comparison",
                "repetition": 0,
                "status": "denominator_mismatch",
                "reasons": ["baseline_candidate_denominators_differ"],
                "tracePath": None,
            }
        )
    metric_deltas = {
        "meanTaskScore": (
            None
            if base.get("meanTaskScore") is None or candidate.get("meanTaskScore") is None
            else round(candidate["meanTaskScore"] - base["meanTaskScore"], 6)
        ),
        "passAt1": round(candidate["passAt1"] - base["passAt1"], 6),
        "passAtK": round(candidate["passAtK"] - base["passAtK"], 6),
        "totalTokensRatio": _ratio(candidate_tokens, base_tokens),
        "totalTokensDelta": (
            None
            if base_tokens is None or candidate_tokens is None
            else round(candidate_tokens - base_tokens, 3)
        ),
    }
    expected_cell_count = (
        len(case_ids) * spec.repetitions * len(spec.models) * 2
    )
    recommendation = _recommend(
        spec,
        cells=cells,
        expected_cell_count=expected_cell_count,
        base=base,
        candidate=candidate,
        metric_deltas=metric_deltas,
        same_denominator=same_denominator,
    )
    status_counts = Counter(cell.status for cell in cells)
    return {
        "schemaVersion": "1.0",
        "experimentId": spec.experiment_id,
        "experimentSpecHash": spec.spec_hash,
        "generatedAt": datetime.now().isoformat(timespec="milliseconds"),
        "experiment": {
            "suite": spec.suite,
            "profile": spec.profile,
            "javaMode": spec.java_mode,
            "models": spec.models,
            "repetitions": spec.repetitions,
            "expectedCellCount": expected_cell_count,
            "actualCellCount": len(cells),
        },
        "summary": {
            "taskCount": len(case_ids),
            "cellCount": len(cells),
            "actualCellCount": len(cells),
            "expectedCellCount": expected_cell_count,
            "complete": len(cells) == expected_cell_count,
            "sameDenominator": same_denominator,
            "meanOfTaskMeans": {
                "base": base["meanTaskScore"],
                "candidate": candidate["meanTaskScore"],
            },
            "recommendation": recommendation,
        },
        "arms": {"base": base, "candidate": candidate},
        "tasks": task_rows,
        "statusCounts": dict(sorted(status_counts.items())),
        "metricDeltas": metric_deltas,
        "anomalies": anomalies,
        "recommendation": recommendation,
        "cells": [cell.to_dict() for cell in cells],
    }


def write_ab_report(output_dir: Path, report: dict[str, Any]) -> None:
    write_json_atomic(output_dir / "report.json", report)
    (output_dir / "report.html").write_text(
        _render_html(report),
        encoding="utf-8",
    )
    (output_dir / "audit.md").write_text(
        _render_audit(report),
        encoding="utf-8",
    )


def _render_audit(report: dict[str, Any]) -> str:
    lines = [
        f"# A/B Audit: {report['experimentId']}",
        "",
        f"- Recommendation: `{report['recommendation']}`",
        (
            f"- Cells: {report['summary']['actualCellCount']}/"
            f"{report['summary']['expectedCellCount']}"
        ),
        "",
        "## Anomalies",
        "",
    ]
    if not report["anomalies"]:
        lines.append("- none")
    else:
        lines.append("| Cell | Arm | Status | Reasons | Trace |")
        lines.append("|---|---|---|---|---|")
        for item in report["anomalies"]:
            lines.append(
                "| {cell} | {arm} | {status} | {reasons} | {trace} |".format(
                    cell=item["cellKey"],
                    arm=item["arm"],
                    status=item["status"],
                    reasons=", ".join(item["reasons"]),
                    trace=item.get("tracePath") or "-",
                )
            )
    lines.extend(
        [
            "",
            "## Review Checklist",
            "",
            "- Confirm infrastructure failures were not counted as model failures.",
            "- Confirm both arms use the same case set and repetition count.",
            "- Inspect every direction reversal and every sub-100% cell.",
            "- Make the final ship/block decision manually.",
            "",
        ]
    )
    return "\n".join(lines)


def _render_html(report: dict[str, Any]) -> str:
    rows = []
    for item in report["tasks"]:
        base = item["base"]
        candidate = item["candidate"]
        rows.append(
            "<tr>"
            f"<td>{escape(str(item['caseId']))}</td>"
            f"<td>{escape(str(base.get('meanTaskScore')))}</td>"
            f"<td>{escape(str(candidate.get('meanTaskScore')))}</td>"
            f"<td>{escape(item['direction'])}</td>"
            f"<td>{escape(str(base.get('medianTotalTokens')))}</td>"
            f"<td>{escape(str(candidate.get('medianTotalTokens')))}</td>"
            "</tr>"
        )
    anomalies = "".join(
        f"<li><code>{escape(item['cellKey'])}</code> "
        f"{escape(item['arm'])}: {escape(', '.join(item['reasons']))}</li>"
        for item in report["anomalies"]
    ) or "<li>none</li>"
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8"/>
  <title>A/B Report - {escape(report['experimentId'])}</title>
  <style>
    body {{ font-family: "Segoe UI", sans-serif; margin: 32px; color: #17202a; background: #fbfcfd; }}
    table {{ border-collapse: collapse; width: 100%; background: white; }}
    th, td {{ border: 1px solid #d5d8dc; padding: 8px; text-align: left; }}
    th {{ background: #f4f6f7; }}
    code {{ font-size: 12px; }}
    .recommendation {{ font-weight: 700; }}
  </style>
</head>
<body>
  <h1>A/B Report: {escape(report['experimentId'])}</h1>
  <p>Suite: <code>{escape(report['experiment']['suite'])}</code> ·
     Mode: <code>{escape(report['experiment']['javaMode'])}</code> ·
     Cells: {report['summary']['actualCellCount']}/{report['summary']['expectedCellCount']}</p>
  <p class="recommendation">Recommendation: {escape(report['recommendation'])}</p>
  <p>Base mean score: {escape(str(report['arms']['base']['meanTaskScore']))} ·
     Candidate mean score: {escape(str(report['arms']['candidate']['meanTaskScore']))} ·
     Token ratio: {escape(str(report['metricDeltas']['totalTokensRatio']))}</p>
  <h2>Task Results</h2>
  <table>
    <thead><tr><th>Case</th><th>Base score</th><th>Candidate score</th><th>Direction</th><th>Base tokens</th><th>Candidate tokens</th></tr></thead>
    <tbody>{''.join(rows)}</tbody>
  </table>
  <h2>Anomalies</h2>
  <ul>{anomalies}</ul>
</body>
</html>
"""
