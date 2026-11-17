from __future__ import annotations

import json
import math
from collections import Counter
from datetime import datetime
from html import escape
from pathlib import Path
from statistics import median
from typing import Any

from .models import CaseResult


def _median(values: list[float | int | None]) -> float | None:
    present = [float(value) for value in values if value is not None]
    return median(present) if present else None


def _ratio_text(metrics: dict[str, Any]) -> str:
    ratio = metrics.get("hitRatio")
    sampled = int(metrics.get("sampledInvocations") or 0)
    unverified = int(metrics.get("unverifiedInvocations") or 0)
    missing = int(metrics.get("missingCachedPromptTokens") or 0)
    null = int(metrics.get("nullCachedPromptTokens") or 0)
    value = f"{float(ratio):.1%}" if ratio is not None else "n/a"
    return (
        f"{value} (samples={sampled}, unverified={unverified}, "
        f"missing={missing}, null={null})"
    )


def _wilson_interval(
    successes: int,
    total: int,
    *,
    z: float = 1.96,
) -> dict[str, float] | None:
    if total <= 0:
        return None
    proportion = successes / total
    denominator = 1 + (z * z / total)
    center = (proportion + z * z / (2 * total)) / denominator
    margin = (
        z
        * math.sqrt(
            proportion * (1 - proportion) / total
            + z * z / (4 * total * total)
        )
        / denominator
    )
    return {
        "lower": max(0.0, center - margin),
        "upper": min(1.0, center + margin),
    }


def _failure_categories(result: CaseResult) -> list[str]:
    if result.passed and not result.unverified:
        return []
    categories: set[str] = set()
    if result.trace.status == "TIMEOUT":
        categories.add("timeout")
    elif result.trace.status != "SUCCESS":
        error_code = str(
            (result.trace.raw.get("run") or {}).get("errorCode") or ""
        )
        categories.add(
            "budget" if error_code == "EVAL_BUDGET_EXCEEDED" else "run_status"
        )

    for invocation in result.trace.llm_invocations:
        status = str(invocation.get("status", "")).upper()
        if status == "TIMEOUT":
            categories.add("model_timeout")
        elif status == "FAILED":
            categories.add("model")

    for invocation in result.trace.tool_invocations:
        status = str(invocation.get("status", "")).upper()
        if status == "SUCCESS":
            continue
        error = str(invocation.get("errorMsg") or "").lower()
        if "permission" in error:
            categories.add("tool_permission")
        elif "429" in error or "rate limit" in error:
            categories.add("tool_rate_limit")
        elif "timeout" in error:
            categories.add("tool_timeout")
        elif "forbidden" in error or "403" in error:
            categories.add("tool_forbidden")
        elif (
            "cannot connect to host" in error
            or "connect call failed" in error
            or "proxy" in error
            or "502" in error
        ):
            categories.add("tool_network")
        elif "upstream" in error or "503" in error:
            categories.add("tool_upstream")
        elif "json" in error or "malformed" in error:
            categories.add("tool_payload")
        elif "argument" in error or "required" in error:
            categories.add("tool_arguments")
        else:
            categories.add("tool_failure")

    score_categories = {
        "tool_sequence": "trajectory",
        "tool_recovery": "recovery",
        "max_steps": "loop_or_step_limit",
        "token_budget": "budget",
        "wall_time": "budget",
        "artifact_count": "artifact",
        "answer_present": "answer_contract",
        "expected_facts": "oracle_mismatch",
        "citation_coverage": "citation",
        "citation_recall": "retrieval",
        "stage_contract": "run_status",
        "sql_execution": "data_oracle",
        "sql_result": "data_oracle",
        "task_success": "oracle_mismatch",
        "official_exact_match": "official_oracle",
        "official_reward": "official_oracle",
        "official_verifier": "official_verifier",
        "answer_contract": "answer_contract",
        "db_terminal_state_hash": "environment",
        "user_simulator_status": "user_simulator",
        "skill_selection": "skill_trajectory",
        "skill_load_count": "skill_budget",
        "tool_selection": "tool_selection",
        "trajectory_match": "trajectory",
        "trajectory_judge": "judge",
        "route_precision": "route",
        "route_recall": "route",
        "route_f1": "route",
        "subgoal_progress": "progress",
        "task_completion": "judge",
        "faithfulness": "judge",
    }
    for score in result.scores:
        if score.name == "user_simulator_status" and score.passed is False:
            categories.add("user_simulator")
        if score.name == "skill_selection" and score.passed is False:
            categories.add("skill_trajectory")
        if score.passed is False and score.hard_gate:
            categories.add(score_categories.get(score.name, score.scorer))
        if score.status == "unverified" and score.hard_gate:
            categories.add("unverified")
        if str(score.evidence.get("status") or "") == "infrastructure_error":
            categories.add("environment")
    return sorted(categories)


