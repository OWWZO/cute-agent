from __future__ import annotations

import hashlib
import json
import re
from dataclasses import asdict
from datetime import datetime
from html import escape
from pathlib import Path
from typing import Any

from .models import EvalCase


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _normalized_query(case: EvalCase) -> str:
    return re.sub(r"[\W_]+", "", case.query.casefold())


def _case_fingerprint(case: EvalCase) -> str:
    payload = {
        "query": _normalized_query(case),
        "expected": asdict(case.expected),
        "script": case.script,
        "tools": case.tools,
        "answerContract": case.answer_contract,
    }
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def audit_case_separation(
    validation_cases: list[EvalCase],
    holdout_cases: list[EvalCase],
) -> dict[str, Any]:
    validation_by_query: dict[str, list[str]] = {}
    holdout_by_query: dict[str, list[str]] = {}
    validation_by_fingerprint: dict[str, list[str]] = {}
    holdout_by_fingerprint: dict[str, list[str]] = {}

    for case in validation_cases:
        validation_by_query.setdefault(_normalized_query(case), []).append(case.id)
        validation_by_fingerprint.setdefault(_case_fingerprint(case), []).append(case.id)
    for case in holdout_cases:
        holdout_by_query.setdefault(_normalized_query(case), []).append(case.id)
        holdout_by_fingerprint.setdefault(_case_fingerprint(case), []).append(case.id)

    shared_ids = sorted(
        {case.id for case in validation_cases}
        & {case.id for case in holdout_cases}
    )
    shared_queries = sorted(
        set(validation_by_query) & set(holdout_by_query)
    )
    shared_fingerprints = sorted(
        set(validation_by_fingerprint) & set(holdout_by_fingerprint)
    )
    return {
        "sharedIds": shared_ids,
        "sharedQueryPairs": [
            {
                "validation": validation_by_query[key],
                "holdout": holdout_by_query[key],
            }
            for key in shared_queries
        ],
        "clonedCasePairs": [
            {
                "validation": validation_by_fingerprint[key],
                "holdout": holdout_by_fingerprint[key],
            }
            for key in shared_fingerprints
        ],
        "leak": bool(shared_ids or shared_queries or shared_fingerprints),
    }


def _suite_snapshot(report: dict[str, Any]) -> dict[str, Any]:
    summary = report.get("summary") or {}
    return {
        "suite": (report.get("environment") or {}).get("suite"),
        "caseCount": int(summary.get("caseCount") or 0),
        "passAt1": float(summary.get("passAt1") or 0.0),
        "passAtK": float(summary.get("passAtK") or 0.0),
        "confidenceIntervals": summary.get("confidenceIntervals"),
        "medianTotalTokens": _median_case_metric(report, "medianTotalTokens"),
        "medianDurationMs": _median_case_metric(report, "medianDurationMs"),
    }


def _median_case_metric(report: dict[str, Any], name: str) -> float | None:
    values = [
        float(item[name])
        for item in (report.get("caseSummaries") or [])
        if item.get(name) is not None
    ]
    if not values:
        return None
    values.sort()
    middle = len(values) // 2
    if len(values) % 2:
        return values[middle]
    return (values[middle - 1] + values[middle]) / 2


def _ratio(numerator: float | None, denominator: float | None) -> float | None:
    if numerator is None or denominator in {None, 0.0}:
        return None
    return round(numerator / denominator, 4)


def _coverage(validation_cases: list[EvalCase], holdout_cases: list[EvalCase]) -> dict[str, Any]:
    validation_types = {
        str(case.metadata.get("taskType"))
        for case in validation_cases
        if case.metadata.get("taskType")
    }
    holdout_types = {
        str(case.metadata.get("taskType"))
        for case in holdout_cases
        if case.metadata.get("taskType")
    }
    return {
        "validationTaskTypes": sorted(validation_types),
        "holdoutTaskTypes": sorted(holdout_types),
        "missingFromHoldout": sorted(validation_types - holdout_types),
        "holdoutOnly": sorted(holdout_types - validation_types),
    }


