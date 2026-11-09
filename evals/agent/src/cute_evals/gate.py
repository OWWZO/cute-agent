from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any


def _load(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise TypeError(f"report must be a JSON object: {path}")
    return payload


def _eval_status(report: dict[str, Any], min_pass_at_k: float) -> dict[str, Any]:
    summary = report.get("summary") or {}
    failed = int(summary.get("failed") or 0)
    unstable = int(summary.get("unstable") or 0)
    unverified = int(summary.get("unverified") or 0)
    pass_at_k = summary.get("passAtK")
    pass_at_k_ok = (
        pass_at_k is not None and float(pass_at_k) >= min_pass_at_k
    )
    reasons = []
    if failed:
        reasons.append(f"failed={failed}")
    if unstable:
        reasons.append(f"unstable={unstable}")
    if unverified:
        reasons.append(f"unverified={unverified}")
    if not pass_at_k_ok:
        reasons.append(f"passAtK={pass_at_k}")
    return {
        "kind": "eval",
        "ok": not reasons,
        "reasons": reasons,
        "summary": summary,
    }


def _ab_status(
    report: dict[str, Any],
    *,
    require_ship_ab: bool,
) -> dict[str, Any]:
    recommendation = str(report.get("recommendation") or "inconclusive")
    status_counts = report.get("statusCounts") or {}
    infra_failures = sum(
        int(status_counts.get(name) or 0)
        for name in ("infra_error", "adapter_error", "oracle_error", "skipped")
    )
    reasons = []
    if infra_failures:
        reasons.append(f"infraFailures={infra_failures}")
    if recommendation in {"block", "needs-more-runs"}:
        reasons.append(f"recommendation={recommendation}")
    elif require_ship_ab and recommendation != "ship":
        reasons.append(f"recommendation={recommendation}; requireShipAB=true")
    return {
        "kind": "ab",
        "ok": not reasons,
        "reasons": reasons,
        "recommendation": recommendation,
        "statusCounts": status_counts,
    }


def evaluate_gate(
    report_paths: list[Path],
    output_dir: Path,
    *,
    min_pass_at_k: float = 0.0,
    require_ship_ab: bool = False,
) -> dict[str, Any]:
    entries = []
    for path in report_paths:
        resolved = path.resolve()
        if not resolved.exists():
            entries.append(
                {
                    "report": str(resolved),
                    "kind": "missing",
                    "ok": False,
                    "reasons": ["report missing"],
                }
            )
            continue
        report = _load(resolved)
        is_ab = "recommendation" in report and "cells" in report
        result = (
            _ab_status(report, require_ship_ab=require_ship_ab)
            if is_ab
            else _eval_status(report, min_pass_at_k)
        )
        result["report"] = str(resolved)
        entries.append(result)

    passed = all(item["ok"] for item in entries)
    payload = {
        "schemaVersion": "1.0",
        "generatedAt": datetime.now().isoformat(timespec="milliseconds"),
        "passed": passed,
        "minPassAtK": min_pass_at_k,
        "requireShipAB": require_ship_ab,
        "reports": entries,
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "gate-report.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    lines = [
        "# Agent Evaluation Gate",
        "",
        f"- Result: {'PASS' if passed else 'BLOCK'}",
        f"- Min pass@k: {min_pass_at_k}",
        f"- Require ship A/B: {require_ship_ab}",
        "",
        "| Report | Kind | Result | Reasons |",
        "|---|---|---|---|",
    ]
    for item in entries:
        lines.append(
            "| {report} | {kind} | {result} | {reasons} |".format(
                report=item["report"],
                kind=item["kind"],
                result="PASS" if item["ok"] else "BLOCK",
                reasons=", ".join(item["reasons"]) or "-",
            )
        )
    (output_dir / "gate-report.md").write_text(
        "\n".join(lines) + "\n",
        encoding="utf-8",
    )
    return payload