def _tool_failure_classes(result: CaseResult) -> list[str]:
    categories = []
    for invocation in result.trace.tool_invocations:
        if str(invocation.get("status", "")).upper() == "SUCCESS":
            continue
        error = str(invocation.get("errorMsg") or "").lower()
        if "permission" in error:
            categories.append("permission")
        elif "429" in error or "rate limit" in error:
            categories.append("rate_limit")
        elif "timeout" in error:
            categories.append("timeout")
        elif "forbidden" in error or "403" in error:
            categories.append("forbidden")
        elif (
            "cannot connect to host" in error
            or "connect call failed" in error
            or "proxy" in error
            or "502" in error
        ):
            categories.append("network")
        elif "upstream" in error or "503" in error:
            categories.append("upstream")
        elif "json" in error or "malformed" in error:
            categories.append("malformed_payload")
        elif "argument" in error or "required" in error:
            categories.append("invalid_arguments")
        else:
            categories.append("unknown")
    return sorted(categories)


def _case_summary(case_id: str, attempts: list[CaseResult]) -> dict[str, Any]:
    ordered = sorted(attempts, key=lambda item: item.attempt)
    first = ordered[0]
    passed_count = sum(1 for item in ordered if item.passed)
    quality_passed_count = sum(1 for item in ordered if item.quality_passed)
    slo_passed_count = sum(1 for item in ordered if item.slo_passed)
    pass_at_1 = first.passed and not first.unverified
    pass_at_k = all(item.passed and not item.unverified for item in ordered)
    quality_pass_at_1 = first.quality_passed and not first.unverified
    quality_pass_at_k = all(
        item.quality_passed and not item.unverified for item in ordered
    )
    slo_pass_at_1 = first.slo_passed
    slo_pass_at_k = all(item.slo_passed for item in ordered)
    hard_gate_failed = any(item.hard_gate_failed for item in ordered)
    any_unverified = any(item.unverified for item in ordered)
    if hard_gate_failed:
        status = "failed" if not passed_count else "unstable"
    elif any_unverified:
        status = "unverified"
    elif pass_at_k:
        status = "passed"
    elif passed_count:
        status = "unstable"
    else:
        status = "failed"
    quality_status = (
        "failed"
        if hard_gate_failed and not quality_pass_at_k
        else "unverified"
        if any_unverified
        else ("passed" if quality_pass_at_k else "failed")
    )
    slo_status = "passed" if slo_pass_at_k else "failed"
    categories = Counter(
        category for item in ordered for category in _failure_categories(item)
    )
    tool_failures = Counter(
        category for item in ordered for category in _tool_failure_classes(item)
    )
    return {
        "caseId": case_id,
        "suite": first.case.suite,
        "metadata": first.case.metadata,
        "status": status,
        "qualityStatus": quality_status,
        "sloStatus": slo_status,
        "attemptCount": len(ordered),
        "passedAttempts": passed_count,
        "qualityPassedAttempts": quality_passed_count,
        "sloPassedAttempts": slo_passed_count,
        "passAt1": pass_at_1,
        "passAtK": pass_at_k,
        "qualityPassAt1": quality_pass_at_1,
        "qualityPassAtK": quality_pass_at_k,
        "sloPassAt1": slo_pass_at_1,
        "sloPassAtK": slo_pass_at_k,
        "medianTotalTokens": _median(
            [item.trace.total_tokens for item in ordered]
        ),
        "medianDurationMs": _median(
            [item.trace.duration_ms for item in ordered]
        ),
        "medianFirstTokenMs": _median(
            [item.trace.first_token_ms() for item in ordered]
        ),
        "cacheHitMetrics": _merge_cache_metrics(ordered),
        "failureCategories": dict(sorted(categories.items())),
        "toolFailureClasses": dict(sorted(tool_failures.items())),
        "tracePaths": [item.trace_path for item in ordered],
        "scores": [score.to_dict() for score in first.scores],
    }


