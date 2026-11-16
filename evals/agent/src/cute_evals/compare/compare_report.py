from __future__ import annotations

import json
from datetime import datetime
from html import escape
from pathlib import Path
from statistics import median
from typing import Any

import jsonschema

from .dataset import CompareDataset


def _median(values: list[float | int | None]) -> float | None:
    present = [float(value) for value in values if value is not None]
    return median(present) if present else None


def _tool_count(results: list[dict[str, Any]]) -> float | None:
    values = [
        int(result["toolCallCount"])
        for result in results
        if result.get("toolCallCount") is not None
    ]
    return float(median(values)) if values else None


def _agent_case_entry(
    report: dict[str, Any],
    results: list[dict[str, Any]],
    case_id: str,
) -> dict[str, Any] | None:
    summary = next(
        (
            item
            for item in report.get("caseSummaries") or []
            if item.get("caseId") == case_id
        ),
        None,
    )
    if summary is None:
        return None
    case_results = [
        result
        for result in results
        if result.get("caseId") == case_id
    ]
    return {
        "status": summary.get("status"),
        "passed": summary.get("passAt1") is True,
        "passAt1": summary.get("passAt1"),
        "passAtK": summary.get("passAtK"),
        "qualityPassAt1": summary.get("qualityPassAt1"),
        "qualityPassAtK": summary.get("qualityPassAtK"),
        "attemptCount": summary.get("attemptCount"),
        "medianTotalTokens": summary.get("medianTotalTokens"),
        "medianDurationMs": summary.get("medianDurationMs"),
        "medianFirstTokenMs": summary.get("medianFirstTokenMs"),
        "medianToolCalls": _tool_count(case_results),
        "failureCategories": summary.get("failureCategories") or {},
        "tracePaths": summary.get("tracePaths") or [],
    }


def _winner(
    entries: dict[str, dict[str, Any] | None],
    *,
    quality_only: bool,
) -> tuple[str, str]:
    present = {name: entry for name, entry in entries.items() if entry is not None}
    if not present:
        return "incomplete", "No agent result was recorded."
    if len(present) != len(entries):
        return "incomplete", "At least one agent result is missing."
    if any(entry["status"] == "unverified" for entry in present.values()):
        return "incomplete", "At least one agent result is unverified."
    pass_key = "qualityPassAtK" if quality_only else "passAtK"
    passed = [
        name for name, entry in present.items() if entry[pass_key] is True
    ]
    if len(passed) == len(present):
        if len(present) == 1:
            return passed[0], f"Only {passed[0]} was available and it passed."
        return "tie", "All available agents passed the case."
    if len(passed) == 1:
        return passed[0], f"Only {passed[0]} passed the case."
    return "tie", "No available agent passed the case."


def build_comparison_report(
    *,
    dataset: CompareDataset,
    mode: str,
    repeat: int,
    agent_reports: dict[str, dict[str, Any]],
    agent_results: dict[str, list[dict[str, Any]]],
    agent_metadata: dict[str, dict[str, Any]],
    environment: dict[str, Any],
    quality_only: bool = False,
) -> dict[str, Any]:
    case_entries = []
    result_wins: dict[str, int] = {}
    efficiency_wins: dict[str, int] = {}
    for case in dataset.cases:
        entries = {
            agent: _agent_case_entry(
                report,
                agent_results.get(agent) or [],
                case.id,
            )
            for agent, report in agent_reports.items()
        }
        winner, reason = _winner(entries, quality_only=quality_only)
        result_wins[winner] = result_wins.get(winner, 0) + 1
        durations = {
            agent: entry["medianDurationMs"]
            for agent, entry in entries.items()
            if entry and entry["medianDurationMs"] is not None
        }
        efficiency_winner = (
            min(durations, key=lambda name: float(durations[name]))
            if durations
            else "incomplete"
        )
        efficiency_wins[efficiency_winner] = (
            efficiency_wins.get(efficiency_winner, 0) + 1
        )
        case_entries.append(
            {
                "caseId": case.id,
                "taskType": case.metadata.get("taskType"),
                "risk": case.metadata.get("risk"),
                "agents": entries,
                "resultWinner": winner,
                "efficiencyWinner": efficiency_winner,
                "reason": reason,
            }
        )

    agents = {}
    for agent, report in agent_reports.items():
        agents[agent] = {
            "metadata": agent_metadata.get(agent) or {},
            "reportPath": f"agents/{agent}/report.json",
            "reportHtmlPath": f"agents/{agent}/report.html",
            "summary": report.get("summary") or {},
        }

    payload = {
        "schemaVersion": "1.0",
        "generatedAt": datetime.now().astimezone().isoformat(timespec="milliseconds"),
        "suite": dataset.suite,
        "datasetHash": dataset.dataset_hash,
        "mode": mode,
        "repeat": repeat,
        "environment": environment,
        "agents": agents,
        "summary": {
            "caseCount": len(dataset.cases),
            "resultWins": dict(sorted(result_wins.items())),
            "efficiencyWins": dict(sorted(efficiency_wins.items())),
            "sampleAdequacy": (
                "pilot-sufficient"
                if len(dataset.cases) >= 12 and repeat >= 3
                else "provisional"
            ),
            "interpretation": (
                "Result wins use deterministic task and workspace checks"
                + (
                    " plus official quality only; budget SLO is diagnostic."
                    if quality_only
                    else " plus the product budget SLO."
                )
                + " "
                "Efficiency wins only compare median wall time and do not override failures."
            ),
        },
        "cases": case_entries,
    }
    return payload


