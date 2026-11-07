from __future__ import annotations

import hashlib
import json
import re
import shutil
from copy import deepcopy
from datetime import datetime, timedelta
from html import escape
from pathlib import Path
from typing import Any

import yaml

from .models import RunTrace
from .trace_tools import write_case_draft

SAMPLE_SCHEMA_VERSION = "1.0"
ALLOWED_STATUSES = {"SUCCESS", "FAILED", "TIMEOUT", "STOPPED"}
DEFAULT_CONFIG: dict[str, Any] = {
    "schemaVersion": 1,
    "lookbackHours": 24,
    "maxCandidates": 300,
    "failureQuota": 20,
    "healthQuota": 10,
    "seed": 42,
    "statuses": ["SUCCESS", "FAILED", "TIMEOUT", "STOPPED"],
    "retentionDays": 14,
    "schedule": {
        "enabled": False,
        "at": "02:30",
        "timezone": "Asia/Shanghai",
    },
    "thresholds": {
        "maxTotalTokens": 50000,
        "maxWallMs": 300000,
        "maxFirstTokenMs": 30000,
        "maxRepeatedToolCalls": 3,
        "minToolSuccessRate": 1.0,
    },
}


def load_sample_config(path: Path | None = None) -> dict[str, Any]:
    config = deepcopy(DEFAULT_CONFIG)
    if path is not None:
        raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        if not isinstance(raw, dict):
            raise TypeError(f"sample config must be a mapping: {path}")
        _merge(config, raw)
    validate_sample_config(config)
    return config


def _merge(base: dict[str, Any], update: dict[str, Any]) -> None:
    for key, value in update.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            _merge(base[key], value)
        else:
            base[key] = value