def build_overfit_report(
    *,
    validation_report_path: Path,
    holdout_report_path: Path,
    validation_cases: list[EvalCase],
    holdout_cases: list[EvalCase],
    output_dir: Path,
) -> dict[str, Any]:
    validation = _suite_snapshot(_read_json(validation_report_path))
    holdout = _suite_snapshot(_read_json(holdout_report_path))
    leakage = audit_case_separation(validation_cases, holdout_cases)

    pass_at_1_gap = round(validation["passAt1"] - holdout["passAt1"], 4)
    pass_at_k_gap = round(validation["passAtK"] - holdout["passAtK"], 4)
    if leakage["leak"]:
        verdict = "leakage"
    elif pass_at_k_gap >= 0.20 or pass_at_1_gap >= 0.20:
        verdict = "overfit-risk"
    elif pass_at_k_gap >= 0.10 or pass_at_1_gap >= 0.10:
        verdict = "watch"
    else:
        verdict = "no-signal"

    sample_limited = min(validation["caseCount"], holdout["caseCount"]) < 30
    payload = {
        "schemaVersion": "1.0",
        "generatedAt": datetime.now().isoformat(timespec="milliseconds"),
        "validation": validation,
        "holdout": holdout,
        "gaps": {
            "passAt1": pass_at_1_gap,
            "passAtK": pass_at_k_gap,
            "tokensRatio": _ratio(
                holdout["medianTotalTokens"],
                validation["medianTotalTokens"],
            ),
            "durationRatio": _ratio(
                holdout["medianDurationMs"],
                validation["medianDurationMs"],
            ),
        },
        "leakage": leakage,
        "coverage": _coverage(validation_cases, holdout_cases),
        "verdict": verdict,
        "sampleLimited": sample_limited,
        "interpretation": {
            "directional": sample_limited,
            "note": (
                "至少一侧少于 30 题，结论只能作为方向性信号。"
                if sample_limited
                else "样本量达到比较阈值，仍需结合置信区间判断。"
            ),
        },
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "overfit-report.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "overfit-report.html").write_text(
        _render_html(payload),
        encoding="utf-8",
    )
    return payload


def overfit_blocks(report: dict[str, Any]) -> bool:
    return report.get("verdict") in {"leakage", "overfit-risk"}


def _render_html(payload: dict[str, Any]) -> str:
    validation = payload["validation"]
    holdout = payload["holdout"]
    gaps = payload["gaps"]
    leakage = payload["leakage"]
    coverage = payload["coverage"]
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8"/>
  <title>Cute-Agent Generalization Monitor</title>
  <style>
    body {{ font-family: "Segoe UI", sans-serif; margin: 32px; color: #17202a; background: #fbfcfd; }}
    table {{ border-collapse: collapse; width: 100%; background: white; margin: 16px 0 28px; }}
    th, td {{ border: 1px solid #d5d8dc; padding: 8px; text-align: left; }}
    th {{ background: #f4f6f7; }}
    .verdict {{ font-size: 20px; font-weight: 700; }}
    .risk {{ color: #b03a2e; }}
    .watch {{ color: #b9770e; }}
    .ok {{ color: #1e8449; }}
  </style>
</head>
<body>
  <h1>Cute-Agent Generalization Monitor</h1>
  <p class="verdict {escape(_verdict_class(payload['verdict']))}">
    verdict: {escape(payload["verdict"])}
  </p>
  <p>{escape(payload["interpretation"]["note"])}</p>
  <table>
    <thead><tr><th>Suite</th><th>Cases</th><th>pass@1</th><th>pass^k</th>
    <th>Median tokens</th><th>Median duration ms</th></tr></thead>
    <tbody>
      <tr><td>validation</td><td>{validation['caseCount']}</td>
      <td>{validation['passAt1']:.2%}</td><td>{validation['passAtK']:.2%}</td>
      <td>{escape(str(validation['medianTotalTokens']))}</td>
      <td>{escape(str(validation['medianDurationMs']))}</td></tr>
      <tr><td>holdout</td><td>{holdout['caseCount']}</td>
      <td>{holdout['passAt1']:.2%}</td><td>{holdout['passAtK']:.2%}</td>
      <td>{escape(str(holdout['medianTotalTokens']))}</td>
      <td>{escape(str(holdout['medianDurationMs']))}</td></tr>
    </tbody>
  </table>
  <p><strong>pass@1 gap:</strong> {gaps['passAt1']:.2%}
     · <strong>pass^k gap:</strong> {gaps['passAtK']:.2%}
     · <strong>token ratio:</strong> {escape(str(gaps['tokensRatio']))}
     · <strong>duration ratio:</strong> {escape(str(gaps['durationRatio']))}</p>
  <p><strong>Leakage:</strong> shared ids={len(leakage['sharedIds'])},
     shared queries={len(leakage['sharedQueryPairs'])},
     cloned cases={len(leakage['clonedCasePairs'])}</p>
  <p><strong>Coverage:</strong> missing from holdout=
     {escape(", ".join(coverage['missingFromHoldout']) or "none")};
     holdout only={escape(", ".join(coverage['holdoutOnly']) or "none")}</p>
</body>
</html>
"""


def _verdict_class(verdict: str) -> str:
    if verdict in {"leakage", "overfit-risk"}:
        return "risk"
    if verdict == "watch":
        return "watch"
    return "ok"