def write_comparison_report(output_dir: Path, payload: dict[str, Any]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    schema_path = (
        Path(__file__).resolve().parents[3]
        / "schemas"
        / "compare-report.schema.json"
    )
    if not schema_path.exists():
        schema_path = (
            Path(__file__).resolve().parents[1]
            / "schemas"
            / "compare-report.schema.json"
        )
    jsonschema.validate(
        payload,
        json.loads(schema_path.read_text(encoding="utf-8")),
    )
    (output_dir / "report.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    rows = []
    for case in payload["cases"]:
        agent_cells = []
        for agent, entry in case["agents"].items():
            if entry is None:
                agent_cells.append(f"<td>{escape(agent)}: missing</td>")
                continue
            agent_cells.append(
                "<td>"
                f"{escape(agent)}: {escape(str(entry['status']))}<br/>"
                f"tokens={escape(str(entry['medianTotalTokens']))}<br/>"
                f"wall_ms={escape(str(entry['medianDurationMs']))}<br/>"
                f"tools={escape(str(entry['medianToolCalls']))}"
                "</td>"
            )
        rows.append(
            "<tr>"
            f"<td>{escape(case['caseId'])}</td>"
            f"{''.join(agent_cells)}"
            f"<td>{escape(case['resultWinner'])}</td>"
            f"<td>{escape(case['efficiencyWinner'])}</td>"
            f"<td>{escape(case['reason'])}</td>"
            "</tr>"
        )
    summary = payload["summary"]
    html = f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8"/>
  <title>Cute Agent / Codex Comparison</title>
  <style>
    body {{ font-family: "Segoe UI", sans-serif; margin: 32px; color: #17202a; background: #fbfcfd; }}
    table {{ border-collapse: collapse; width: 100%; background: white; }}
    th, td {{ border: 1px solid #d5d8dc; padding: 8px; text-align: left; vertical-align: top; }}
    th {{ background: #f4f6f7; }}
    code {{ font-size: 12px; }}
  </style>
</head>
<body>
  <h1>Cute Agent / Codex Comparison</h1>
  <p>Suite <code>{escape(payload["suite"])}</code>, mode <code>{escape(payload["mode"])}</code>,
  repeat {payload["repeat"]}, dataset <code>{escape(payload["datasetHash"])}</code>.</p>
  <p>Result wins: {escape(str(summary["resultWins"]))}. Efficiency wins:
  {escape(str(summary["efficiencyWins"]))}. Sample adequacy:
  <strong>{escape(summary["sampleAdequacy"])}</strong>.</p>
  <p>Sandbox backend: <code>{escape(str(payload.get("environment", {}).get("sandboxBackend", "unknown")))}</code>.
  Workspace mode: <code>{escape(str(payload.get("environment", {}).get("workspaceRootMode", "unknown")))}</code>.
  Deletion sync: <code>{escape(str(payload.get("environment", {}).get("deletionSyncEnabled", False)))}</code>.</p>
  <p>{escape(summary["interpretation"])}</p>
  <table>
    <thead><tr><th>Case</th><th>Agents</th><th>Result winner</th>
    <th>Efficiency winner</th><th>Reason</th></tr></thead>
    <tbody>{''.join(rows)}</tbody>
  </table>
</body>
</html>
"""
    (output_dir / "report.html").write_text(html, encoding="utf-8")