def _merge_cache_metrics(items: list[CaseResult]) -> dict[str, int | float | None]:
    sampled_invocations = 0
    unverified_invocations = 0
    missing_cached_tokens = 0
    null_cached_tokens = 0
    invalid_prompt_tokens = 0
    prompt_tokens = 0
    cached_tokens = 0
    for item in items:
        metrics = item.trace.cache_hit_metrics()
        sampled_invocations += int(metrics["sampledInvocations"] or 0)
        unverified_invocations += int(metrics["unverifiedInvocations"] or 0)
        missing_cached_tokens += int(
            metrics["missingCachedPromptTokens"] or 0
        )
        null_cached_tokens += int(metrics["nullCachedPromptTokens"] or 0)
        invalid_prompt_tokens += int(metrics["invalidPromptTokens"] or 0)
        prompt_tokens += int(metrics["promptTokens"] or 0)
        cached_tokens += int(metrics["cachedPromptTokens"] or 0)
    return {
        "sampledInvocations": sampled_invocations,
        "unverifiedInvocations": unverified_invocations,
        "missingCachedPromptTokens": missing_cached_tokens,
        "nullCachedPromptTokens": null_cached_tokens,
        "invalidPromptTokens": invalid_prompt_tokens,
        "promptTokens": prompt_tokens,
        "cachedPromptTokens": cached_tokens,
        "hitRatio": cached_tokens / prompt_tokens if prompt_tokens else None,
    }


def _compaction_summary(case_summaries: list[dict[str, Any]]) -> dict[str, Any]:
    compressed = []
    controls = []
    for item in case_summaries:
        scores = {score["name"]: score for score in item["scores"]}
        if "compaction_triggered" in scores:
            compressed.append((item, scores))
        elif "compaction_control_clean" in scores:
            controls.append((item, scores))
    if not compressed and not controls:
        return {}

    strategies: Counter[str] = Counter()
    ratios: list[float] = []
    before_tokens = 0
    after_tokens = 0
    for _, scores in compressed:
        triggered = scores.get("compaction_triggered") or {}
        for strategy in (triggered.get("evidence") or {}).get("strategies") or []:
            strategies[str(strategy)] += 1
        ratio_score = scores.get("compression_ratio") or {}
        ratio = ratio_score.get("value")
        evidence = ratio_score.get("evidence") or {}
        if isinstance(ratio, (int, float)):
            ratios.append(float(ratio))
        if isinstance(evidence.get("beforeTokens"), (int, float)):
            before_tokens += int(evidence["beforeTokens"])
        if isinstance(evidence.get("afterTokens"), (int, float)):
            after_tokens += int(evidence["afterTokens"])

    return {
        "compressedCaseCount": len(compressed),
        "triggeredCaseCount": sum(
            1
            for _, scores in compressed
            if (scores.get("compaction_triggered") or {}).get("value") is True
        ),
        "carryForwardQualityPassCount": sum(
            1 for item, _ in compressed if item["qualityPassAt1"]
        ),
        "carryForwardStrictPassCount": sum(
            1 for item, _ in compressed if item["passAt1"]
        ),
        "controlCaseCount": len(controls),
        "controlCleanCount": sum(
            1
            for _, scores in controls
            if (scores.get("compaction_control_clean") or {}).get("value") is True
        ),
        "strategies": dict(sorted(strategies.items())),
        "fullLlmCount": sum(
            count
            for strategy, count in strategies.items()
            if strategy.startswith("full-llm")
        ),
        "fallbackCount": sum(
            count
            for strategy, count in strategies.items()
            if "fallback" in strategy
        ),
        "medianCompressionRatio": _median(ratios),
        "beforeTokens": before_tokens if ratios else None,
        "afterTokens": after_tokens if ratios else None,
        "reclaimedTokens": before_tokens - after_tokens if ratios else None,
        "expandedCaseCount": sum(1 for ratio in ratios if ratio < 1.0),
    }


