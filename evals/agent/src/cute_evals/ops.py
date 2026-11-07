from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from datetime import datetime
from html import escape
from pathlib import Path
from statistics import median
from typing import Any

OPS_SCHEMA_VERSION = "1.0"


def _now() -> str:
    return datetime.now().isoformat(timespec="milliseconds")


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _json_hash(payload: Any) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return _sha256_bytes(encoded)


def _median(values: list[float | int | None]) -> float | None:
    present = [float(value) for value in values if value is not None]
    return median(present) if present else None


def _load_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise TypeError(f"JSON document must be an object: {path}")
    return payload


def _git_metadata(root: Path) -> dict[str, Any]:
    executable = shutil.which("git.exe") or shutil.which("git")
    if executable is None or not (root / ".git").exists():
        return {}

    def run(*args: str) -> str:
        completed = subprocess.run(
            [executable, *args],
            cwd=root,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )
        return completed.stdout.strip() if completed.returncode == 0 else ""

    commit = run("rev-parse", "HEAD")
    if not commit:
        return {}
    return {
        "commit": commit,
        "dirty": bool(run("status", "--porcelain")),
    }


def _report_identity(report: dict[str, Any]) -> tuple[str, str]:
    benchmark = report.get("benchmark") or {}
    environment = report.get("environment") or {}
    suite = str(
        benchmark.get("name")
        or environment.get("suite")
        or report.get("profile")
        or "unknown"
    )
    return suite, str(environment.get("javaMode") or "default")