def validate_sample_config(config: dict[str, Any]) -> None:
    if int(config["schemaVersion"]) != 1:
        raise ValueError("sample config schemaVersion must be 1")
    for key in (
        "lookbackHours",
        "maxCandidates",
        "failureQuota",
        "healthQuota",
        "seed",
        "retentionDays",
    ):
        if not isinstance(config.get(key), int):
            raise TypeError(f"sample config {key} must be an integer")
    if config["lookbackHours"] < 1:
        raise ValueError("lookbackHours must be >= 1")
    if not 1 <= config["maxCandidates"] <= 500:
        raise ValueError("maxCandidates must be between 1 and 500")
    if config["failureQuota"] < 0 or config["healthQuota"] < 0:
        raise ValueError("sample quotas must be >= 0")
    if config["retentionDays"] < 0:
        raise ValueError("retentionDays must be >= 0")
    statuses = [str(item).upper() for item in config.get("statuses") or []]
    if not statuses or any(item not in ALLOWED_STATUSES for item in statuses):
        raise ValueError(
            "statuses must be a non-empty subset of SUCCESS,FAILED,TIMEOUT,STOPPED"
        )
    config["statuses"] = list(dict.fromkeys(statuses))
    thresholds = config.get("thresholds") or {}
    if not isinstance(thresholds, dict):
        raise TypeError("thresholds must be a mapping")
    schedule = config.get("schedule") or {}
    if not isinstance(schedule, dict):
        raise TypeError("schedule must be a mapping")
    if not isinstance(schedule.get("enabled"), bool):
        raise TypeError("schedule.enabled must be a boolean")
    if not isinstance(schedule.get("at"), str):
        raise TypeError("schedule.at must be a string")
    if not isinstance(schedule.get("timezone"), str):
        raise TypeError("schedule.timezone must be a string")
    for key in (
        "maxTotalTokens",
        "maxWallMs",
        "maxFirstTokenMs",
        "maxRepeatedToolCalls",
    ):
        value = thresholds.get(key)
        if value is not None and (not isinstance(value, int) or value < 0):
            raise ValueError(f"thresholds.{key} must be a non-negative integer")
    rate = thresholds.get("minToolSuccessRate")
    if rate is not None and not 0 <= float(rate) <= 1:
        raise ValueError("thresholds.minToolSuccessRate must be between 0 and 1")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _safe_name(value: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-")
    return safe or "unknown-run"


def _finding(code: str, weight: int, message: str, value: Any = None) -> dict[str, Any]:
    return {"code": code, "weight": weight, "message": message, "value": value}


def audit_trace(
    trace: RunTrace,
    *,
    relative_trace: str,
    thresholds: dict[str, Any],
) -> dict[str, Any]:
    run = trace.raw.get("run") or {}
    status = str(run.get("status") or trace.status or "UNKNOWN").upper()
    total_tokens = trace.total_tokens
    duration_ms = trace.duration_ms
    first_token_ms = trace.first_token_ms()
    tool_success_rate = trace.tool_success_rate()
    repeated_tool_calls = trace.repeated_tool_calls()
    failed_tools = [
        {
            "toolName": str(item.get("toolName") or ""),
            "status": str(item.get("status") or "UNKNOWN").upper(),
            "errorMsg": str(item.get("errorMsg") or ""),
        }
        for item in trace.tool_invocations
        if str(item.get("status") or "").upper() in {"FAILED", "TIMEOUT"}
    ]
    findings: list[dict[str, Any]] = []

    if status == "FAILED":
        findings.append(_finding("run_failed", 100, "Run ended in FAILED", status))
    elif status == "TIMEOUT":
        findings.append(_finding("run_timeout", 95, "Run ended in TIMEOUT", status))
    elif status == "STOPPED":
        findings.append(_finding("run_stopped", 40, "Run was stopped", status))

    error_code = str(run.get("errorCode") or "")
    if error_code == "EVAL_BUDGET_EXCEEDED":
        findings.append(
            _finding("budget_exceeded", 90, "Run exceeded its budget", error_code)
        )
    if status == "SUCCESS" and not trace.final_text.strip():
        findings.append(
            _finding("empty_final_answer", 80, "Successful run has an empty final answer")
        )
    if failed_tools:
        findings.append(
            _finding(
                "tool_failure",
                70,
                f"{len(failed_tools)} tool invocation(s) failed",
                failed_tools,
            )
        )
    max_total_tokens = thresholds.get("maxTotalTokens")
    if (
        max_total_tokens is not None
        and total_tokens is not None
        and int(total_tokens) > int(max_total_tokens)
    ):
        findings.append(
            _finding(
                "high_total_tokens",
                40,
                "Run exceeded the token audit threshold",
                total_tokens,
            )
        )
    max_wall_ms = thresholds.get("maxWallMs")
    if (
        max_wall_ms is not None
        and duration_ms is not None
        and int(duration_ms) > int(max_wall_ms)
    ):
        findings.append(
            _finding(
                "slow_run",
                35,
                "Run exceeded the wall-time audit threshold",
                duration_ms,
            )
        )
    max_first_token_ms = thresholds.get("maxFirstTokenMs")
    if (
        max_first_token_ms is not None
        and first_token_ms is not None
        and float(first_token_ms) > int(max_first_token_ms)
    ):
        findings.append(
            _finding(
                "slow_first_token",
                30,
                "First token exceeded the audit threshold",
                first_token_ms,
            )
        )
    max_repeated = thresholds.get("maxRepeatedToolCalls")
    if max_repeated is not None and repeated_tool_calls > int(max_repeated):
        findings.append(
            _finding(
                "repeated_tool_calls",
                50,
                "Repeated identical tool calls exceeded the audit threshold",
                repeated_tool_calls,
            )
        )
    min_success_rate = thresholds.get("minToolSuccessRate")
    if (
        min_success_rate is not None
        and tool_success_rate is not None
        and tool_success_rate < float(min_success_rate)
    ):
        findings.append(
            _finding(
                "low_tool_success_rate",
                60,
                "Tool success rate is below the audit threshold",
                tool_success_rate,
            )
        )

    return {
        "requestId": trace.request_id,
        "sessionId": str(run.get("sessionId") or ""),
        "runCreatedAt": str(run.get("createdAt") or run.get("startedAt") or ""),
        "trace": relative_trace,
        "status": status,
        "finalEmpty": not trace.final_text.strip(),
        "metrics": {
            "totalTokens": total_tokens,
            "durationMs": duration_ms,
            "firstTokenMs": first_token_ms,
            "toolCount": len(trace.tool_invocations),
            "toolFailureCount": len(failed_tools),
            "toolSuccessRate": tool_success_rate,
            "repeatedToolCalls": repeated_tool_calls,
            "artifactCount": len(trace.artifacts),
        },
        "_toolStatuses": [
            str(item.get("status") or "UNKNOWN").upper()
            for item in trace.tool_invocations
        ],
        "failedTools": failed_tools,
        "findings": findings,
        "riskScore": sum(int(item["weight"]) for item in findings),
        "problem": bool(findings),
    }


def _deterministic_key(seed: int, request_id: str) -> str:
    return hashlib.sha256(f"{seed}:{request_id}".encode()).hexdigest()


def _percentile(values: list[float | int], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(float(value) for value in values)
    index = (len(ordered) - 1) * percentile
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = index - lower
    return round(ordered[lower] + (ordered[upper] - ordered[lower]) * fraction, 3)


def _binding_counts(rows: list[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for row in rows:
        for finding in row["findings"]:
            code = str(finding["code"])
            counts[code] = counts.get(code, 0) + 1
    return dict(sorted(counts.items()))


def _summary(audits: list[dict[str, Any]]) -> dict[str, Any]:
    status_counts: dict[str, int] = {}
    failed_by_tool: dict[str, int] = {}
    successful_tools = 0
    for audit in audits:
        status = str(audit["status"])
        status_counts[status] = status_counts.get(status, 0) + 1
        successful_tools += sum(
            1 for tool_status in audit["_toolStatuses"] if tool_status == "SUCCESS"
        )
        for tool in audit["failedTools"]:
            name = str(tool["toolName"])
            failed_by_tool[name] = failed_by_tool.get(name, 0) + 1
    token_values = [
        audit["metrics"]["totalTokens"]
        for audit in audits
        if audit["metrics"]["totalTokens"] is not None
    ]
    duration_values = [
        audit["metrics"]["durationMs"]
        for audit in audits
        if audit["metrics"]["durationMs"] is not None
    ]
    first_token_values = [
        audit["metrics"]["firstTokenMs"]
        for audit in audits
        if audit["metrics"]["firstTokenMs"] is not None
    ]
    tool_counts = sum(int(item["metrics"]["toolCount"]) for item in audits)
    tool_failures = sum(int(item["metrics"]["toolFailureCount"]) for item in audits)
    problem_count = sum(1 for item in audits if item["problem"])
    return {
        "scanned": len(audits),
        "problemCount": problem_count,
        "healthyCount": len(audits) - problem_count,
        "problemRate": round(problem_count / len(audits), 6) if audits else 0.0,
        "statusCounts": dict(sorted(status_counts.items())),
        "findingCounts": _binding_counts(audits),
        "failedTools": dict(sorted(failed_by_tool.items())),
        "emptyFinalCount": sum(1 for item in audits if item["finalEmpty"]),
        "toolInvocations": tool_counts,
        "toolFailures": tool_failures,
        "toolSuccessRate": (
            round(successful_tools / tool_counts, 6)
            if tool_counts
            else None
        ),
        "totalTokens": {
            "p50": _percentile(token_values, 0.50),
            "p95": _percentile(token_values, 0.95),
            "max": max(token_values) if token_values else None,
        },
        "durationMs": {
            "p50": _percentile(duration_values, 0.50),
            "p95": _percentile(duration_values, 0.95),
            "max": max(duration_values) if duration_values else None,
        },
        "firstTokenMs": {
            "p50": _percentile(first_token_values, 0.50),
            "p95": _percentile(first_token_values, 0.95),
            "max": max(first_token_values) if first_token_values else None,
        },
    }


def _load_export(export_dir: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    manifest_path = export_dir / "export-manifest.json"
    if not manifest_path.exists():
        raise FileNotFoundError(f"missing export manifest: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    rows = []
    for relative in manifest.get("traces") or []:
        path = export_dir / str(relative)
        payload = json.loads(path.read_text(encoding="utf-8"))
        rows.append({"path": path, "relative": str(relative), "trace": RunTrace.from_dict(payload)})
    return manifest, rows


def sample_export(
    export_dir: Path,
    output_dir: Path,
    *,
    config: dict[str, Any],
    from_time: datetime,
    to_time: datetime,
) -> dict[str, Any]:
    if from_time >= to_time:
        raise ValueError("sample window must satisfy from < to")
    export_dir = export_dir.resolve()
    output_dir = output_dir.resolve()
    export_manifest, rows = _load_export(export_dir)
    audits: list[dict[str, Any]] = []
    seen: set[str] = set()
    for row in rows:
        request_id = row["trace"].request_id
        if not request_id or request_id in seen:
            continue
        seen.add(request_id)
        audit = audit_trace(
            row["trace"],
            relative_trace=row["relative"],
            thresholds=config["thresholds"],
        )
        run_time = audit["runCreatedAt"]
        if run_time:
            parsed_run_time = datetime.fromisoformat(run_time)
            if parsed_run_time < from_time or parsed_run_time >= to_time:
                continue
        audits.append(audit)

    seed = int(config["seed"])
    problems = sorted(
        (item for item in audits if item["problem"]),
        key=lambda item: (
            -int(item["riskScore"]),
            _deterministic_key(seed, str(item["requestId"])),
        ),
    )
    healthy = sorted(
        (item for item in audits if not item["problem"]),
        key=lambda item: _deterministic_key(seed, str(item["requestId"])),
    )
    selected_problems = problems[: int(config["failureQuota"])]
    selected_healthy = healthy[: int(config["healthQuota"])]

    prior_manifest = output_dir / "sample-manifest.json"
    managed_run = False
    if prior_manifest.is_file():
        try:
            managed_run = (
                json.loads(prior_manifest.read_text(encoding="utf-8")).get(
                    "schemaVersion"
                )
                == SAMPLE_SCHEMA_VERSION
            )
        except (json.JSONDecodeError, OSError):
            managed_run = False
    _reset_managed_dir(output_dir / "samples", root=output_dir, managed=managed_run)
    _reset_managed_dir(output_dir / "drafts", root=output_dir, managed=managed_run)
    selected: list[dict[str, Any]] = []
    for category, items in (
        ("problem", selected_problems),
        ("healthy_control", selected_healthy),
    ):
        for audit in items:
            source = export_dir / str(audit["trace"])
            target_relative = Path("samples") / _safe_name(str(audit["requestId"])) / "trace.json"
            target = output_dir / target_relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            selected.append(
                {
                    **{
                        key: value
                        for key, value in audit.items()
                        if key != "_toolStatuses"
                    },
                    "category": category,
                    "sampleTrace": target_relative.as_posix(),
                    "traceSha256": _sha256(target),
                }
            )

    draft_results: list[dict[str, Any]] = []
    for item in selected:
        if item["category"] != "problem":
            continue
        trace_path = output_dir / str(item["sampleTrace"])
        draft_path = output_dir / "drafts" / f"{_safe_name(str(item['requestId']))}.yaml"
        try:
            draft = write_case_draft(trace_path, draft_path)
            trace_ref = str(item["sampleTrace"])
            draft["source"]["trace"] = trace_ref
            draft["replay"]["baselineTrace"] = trace_ref
            draft_path.write_text(
                yaml.safe_dump(draft, allow_unicode=True, sort_keys=False),
                encoding="utf-8",
            )
            draft_results.append(
                {
                    "requestId": item["requestId"],
                    "draft": draft_path.relative_to(output_dir).as_posix(),
                    "failureClass": draft["source"]["failureClass"],
                    "oracleReviewed": draft["replay"]["oracleReviewed"],
                }
            )
        except ValueError as exc:
            draft_results.append(
                {
                    "requestId": item["requestId"],
                    "draft": None,
                    "error": str(exc),
                    "oracleReviewed": False,
                }
            )

    generated_at = datetime.now().isoformat(timespec="seconds")
    summary = _summary(audits)
    manifest = {
        "schemaVersion": SAMPLE_SCHEMA_VERSION,
        "generatedAt": generated_at,
        "window": {"from": from_time.isoformat(), "to": to_time.isoformat()},
        "sourceExport": {
            "path": str(export_dir),
            "manifestSha256": _sha256(export_dir / "export-manifest.json"),
            "exportedTraces": int(export_manifest.get("exportedTraces") or 0),
        },
        "config": config,
        "summary": {
            **summary,
            "problemCandidates": len(problems),
            "healthyCandidates": len(healthy),
            "selectedProblems": len(selected_problems),
            "selectedHealthy": len(selected_healthy),
            "selectedTotal": len(selected),
        },
        "selection": selected,
        "drafts": draft_results,
        "approval": {
            "automatic": False,
            "requiredCommand": "cute-evals case-approve",
            "note": "Only a human-reviewed deterministic oracle may enter regression.",
        },
    }
    public_audits = [
        {key: value for key, value in item.items() if key != "_toolStatuses"}
        for item in audits
    ]
    audit_report = {
        "schemaVersion": SAMPLE_SCHEMA_VERSION,
        "generatedAt": generated_at,
        "window": manifest["window"],
        "thresholds": config["thresholds"],
        "summary": summary,
        "traces": public_audits,
    }

    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "sample-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "audit-report.json").write_text(
        json.dumps(audit_report, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "audit-report.html").write_text(
        _render_audit_html(manifest, audit_report),
        encoding="utf-8",
    )
    return manifest


def _reset_managed_dir(path: Path, *, root: Path, managed: bool) -> None:
    resolved = path.resolve()
    if resolved.exists():
        if resolved.parent != root.resolve():
            raise ValueError(f"refusing to reset invalid managed path: {resolved}")
        if not managed:
            raise FileExistsError(
                f"refusing to overwrite unmanaged sample output: {resolved}"
            )
        shutil.rmtree(resolved)


def prune_sample_runs(
    root: Path,
    *,
    retention_days: int,
    now: datetime | None = None,
    keep: Path | None = None,
) -> list[str]:
    if retention_days <= 0:
        return []
    root = root.resolve()
    if not root.exists():
        return []
    cutoff = (now or datetime.now()) - timedelta(days=retention_days)
    kept = keep.resolve() if keep is not None else None
    removed: list[str] = []
    for child in sorted(root.iterdir()):
        if not child.is_dir() or (kept is not None and child.resolve() == kept):
            continue
        manifest_path = child / "sample-manifest.json"
        if not manifest_path.is_file():
            continue
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            if manifest.get("schemaVersion") != SAMPLE_SCHEMA_VERSION:
                continue
            generated_at = datetime.fromisoformat(str(manifest["generatedAt"]))
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            continue
        resolved = child.resolve()
        if generated_at < cutoff and resolved.parent == root:
            shutil.rmtree(resolved)
            removed.append(str(resolved))
    return removed


def _render_audit_html(manifest: dict[str, Any], audit: dict[str, Any]) -> str:
    summary = audit["summary"]
    finding_rows = "".join(
        f"<tr><td>{escape(code)}</td><td>{count}</td></tr>"
        for code, count in summary["findingCounts"].items()
    )
    selected_rows = []
    for item in manifest["selection"]:
        codes = ", ".join(str(finding["code"]) for finding in item["findings"])
        selected_rows.append(
            "<tr>"
            f"<td>{escape(str(item['category']))}</td>"
            f"<td><code>{escape(str(item['requestId']))}</code></td>"
            f"<td>{escape(str(item['status']))}</td>"
            f"<td>{escape(str(item['riskScore']))}</td>"
            f"<td>{escape(str(item['metrics']['totalTokens']))}</td>"
            f"<td>{escape(str(item['metrics']['durationMs']))}</td>"
            f"<td>{escape(codes or '-')}</td>"
            "</tr>"
        )
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <title>Ledger Trace Audit</title>
  <style>
    body {{ font-family: "Segoe UI", sans-serif; margin: 32px; color: #17202a; background: #fbfcfd; }}
    table {{ border-collapse: collapse; width: 100%; margin: 16px 0 28px; background: white; }}
    th, td {{ border: 1px solid #d5d8dc; padding: 8px; text-align: left; vertical-align: top; }}
    th {{ background: #f4f6f7; }}
    .metric {{ display: inline-block; margin-right: 24px; }}
    code {{ font-size: 12px; }}
  </style>
</head>
<body>
  <h1>Ledger Trace Audit</h1>
  <p>Window: {escape(manifest['window']['from'])} to {escape(manifest['window']['to'])}</p>
  <p>
    <span class="metric">Scanned: <strong>{summary['scanned']}</strong></span>
    <span class="metric">Problems: <strong>{summary['problemCount']}</strong></span>
    <span class="metric">Selected: <strong>{manifest['summary']['selectedTotal']}</strong></span>
    <span class="metric">Tool success: <strong>{summary['toolSuccessRate']}</strong></span>
  </p>
  <h2>Findings</h2>
  <table><thead><tr><th>Finding</th><th>Count</th></tr></thead><tbody>{finding_rows}</tbody></table>
  <h2>Selected Samples</h2>
  <table>
    <thead><tr><th>Category</th><th>Request</th><th>Status</th><th>Risk</th><th>Tokens</th><th>Duration ms</th><th>Findings</th></tr></thead>
    <tbody>{''.join(selected_rows)}</tbody>
  </table>
  <p>Badcase drafts require human Oracle review. This report never approves a regression case.</p>
</body>
</html>
"""