def _stratum_metrics(items: list[dict[str, Any]]) -> dict[str, Any]:
    pass_at_1_count = sum(1 for item in items if item["passAt1"])
    pass_at_k_count = sum(1 for item in items if item["passAtK"])
    quality_pass_at_1_count = sum(
        1 for item in items if item["qualityPassAt1"]
    )
    quality_pass_at_k_count = sum(
        1 for item in items if item["qualityPassAtK"]
    )
    slo_pass_at_1_count = sum(1 for item in items if item["sloPassAt1"])
    slo_pass_at_k_count = sum(1 for item in items if item["sloPassAtK"])
    return {
        "caseCount": len(items),
        "passed": sum(1 for item in items if item["status"] == "passed"),
        "failed": sum(1 for item in items if item["status"] == "failed"),
        "unstable": sum(1 for item in items if item["status"] == "unstable"),
        "unverified": sum(1 for item in items if item["status"] == "unverified"),
        "passAt1": pass_at_1_count / len(items) if items else 0.0,
        "passAtK": pass_at_k_count / len(items) if items else 0.0,
        "qualityPassAt1": (
            quality_pass_at_1_count / len(items) if items else 0.0
        ),
        "qualityPassAtK": (
            quality_pass_at_k_count / len(items) if items else 0.0
        ),
        "sloPassAt1": slo_pass_at_1_count / len(items) if items else 0.0,
        "sloPassAtK": slo_pass_at_k_count / len(items) if items else 0.0,
        "passAt1Interval": _wilson_interval(pass_at_1_count, len(items)),
        "passAtKInterval": _wilson_interval(pass_at_k_count, len(items)),
        "qualityPassAt1Interval": _wilson_interval(
            quality_pass_at_1_count, len(items)
        ),
        "qualityPassAtKInterval": _wilson_interval(
            quality_pass_at_k_count, len(items)
        ),
        "sloPassAt1Interval": _wilson_interval(slo_pass_at_1_count, len(items)),
        "sloPassAtKInterval": _wilson_interval(slo_pass_at_k_count, len(items)),
        "medianTotalTokens": _median(
            [item["medianTotalTokens"] for item in items]
        ),
        "medianDurationMs": _median(
            [item["medianDurationMs"] for item in items]
        ),
    }


def _strata(case_summaries: list[dict[str, Any]]) -> dict[str, Any]:
    dimensions = (
        "level",
        "role",
        "taskType",
        "lengthBucket",
        "toolCountBucket",
        "risk",
    )
    result: dict[str, Any] = {}
    for dimension in dimensions:
        groups: dict[str, list[dict[str, Any]]] = {}
        for item in case_summaries:
            value = (item.get("metadata") or {}).get(dimension)
            if value is None:
                continue
            groups.setdefault(str(value), []).append(item)
        if groups:
            result[dimension] = {
                name: _stratum_metrics(items)
                for name, items in sorted(groups.items())
            }
    return result


def _is_diagnostic_score(score: dict[str, Any]) -> bool:
    return (
        str(score.get("scorer") or "").endswith(".diagnostic")
        or str(score.get("name") or "").startswith("diagnostic_")
    )


def _diagnostic_summary(case_summaries: list[dict[str, Any]]) -> dict[str, Any]:
    scores = []
    grouped: dict[str, list[float]] = {}
    for case in case_summaries:
        for score in case.get("scores") or []:
            if not _is_diagnostic_score(score):
                continue
            scores.append({
                "caseId": case["caseId"],
                "name": score.get("name"),
                "value": score.get("value"),
                "unit": score.get("unit"),
                "status": score.get("status"),
                "evidence": score.get("evidence") or {},
            })
            value = score.get("value")
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                grouped.setdefault(str(score.get("name")), []).append(float(value))
    metrics = {}
    for name, values in sorted(grouped.items()):
        metrics[name] = {
            "samples": len(values),
            "mean": sum(values) / len(values),
            "median": _median(values),
        }
    return {"scores": scores, "metrics": metrics}


def build_baseline(report: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": "1.0",
        "generatedAt": report["generatedAt"],
        "profile": report["profile"],
        "agent": report["agent"],
        "datasetHash": report.get("environment", {}).get("datasetHash"),
        "environment": report.get("environment", {}),
        "summary": {
            "caseCount": report["summary"]["caseCount"],
            "passAt1": report["summary"]["passAt1"],
            "passAtK": report["summary"]["passAtK"],
            "qualityPassAt1": report["summary"].get("qualityPassAt1"),
            "qualityPassAtK": report["summary"].get("qualityPassAtK"),
            "sloPassAt1": report["summary"].get("sloPassAt1"),
            "sloPassAtK": report["summary"].get("sloPassAtK"),
        },
        "cases": {
            item["caseId"]: {
                "status": item["status"],
                "passAt1": item["passAt1"],
                "passAtK": item["passAtK"],
                "qualityPassAt1": item["qualityPassAt1"],
                "qualityPassAtK": item["qualityPassAtK"],
                "sloPassAt1": item["sloPassAt1"],
                "sloPassAtK": item["sloPassAtK"],
                "medianTotalTokens": item["medianTotalTokens"],
                "medianDurationMs": item["medianDurationMs"],
                "medianFirstTokenMs": item["medianFirstTokenMs"],
            }
            for item in report["caseSummaries"]
        },
    }


