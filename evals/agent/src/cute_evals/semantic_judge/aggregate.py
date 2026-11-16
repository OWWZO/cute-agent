from __future__ import annotations

import html
import json
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any

import jsonschema

from .contract import JudgeVerdict


def build_semantic_report(
    verdicts: list[JudgeVerdict],
    *,
    mode: str,
    scopes: list[str],
    report_paths: list[Path],
) -> dict[str, Any]:
    status_counts = Counter(item.status for item in verdicts)
    verdict_counts = Counter(item.verdict for item in verdicts)
    by_agent: dict[str, list[JudgeVerdict]] = {}
    for verdict in verdicts:
        by_agent.setdefault(verdict.agent, []).append(verdict)
    agent_summary = {}
    for agent, items in sorted(by_agent.items()):
        scores = [
            item.weighted_score
            for item in items
            if item.weighted_score is not None
        ]
        agent_summary[agent] = {
            "runs": len(items),
            "verified": sum(item.status == "verified" for item in items),
            "unverified": sum(item.status == "unverified" for item in items),
            "passed": sum(item.verdict == "pass" for item in items),
            "failed": sum(item.verdict == "fail" for item in items),
            "meanWeightedScore": (
                sum(scores) / len(scores) if scores else None
            ),
        }
    eligible = len(verdicts)
    verified = status_counts.get("verified", 0)
    return {
        "schemaVersion": "1.0",
        "generatedAt": datetime.now().astimezone().isoformat(
            timespec="milliseconds"
        ),
        "mode": mode,
        "officialScore": False,
        "gateEligible": False,
        "scopes": scopes,
        "sourceReports": [str(path) for path in report_paths],
        "summary": {
            "eligibleRuns": eligible,
            "verifiedRuns": verified,
            "unverifiedRuns": status_counts.get("unverified", 0),
            "disputedRuns": status_counts.get("disputed", 0),
            "judgeCoverage": verified / eligible if eligible else 0.0,
            "verdicts": dict(sorted(verdict_counts.items())),
            "agents": agent_summary,
        },
        "verdicts": [item.to_dict() for item in verdicts],
    }


def write_semantic_report(output_dir: Path, payload: dict[str, Any]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    schema_candidates = (
        Path(__file__).resolve().parents[3]
        / "schemas"
        / "semantic-judge-report.schema.json",
        Path(__file__).resolve().parents[1]
        / "schemas"
        / "semantic-judge-report.schema.json",
    )
    schema_path = next(
        (path for path in schema_candidates if path.exists()),
        None,
    )
    if schema_path is None:
        raise FileNotFoundError("semantic-judge-report.schema.json was not found")
    jsonschema.validate(
        payload,
        json.loads(schema_path.read_text(encoding="utf-8")),
    )
    (output_dir / "report.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    rows = []
    for item in payload["verdicts"]:
        rows.append(
            "<tr>"
            f"<td>{html.escape(item['caseId'])}</td>"
            f"<td>{html.escape(item['agent'])}</td>"
            f"<td>{item['attempt']}</td>"
            f"<td>{html.escape(item['rubricId'])}</td>"
            f"<td>{html.escape(item['status'])}</td>"
            f"<td>{html.escape(item['verdict'])}</td>"
            f"<td>{html.escape(str(item['weightedScore']))}</td>"
            f"<td>{html.escape(item['summary'])}</td>"
            "</tr>"
        )
    summary = payload["summary"]
    document = f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8"/>
  <title>Semantic Judge Advisory Report</title>
  <style>
    body {{ font-family: "Segoe UI", sans-serif; margin: 32px; color: #17202a; background: #fbfcfd; }}
    table {{ border-collapse: collapse; width: 100%; background: white; }}
    th, td {{ border: 1px solid #d5d8dc; padding: 8px; text-align: left; vertical-align: top; }}
    th {{ background: #f4f6f7; }}
  </style>
</head>
<body>
  <h1>Semantic Judge Advisory Report</h1>
  <p><strong>This report is advisory only.</strong> It does not change official scores,
  pass@k, SLOs, or deterministic gates.</p>
  <p>Mode: <code>{html.escape(payload["mode"])}</code>. Eligible:
  {summary["eligibleRuns"]}. Verified: {summary["verifiedRuns"]}. Unverified:
  {summary["unverifiedRuns"]}. Coverage: {summary["judgeCoverage"]:.2%}.</p>
  <table>
    <thead><tr><th>Case</th><th>Agent</th><th>Attempt</th><th>Rubric</th>
    <th>Status</th><th>Verdict</th><th>Score</th><th>Summary</th></tr></thead>
    <tbody>{''.join(rows)}</tbody>
  </table>
</body>
</html>
"""
    (output_dir / "report.html").write_text(document, encoding="utf-8")