def _case_summaries(report: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {
        str(item["caseId"]): {
            "status": str(item.get("status") or "unknown"),
            "passAt1": bool(item.get("passAt1")),
            "passAtK": bool(item.get("passAtK")),
            "medianTotalTokens": item.get("medianTotalTokens"),
            "medianDurationMs": item.get("medianDurationMs"),
            "medianFirstTokenMs": item.get("medianFirstTokenMs"),
        }
        for item in report.get("caseSummaries") or []
    }


def _normalized_record(
    report: dict[str, Any],
    *,
    report_path: Path,
    series: str,
    variant: str,
    release: dict[str, Any] | None,
    notes: str | None,
    repo_root: Path | None,
) -> dict[str, Any]:
    report_sha = _sha256_bytes(report_path.read_bytes())
    summary = report.get("summary") or {}
    cases = _case_summaries(report)
    environment = report.get("environment") or {}
    benchmark = report.get("benchmark") or {}
    record_key = _json_hash(
        {
            "series": series,
            "variant": variant,
            "runId": report.get("runId"),
            "generatedAt": report.get("generatedAt"),
            "reportSha256": report_sha,
        }
    )
    return {
        "schemaVersion": OPS_SCHEMA_VERSION,
        "recordKey": record_key,
        "recordedAt": _now(),
        "sourceReport": str(report_path),
        "sourceReportSha256": report_sha,
        "runId": str(report.get("runId") or ""),
        "generatedAt": str(report.get("generatedAt") or ""),
        "series": series,
        "variant": variant,
        "profile": str(report.get("profile") or ""),
        "agent": str(report.get("agent") or ""),
        "suite": str(
            benchmark.get("name")
            or environment.get("suite")
            or report.get("profile")
            or series
        ),
        "benchmark": {
            key: benchmark.get(key)
            for key in ("name", "version", "scoreLabel", "split", "datasetHash")
            if benchmark.get(key) is not None
        },
        "datasetHash": environment.get("datasetHash"),
        "environment": {
            key: environment.get(key)
            for key in (
                "engine",
                "javaMode",
                "repeat",
                "suite",
                "datasetHash",
                "judgeModel",
                "judgeBaseUrl",
                "scoreLabel",
                "upstreamVersion",
            )
            if environment.get(key) is not None
        },
        "git": _git_metadata(repo_root) if repo_root is not None else {},
        "release": release,
        "notes": notes,
        "summary": {
            key: summary.get(key)
            for key in (
                "caseCount",
                "attemptCount",
                "passed",
                "failed",
                "unstable",
                "unverified",
                "passAt1",
                "passAtK",
                "failureCategories",
                "toolFailureClasses",
            )
        }
        | {
            "medianTotalTokens": _median(
                [item["medianTotalTokens"] for item in cases.values()]
            ),
            "medianDurationMs": _median(
                [item["medianDurationMs"] for item in cases.values()]
            ),
            "medianFirstTokenMs": _median(
                [item["medianFirstTokenMs"] for item in cases.values()]
            ),
        },
        "cases": cases,
    }


def load_history(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    records = []
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(
                f"invalid ops history JSONL at {path}:{line_number}: {exc}"
            ) from exc
        if not isinstance(record, dict):
            raise TypeError(
                f"ops history record must be an object: {path}:{line_number}"
            )
        if record.get("schemaVersion") != OPS_SCHEMA_VERSION:
            raise ValueError(
                f"unsupported ops history schema at {path}:{line_number}"
            )
        records.append(record)
    return records


def record_report(
    report_path: Path,
    history_path: Path,
    *,
    series: str | None = None,
    variant: str | None = None,
    release: dict[str, Any] | None = None,
    notes: str | None = None,
    repo_root: Path | None = None,
) -> tuple[dict[str, Any], bool]:
    report_path = report_path.resolve()
    report = _load_json(report_path)
    default_series, default_variant = _report_identity(report)
    record = _normalized_record(
        report,
        report_path=report_path,
        series=series or default_series,
        variant=variant or default_variant,
        release=release,
        notes=notes,
        repo_root=repo_root,
    )
    existing = load_history(history_path.resolve())
    for item in existing:
        if item.get("recordKey") == record["recordKey"]:
            return item, False
    history_path.resolve().parent.mkdir(parents=True, exist_ok=True)
    with history_path.resolve().open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(
            json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
        )
    return record, True


def _ratio(current: Any, previous: Any) -> float | None:
    if current is None or previous in (None, 0):
        return None
    return round(float(current) / float(previous), 6)


def _delta(current: Any, previous: Any) -> float | None:
    if current is None or previous is None:
        return None
    return round(float(current) - float(previous), 6)


def _trend_group(
    records: list[dict[str, Any]],
    *,
    limit: int,
    max_token_increase: float,
    max_duration_increase: float,
) -> dict[str, Any]:
    ordered = sorted(records, key=lambda item: str(item["recordedAt"]))
    recent = ordered[-limit:]
    latest = recent[-1]
    previous = recent[-2] if len(recent) > 1 else None
    latest_cases = latest.get("cases") or {}
    previous_cases = (previous or {}).get("cases") or {}
    regressions = sorted(
        case_id
        for case_id, case in latest_cases.items()
        if previous_cases.get(case_id, {}).get("passAtK") is True
        and case.get("passAtK") is False
    )
    recoveries = sorted(
        case_id
        for case_id, case in latest_cases.items()
        if previous_cases.get(case_id, {}).get("passAtK") is False
        and case.get("passAtK") is True
    )
    latest_summary = latest.get("summary") or {}
    previous_summary = (previous or {}).get("summary") or {}
    pass_at_1_delta = _delta(
        latest_summary.get("passAt1"),
        previous_summary.get("passAt1"),
    )
    pass_at_k_delta = _delta(
        latest_summary.get("passAtK"),
        previous_summary.get("passAtK"),
    )
    token_ratio = _ratio(
        latest_summary.get("medianTotalTokens"),
        previous_summary.get("medianTotalTokens"),
    )
    duration_ratio = _ratio(
        latest_summary.get("medianDurationMs"),
        previous_summary.get("medianDurationMs"),
    )
    if previous is None:
        status = "baseline"
    elif regressions or (pass_at_k_delta or 0) < 0 or (pass_at_1_delta or 0) < 0:
        status = "regression"
    elif token_ratio is not None and token_ratio > 1 + max_token_increase:
        status = "cost_regression"
    elif (
        duration_ratio is not None
        and duration_ratio > 1 + max_duration_increase
    ):
        status = "latency_regression"
    elif recoveries or (pass_at_k_delta or 0) > 0 or (pass_at_1_delta or 0) > 0:
        status = "recovery"
    else:
        status = "stable"
    return {
        "series": latest["series"],
        "variant": latest["variant"],
        "status": status,
        "runCount": len(ordered),
        "latest": {
            "recordedAt": latest["recordedAt"],
            "runId": latest["runId"],
            "profile": latest["profile"],
            "sourceReport": latest["sourceReport"],
            "release": latest.get("release"),
        },
        "metrics": {
            "caseCount": latest_summary.get("caseCount"),
            "passAt1": latest_summary.get("passAt1"),
            "passAtK": latest_summary.get("passAtK"),
            "medianTotalTokens": latest_summary.get("medianTotalTokens"),
            "medianDurationMs": latest_summary.get("medianDurationMs"),
            "passAt1Delta": pass_at_1_delta,
            "passAtKDelta": pass_at_k_delta,
            "tokenRatio": token_ratio,
            "durationRatio": duration_ratio,
        },
        "regressions": regressions,
        "recoveries": recoveries,
        "newCases": sorted(set(latest_cases) - set(previous_cases)),
        "removedCases": sorted(set(previous_cases) - set(latest_cases)),
        "runs": [
            {
                "recordedAt": item["recordedAt"],
                "runId": item["runId"],
                "profile": item["profile"],
                "sourceReport": item["sourceReport"],
                "release": item.get("release"),
                "summary": item.get("summary") or {},
            }
            for item in recent
        ],
    }


def build_trend_report(
    history_path: Path,
    output_dir: Path,
    *,
    series: str | None = None,
    limit: int = 20,
    max_token_increase: float = 0.20,
    max_duration_increase: float = 0.25,
) -> dict[str, Any]:
    if limit < 2:
        raise ValueError("trend limit must be >= 2")
    records = load_history(history_path)
    if series is not None:
        records = [item for item in records if item.get("series") == series]
    groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for record in records:
        groups.setdefault(
            (str(record["series"]), str(record["variant"])),
            [],
        ).append(record)
    group_reports = [
        _trend_group(
            items,
            limit=limit,
            max_token_increase=max_token_increase,
            max_duration_increase=max_duration_increase,
        )
        for _, items in sorted(groups.items())
    ]
    status_counts: dict[str, int] = {}
    for item in group_reports:
        status_counts[item["status"]] = status_counts.get(item["status"], 0) + 1
    payload = {
        "schemaVersion": OPS_SCHEMA_VERSION,
        "generatedAt": _now(),
        "historyPath": str(history_path.resolve()),
        "recordCount": len(records),
        "seriesFilter": series,
        "thresholds": {
            "maxTokenIncrease": max_token_increase,
            "maxDurationIncrease": max_duration_increase,
        },
        "summary": {
            "groupCount": len(group_reports),
            "statusCounts": dict(sorted(status_counts.items())),
            "hasRegression": any(
                item["status"] == "regression" for item in group_reports
            ),
            "hasCostRegression": any(
                item["status"] == "cost_regression" for item in group_reports
            ),
        },
        "groups": group_reports,
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "trend.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "trend.html").write_text(
        _render_trend_html(payload),
        encoding="utf-8",
    )
    return payload


def trend_blocks(payload: dict[str, Any]) -> bool:
    return bool(
        (payload.get("summary") or {}).get("hasRegression")
        or (payload.get("summary") or {}).get("hasCostRegression")
    )


def _matrix_path(path: Path) -> Path:
    resolved = path.resolve()
    return resolved / "report.json" if resolved.is_dir() else resolved


def build_matrix_report(
    report_paths: list[Path],
    output_dir: Path,
) -> dict[str, Any]:
    if not report_paths:
        raise ValueError("matrix requires at least one A/B report")
    rows = []
    seen: set[tuple[str, str]] = set()
    for raw_path in report_paths:
        path = _matrix_path(raw_path)
        report = _load_json(path)
        experiment_id = str(report.get("experimentId") or "")
        if not experiment_id:
            raise ValueError(f"A/B report has no experimentId: {path}")
        key = (experiment_id, str(report.get("experimentSpecHash") or ""))
        if key in seen:
            raise ValueError(f"duplicate A/B report in matrix: {experiment_id}")
        seen.add(key)
        experiment = report.get("experiment") or {}
        arms = report.get("arms") or {}
        base = arms.get("base") or {}
        candidate = arms.get("candidate") or {}
        deltas = report.get("metricDeltas") or {}
        rows.append(
            {
                "experimentId": experiment_id,
                "sourceReport": str(path),
                "generatedAt": report.get("generatedAt"),
                "suite": experiment.get("suite"),
                "profile": experiment.get("profile"),
                "javaMode": experiment.get("javaMode"),
                "models": experiment.get("models") or [],
                "repetitions": experiment.get("repetitions"),
                "baseScore": base.get("meanTaskScore"),
                "candidateScore": candidate.get("meanTaskScore"),
                "scoreDelta": deltas.get("meanTaskScore"),
                "passAt1Delta": deltas.get("passAt1"),
                "passAtKDelta": deltas.get("passAtK"),
                "tokenRatio": deltas.get("totalTokensRatio"),
                "recommendation": report.get("recommendation"),
                "anomalyCount": len(report.get("anomalies") or []),
            }
        )
    recommendations: dict[str, int] = {}
    for row in rows:
        recommendation = str(row["recommendation"])
        recommendations[recommendation] = recommendations.get(recommendation, 0) + 1
    payload = {
        "schemaVersion": OPS_SCHEMA_VERSION,
        "generatedAt": _now(),
        "summary": {
            "experimentCount": len(rows),
            "recommendations": dict(sorted(recommendations.items())),
            "hasBlock": recommendations.get("block", 0) > 0,
        },
        "rows": rows,
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "matrix.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "matrix.html").write_text(
        _render_matrix_html(payload),
        encoding="utf-8",
    )
    return payload


def record_release(
    report_path: Path,
    output_dir: Path,
    *,
    version: str,
    commit: str,
    model: str,
    min_pass_at_k: float = 0.0,
) -> dict[str, Any]:
    report_path = report_path.resolve()
    report = _load_json(report_path)
    summary = report.get("summary") or {}
    baseline = report.get("baselineDiff")
    checks = [
        {
            "name": "no_failed_cases",
            "passed": int(summary.get("failed") or 0) == 0,
            "value": summary.get("failed"),
        },
        {
            "name": "no_unstable_cases",
            "passed": int(summary.get("unstable") or 0) == 0,
            "value": summary.get("unstable"),
        },
        {
            "name": "no_unverified_cases",
            "passed": int(summary.get("unverified") or 0) == 0,
            "value": summary.get("unverified"),
        },
        {
            "name": "baseline_compared",
            "passed": isinstance(baseline, dict),
            "value": baseline is not None,
        },
        {
            "name": "no_baseline_regressions",
            "passed": not bool((baseline or {}).get("regressions")),
            "value": list((baseline or {}).get("regressions") or []),
        },
        {
            "name": "minimum_pass_at_k",
            "passed": float(summary.get("passAtK") or 0.0) >= min_pass_at_k,
            "value": summary.get("passAtK"),
            "minimum": min_pass_at_k,
        },
    ]
    decision = "ship" if all(item["passed"] for item in checks) else "block"
    environment = report.get("environment") or {}
    payload = {
        "schemaVersion": OPS_SCHEMA_VERSION,
        "recordedAt": _now(),
        "version": version,
        "commit": commit,
        "model": model,
        "decision": decision,
        "sourceReport": str(report_path),
        "sourceReportSha256": _sha256_bytes(report_path.read_bytes()),
        "runId": report.get("runId"),
        "profile": report.get("profile"),
        "benchmark": report.get("benchmark"),
        "datasetHash": environment.get("datasetHash"),
        "summary": summary,
        "checks": checks,
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "release-record.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "release-record.md").write_text(
        _render_release_markdown(payload),
        encoding="utf-8",
    )
    return payload


def _render_trend_html(payload: dict[str, Any]) -> str:
    rows = []
    for item in payload["groups"]:
        metrics = item["metrics"]
        rows.append(
            "<tr>"
            f"<td>{escape(item['series'])}</td>"
            f"<td>{escape(item['variant'])}</td>"
            f"<td>{escape(item['status'])}</td>"
            f"<td>{escape(str(metrics.get('passAt1')))}</td>"
            f"<td>{escape(str(metrics.get('passAt1Delta')))}</td>"
            f"<td>{escape(str(metrics.get('passAtK')))}</td>"
            f"<td>{escape(str(metrics.get('passAtKDelta')))}</td>"
            f"<td>{escape(str(metrics.get('tokenRatio')))}</td>"
            f"<td>{escape(', '.join(item['regressions']) or '-')}</td>"
            f"<td>{escape(', '.join(item['recoveries']) or '-')}</td>"
            "</tr>"
        )
    run_rows = []
    for item in payload["groups"]:
        for run in item["runs"]:
            summary = run["summary"]
            release = run.get("release") or {}
            run_rows.append(
                "<tr>"
                f"<td>{escape(item['series'])}</td>"
                f"<td>{escape(item['variant'])}</td>"
                f"<td>{escape(str(run['recordedAt']))}</td>"
                f"<td>{escape(str(summary.get('passAt1')))}</td>"
                f"<td>{escape(str(summary.get('passAtK')))}</td>"
                f"<td>{escape(str(summary.get('medianTotalTokens')))}</td>"
                f"<td>{escape(str(release.get('version') or '-'))}</td>"
                f"<td><code>{escape(str(run['sourceReport']))}</code></td>"
                "</tr>"
            )
    return _html_page(
        "Evaluation Trend",
        f"""
        <p>Records: {payload['recordCount']} · Groups: {payload['summary']['groupCount']}</p>
        <h2>Latest Comparison</h2>
        <table>
          <thead><tr><th>Series</th><th>Variant</th><th>Status</th><th>pass@1</th><th>Δ pass@1</th><th>pass^k</th><th>Δ pass^k</th><th>Token ratio</th><th>Regressions</th><th>Recoveries</th></tr></thead>
          <tbody>{''.join(rows)}</tbody>
        </table>
        <h2>Recent Runs</h2>
        <table>
          <thead><tr><th>Series</th><th>Variant</th><th>Recorded</th><th>pass@1</th><th>pass^k</th><th>Median tokens</th><th>Release</th><th>Report</th></tr></thead>
          <tbody>{''.join(run_rows)}</tbody>
        </table>
        """,
    )


def _render_matrix_html(payload: dict[str, Any]) -> str:
    rows = []
    for item in payload["rows"]:
        rows.append(
            "<tr>"
            f"<td>{escape(str(item['experimentId']))}</td>"
            f"<td>{escape(str(item['suite']))}</td>"
            f"<td>{escape(', '.join(str(model) for model in item['models']))}</td>"
            f"<td>{escape(str(item['baseScore']))}</td>"
            f"<td>{escape(str(item['candidateScore']))}</td>"
            f"<td>{escape(str(item['scoreDelta']))}</td>"
            f"<td>{escape(str(item['passAtKDelta']))}</td>"
            f"<td>{escape(str(item['tokenRatio']))}</td>"
            f"<td>{escape(str(item['recommendation']))}</td>"
            f"<td>{item['anomalyCount']}</td>"
            f"<td><code>{escape(str(item['sourceReport']))}</code></td>"
            "</tr>"
        )
    return _html_page(
        "Evaluation Matrix",
        f"""
        <p>Experiments: {payload['summary']['experimentCount']} · Has block: {payload['summary']['hasBlock']}</p>
        <table>
          <thead><tr><th>Experiment</th><th>Suite</th><th>Models</th><th>Base</th><th>Candidate</th><th>Score Δ</th><th>pass^k Δ</th><th>Token ratio</th><th>Recommendation</th><th>Anomalies</th><th>Report</th></tr></thead>
          <tbody>{''.join(rows)}</tbody>
        </table>
        """,
    )


def _render_release_markdown(payload: dict[str, Any]) -> str:
    lines = [
        f"# Release Evaluation: {payload['version']}",
        "",
        f"- Decision: `{payload['decision']}`",
        f"- Commit: `{payload['commit']}`",
        f"- Model: `{payload['model']}`",
        f"- Report: `{payload['sourceReport']}`",
        f"- Dataset: `{payload.get('datasetHash')}`",
        "",
        "## Gate Checks",
        "",
        "| Check | Passed | Value |",
        "|---|---|---|",
    ]
    for check in payload["checks"]:
        lines.append(
            f"| {check['name']} | {check['passed']} | `{check.get('value')}` |"
        )
    lines.append("")
    return "\n".join(lines)


def _html_page(title: str, body: str) -> str:
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>{escape(title)}</title>
  <style>
    body {{ font-family: "Segoe UI", sans-serif; margin: 32px; color: #17202a; background: #fbfcfd; }}
    h1 {{ margin-bottom: 4px; }}
    table {{ border-collapse: collapse; width: 100%; margin-bottom: 24px; background: white; }}
    th, td {{ border: 1px solid #d5d8dc; padding: 8px; text-align: left; vertical-align: top; }}
    th {{ background: #f4f6f7; }}
    code {{ font-size: 12px; }}
  </style>
</head>
<body>
  <h1>{escape(title)}</h1>
  {body}
</body>
</html>
"""