def diff_baseline(
    report: dict[str, Any], baseline: dict[str, Any]
) -> dict[str, Any]:
    current_cases = {
        item["caseId"]: item for item in report["caseSummaries"]
    }
    baseline_cases = baseline.get("cases") or {}
    new_cases = sorted(set(current_cases) - set(baseline_cases))
    removed_cases = sorted(set(baseline_cases) - set(current_cases))
    regressions = []
    recoveries = []
    metric_deltas = {}
    for case_id in sorted(set(current_cases) & set(baseline_cases)):
        current = current_cases[case_id]
        previous = baseline_cases[case_id]
        if previous.get("passAtK") is True and current["passAtK"] is False:
            regressions.append(case_id)
        if previous.get("passAtK") is False and current["passAtK"] is True:
            recoveries.append(case_id)
        metric_deltas[case_id] = {
            key: (
                None
                if current.get(key) is None or previous.get(key) is None
                else round(float(current[key]) - float(previous[key]), 3)
            )
            for key in (
                "medianTotalTokens",
                "medianDurationMs",
                "medianFirstTokenMs",
            )
        }
    return {
        "baselineGeneratedAt": baseline.get("generatedAt"),
        "datasetChanged": (
            (baseline.get("datasetHash") is not None)
            and baseline.get("datasetHash")
            != report.get("environment", {}).get("datasetHash")
        ),
        "judgeChanged": (
            (baseline.get("environment") or {}).get("judgeModel")
            != report.get("environment", {}).get("judgeModel")
            or (baseline.get("environment") or {}).get("judgeBaseUrl")
            != report.get("environment", {}).get("judgeBaseUrl")
        ),
        "regressions": regressions,
        "recoveries": recoveries,
        "newCases": new_cases,
        "removedCases": removed_cases,
        "metricDeltas": metric_deltas,
        "passAt1Delta": round(
            float(report["summary"]["passAt1"])
            - float((baseline.get("summary") or {}).get("passAt1") or 0.0),
            4,
        ),
        "passAtKDelta": round(
            float(report["summary"]["passAtK"])
            - float((baseline.get("summary") or {}).get("passAtK") or 0.0),
            4,
        ),
        "qualityPassAt1Delta": round(
            float(report["summary"].get("qualityPassAt1") or 0.0)
            - float((baseline.get("summary") or {}).get("qualityPassAt1") or 0.0),
            4,
        ),
        "sloPassAt1Delta": round(
            float(report["summary"].get("sloPassAt1") or 0.0)
            - float((baseline.get("summary") or {}).get("sloPassAt1") or 0.0),
            4,
        ),
    }


def build_report(
    *,
    profile: str,
    agent: str,
    cases: list[CaseResult],
    output_dir: Path,
    baseline: dict[str, Any] | None = None,
    inspect_log: str | None = None,
    environment: dict[str, Any] | None = None,
    benchmark: dict[str, Any] | None = None,
) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    grouped: dict[str, list[CaseResult]] = {}
    for case in cases:
        grouped.setdefault(case.case.id, []).append(case)
    case_summaries = [
        _case_summary(case_id, attempts)
        for case_id, attempts in sorted(grouped.items())
    ]
    status_counts = Counter(item["status"] for item in case_summaries)
    failure_counts = Counter(
        category
        for item in case_summaries
        for category, count in item["failureCategories"].items()
        for _ in range(count)
    )
    tool_failure_counts = Counter(
        category
        for item in case_summaries
        for category, count in item["toolFailureClasses"].items()
        for _ in range(count)
    )
    case_count = len(case_summaries)
    pass_at_1_count = sum(1 for item in case_summaries if item["passAt1"])
    pass_at_k_count = sum(1 for item in case_summaries if item["passAtK"])
    quality_pass_at_1_count = sum(
        1 for item in case_summaries if item["qualityPassAt1"]
    )
    quality_pass_at_k_count = sum(
        1 for item in case_summaries if item["qualityPassAtK"]
    )
    slo_pass_at_1_count = sum(
        1 for item in case_summaries if item["sloPassAt1"]
    )
    slo_pass_at_k_count = sum(
        1 for item in case_summaries if item["sloPassAtK"]
    )
    cache_metrics = _merge_cache_metrics(cases)
    payload = {
        "runId": f"eval-{datetime.now().strftime('%Y%m%d%H%M%S')}",
        "profile": profile,
        "agent": agent,
        "benchmark": benchmark,
        "generatedAt": datetime.now().isoformat(timespec="milliseconds"),
        "environment": environment or {},
        "inspectLog": inspect_log,
        "summary": {
            "caseCount": case_count,
            "attemptCount": len(cases),
            "passed": status_counts["passed"],
            "failed": status_counts["failed"],
            "unstable": status_counts["unstable"],
            "unverified": status_counts["unverified"],
            "passAt1": pass_at_1_count / case_count if case_count else 0.0,
            "passAtK": pass_at_k_count / case_count if case_count else 0.0,
            "qualityPassAt1": (
                quality_pass_at_1_count / case_count if case_count else 0.0
            ),
            "qualityPassAtK": (
                quality_pass_at_k_count / case_count if case_count else 0.0
            ),
            "sloPassAt1": (
                slo_pass_at_1_count / case_count if case_count else 0.0
            ),
            "sloPassAtK": (
                slo_pass_at_k_count / case_count if case_count else 0.0
            ),
            "passAt1Count": pass_at_1_count,
            "passAtKCount": pass_at_k_count,
            "qualityPassAt1Count": quality_pass_at_1_count,
            "qualityPassAtKCount": quality_pass_at_k_count,
            "sloPassAt1Count": slo_pass_at_1_count,
            "sloPassAtKCount": slo_pass_at_k_count,
            "confidenceIntervals": {
                "level": 0.95,
                "method": "wilson",
                "passAt1": _wilson_interval(pass_at_1_count, case_count),
                "passAtK": _wilson_interval(pass_at_k_count, case_count),
                "qualityPassAt1": _wilson_interval(
                    quality_pass_at_1_count, case_count
                ),
                "qualityPassAtK": _wilson_interval(
                    quality_pass_at_k_count, case_count
                ),
                "sloPassAt1": _wilson_interval(
                    slo_pass_at_1_count, case_count
                ),
                "sloPassAtK": _wilson_interval(
                    slo_pass_at_k_count, case_count
                ),
            },
            "failureCategories": dict(sorted(failure_counts.items())),
            "toolFailureClasses": dict(sorted(tool_failure_counts.items())),
            "cache": cache_metrics,
            "compaction": _compaction_summary(case_summaries),
            "strata": _strata(case_summaries),
            "diagnostics": _diagnostic_summary(case_summaries),
            "advisoryUnverifiedScores": sum(
                1
                for item in cases
                for score in item.scores
                if score.status == "unverified" and not score.hard_gate
            ),
        },
        "caseSummaries": case_summaries,
        "caseResults": [case.to_dict() for case in cases],
    }
    if benchmark:
        payload.update(
            {
                "officialScore": benchmark.get("officialScore"),
                "scoreLabel": benchmark.get("scoreLabel"),
                "runnerMode": benchmark.get("runnerMode"),
            }
        )
    payload["baselineDiff"] = (
        diff_baseline(payload, baseline) if baseline is not None else None
    )
    (output_dir / "report.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "report.html").write_text(_render_html(payload), encoding="utf-8")
    return payload


def _render_html(payload: dict[str, Any]) -> str:
    rows = []
    for case in payload["caseSummaries"]:
        categories = ", ".join(case["failureCategories"]) or "-"
        tool_failures = ", ".join(case["toolFailureClasses"]) or "-"
        traces = "<br/>".join(
            f"<code>{escape(path)}</code>" for path in case["tracePaths"]
        )
        rows.append(
            "<tr>"
            f"<td>{escape(case['caseId'])}</td>"
            f"<td class='status {escape(case['status'])}'>{escape(case['status'])}</td>"
            f"<td>{case['passedAttempts']}/{case['attemptCount']}</td>"
            f"<td>{case['qualityPassedAttempts']}/{case['attemptCount']}</td>"
            f"<td>{case['sloPassedAttempts']}/{case['attemptCount']}</td>"
            f"<td>{escape(str(case['medianTotalTokens']))}</td>"
            f"<td>{escape(str(case['medianDurationMs']))}</td>"
            f"<td>{escape(str(case['medianFirstTokenMs']))}</td>"
            f"<td>{escape(_ratio_text(case['cacheHitMetrics']))}</td>"
            f"<td>{escape(categories)}</td>"
            f"<td>{escape(tool_failures)}</td>"
            f"<td>{traces}</td>"
            "</tr>"
        )
    summary = payload["summary"]
    baseline = payload.get("baselineDiff") or {}
    regressions = ", ".join(baseline.get("regressions") or []) or "none"
    benchmark = payload.get("benchmark") or {}
    benchmark_html = ""
    if benchmark:
        benchmark_html = (
            "<p><strong>Benchmark:</strong> "
            f"{escape(str(benchmark.get('name')))} "
            f"{escape(str(benchmark.get('version')))}, "
            f"score label <code>{escape(str(benchmark.get('scoreLabel')))}</code>, "
            f"split <code>{escape(str(benchmark.get('split')))}</code>, "
            f"dataset <code>{escape(str(benchmark.get('datasetHash')))}</code></p>"
        )
    tool_failure_classes = ", ".join(
        f"{name}={count}"
        for name, count in (summary.get("toolFailureClasses") or {}).items()
    ) or "none"
    cache = summary.get("cache") or {}
    cache_text = _ratio_text(cache)
    compaction = summary.get("compaction") or {}
    compaction_html = ""
    if compaction:
        strategies = ", ".join(
            f"{escape(name)}={count}"
            for name, count in (compaction.get("strategies") or {}).items()
        ) or "none"
        ratio = compaction.get("medianCompressionRatio")
        ratio_text = f"{float(ratio):.2f}x" if ratio is not None else "n/a"
        compaction_html = (
            "<p><strong>Compaction:</strong> "
            f"triggered {compaction['triggeredCaseCount']}/"
            f"{compaction['compressedCaseCount']}; "
            f"carry-forward quality {compaction['carryForwardQualityPassCount']}/"
            f"{compaction['compressedCaseCount']}; "
            f"strict {compaction['carryForwardStrictPassCount']}/"
            f"{compaction['compressedCaseCount']}; "
            f"control clean {compaction['controlCleanCount']}/"
            f"{compaction['controlCaseCount']}; "
            f"median ratio {ratio_text}; "
            f"expanded {compaction['expandedCaseCount']}; "
            f"strategies {strategies}</p>"
        )
    strata_rows = []
    for dimension, groups in (summary.get("strata") or {}).items():
        for group, values in groups.items():
            pass_at_1_interval = values.get("passAt1Interval") or {}
            pass_at_k_interval = values.get("passAtKInterval") or {}
            pass_at_1_ci = (
                f"{pass_at_1_interval.get('lower', 0):.0%}-"
                f"{pass_at_1_interval.get('upper', 0):.0%}"
                if pass_at_1_interval
                else "-"
            )
            pass_at_k_ci = (
                f"{pass_at_k_interval.get('lower', 0):.0%}-"
                f"{pass_at_k_interval.get('upper', 0):.0%}"
                if pass_at_k_interval
                else "-"
            )
            strata_rows.append(
                "<tr>"
                f"<td>{escape(str(dimension))}</td>"
                f"<td>{escape(str(group))}</td>"
                f"<td>{values['caseCount']}</td>"
                f"<td>{values['passAt1']:.2%}</td>"
                f"<td>{escape(pass_at_1_ci)}</td>"
                f"<td>{values['passAtK']:.2%}</td>"
                f"<td>{escape(pass_at_k_ci)}</td>"
                f"<td>{escape(str(values['medianTotalTokens']))}</td>"
                "</tr>"
            )
    strata_html = (
        "<h2>Stratified results</h2>"
        "<table><thead><tr><th>Dimension</th><th>Group</th><th>Cases</th>"
        "<th>pass@1</th><th>pass@1 95% CI</th><th>pass^k</th>"
        "<th>pass^k 95% CI</th><th>Median tokens</th></tr></thead>"
        f"<tbody>{''.join(strata_rows)}</tbody></table>"
        if strata_rows
        else ""
    )
    official_score_rows = []
    diagnostic_score_rows = []
    for case in payload["caseSummaries"]:
        for score in case.get("scores") or []:
            row = (
                "<tr>"
                f"<td>{escape(str(case['caseId']))}</td>"
                f"<td>{escape(str(score.get('scorer')))}</td>"
                f"<td>{escape(str(score.get('name')))}</td>"
                f"<td>{escape(str(score.get('value')))}</td>"
                f"<td>{escape(str(score.get('unit')))}</td>"
                f"<td>{escape(str(score.get('pass')))}</td>"
                f"<td>{escape(str(score.get('status')))}</td>"
                f"<td><code>{escape(json.dumps(score.get('evidence') or {}, ensure_ascii=False))}</code></td>"
                "</tr>"
            )
            if _is_diagnostic_score(score):
                diagnostic_score_rows.append(row)
            else:
                official_score_rows.append(row)
    score_table_head = (
        "<thead><tr><th>Case</th><th>Scorer</th><th>Metric</th>"
        "<th>Value</th><th>Unit</th><th>Pass</th><th>Status</th>"
        "<th>Evidence</th></tr></thead>"
    )
    official_scores_html = (
        "<h2>Official and gate scores</h2>"
        f"<table>{score_table_head}<tbody>{''.join(official_score_rows)}</tbody></table>"
        if official_score_rows
        else ""
    )
    diagnostics = payload["summary"].get("diagnostics") or {}
    diagnostic_metrics = diagnostics.get("metrics") or {}
    diagnostic_metrics_html = ""
    if diagnostic_metrics:
        metric_text = ", ".join(
            f"{escape(name)}={float(values['mean']):.4f}"
            for name, values in diagnostic_metrics.items()
        )
        diagnostic_metrics_html = (
            "<p><strong>Diagnostic means:</strong> "
            f"{metric_text}</p>"
        )
    diagnostic_scores_html = (
        "<h2>Diagnostics (advisory only)</h2>"
        f"<table>{score_table_head}<tbody>{''.join(diagnostic_score_rows)}</tbody></table>"
        if diagnostic_score_rows
        else ""
    )
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8"/>
  <title>Cute-Agent Bench Report</title>
  <style>
    body {{ font-family: "Segoe UI", sans-serif; margin: 32px; color: #17202a; background: #fbfcfd; }}
    h1 {{ margin-bottom: 4px; }}
    .meta {{ color: #5d6d7e; margin-bottom: 24px; }}
    .cards {{ display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 24px; }}
    .card {{ border: 1px solid #d5d8dc; border-radius: 8px; padding: 12px 18px; background: white; }}
    table {{ border-collapse: collapse; width: 100%; background: white; }}
    th, td {{ border: 1px solid #d5d8dc; padding: 8px; text-align: left; vertical-align: top; }}
    th {{ background: #f4f6f7; }}
    code {{ font-size: 12px; }}
    .status.passed {{ color: #1e8449; font-weight: 600; }}
    .status.failed, .status.unstable {{ color: #b03a2e; font-weight: 600; }}
    .status.unverified {{ color: #b9770e; font-weight: 600; }}
  </style>
</head>
<body>
  <h1>Cute-Agent Bench</h1>
  <div class="meta">{escape(payload["runId"])} · {escape(payload["profile"])} · {escape(payload["agent"])}</div>
  <div class="cards">
    <div class="card">Cases: {summary["caseCount"]}</div>
    <div class="card">Attempts: {summary["attemptCount"]}</div>
    <div class="card">Passed: {summary["passed"]}</div>
    <div class="card">Failed: {summary["failed"]}</div>
    <div class="card">Unstable: {summary["unstable"]}</div>
    <div class="card">Unverified: {summary["unverified"]}</div>
    <div class="card">Quality pass@1: {summary["qualityPassAt1"]:.2%}</div>
    <div class="card">SLO pass@1: {summary["sloPassAt1"]:.2%}</div>
    <div class="card">pass@1: {summary["passAt1"]:.2%}</div>
    <div class="card">pass^k: {summary["passAtK"]:.2%}</div>
    <div class="card">Cache hit: {escape(cache_text)}</div>
  </div>
  <p><strong>Baseline regressions:</strong> {escape(regressions)}</p>
  <p><strong>Tool failure classes:</strong> {escape(tool_failure_classes)}</p>
  {benchmark_html}
  {compaction_html}
  <p><strong>Inspect log:</strong> <code>{escape(str(payload.get("inspectLog") or "-"))}</code></p>
  {strata_html}
  {official_scores_html}
  {diagnostic_metrics_html}
  {diagnostic_scores_html}
  <table>
    <thead><tr><th>Case</th><th>Status</th><th>Strict pass</th><th>Quality pass</th><th>SLO pass</th><th>Median tokens</th><th>Median wall ms</th><th>Median first token ms</th><th>Cache hit (samples)</th><th>Failure categories</th><th>Injected tool failures</th><th>Traces</th></tr></thead>
    <tbody>{''.join(rows)}</tbody>
  </table>
</body>
</html>
"""
