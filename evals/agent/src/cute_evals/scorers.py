from __future__ import annotations

import json
import re
import sqlite3
from collections import Counter
from pathlib import Path
from typing import Any

from .framework_scorers import (
    score_agentevals,
    score_deepeval,
    score_ragas,
)
from .models import CaseResult, EvalCase, JudgeConfig, RunTrace, ScoreRecord
from .trajectory_quality import _tool_failure_signal


def _record(
    case: EvalCase,
    name: str,
    value: float | bool | str | None,
    *,
    passed: bool | None,
    unit: str,
    hard_gate: bool,
    status: str = "verified",
    evidence: dict[str, Any] | None = None,
) -> ScoreRecord:
    return ScoreRecord(
        case_id=case.id,
        scorer="deterministic",
        name=name,
        value=value,
        unit=unit,
        passed=passed,
        hard_gate=hard_gate,
        status=status,
        evidence=evidence or {},
    )


def _contains_ordered_sequence(actual: list[str], expected: list[str]) -> bool:
    if not expected:
        return True
    index = 0
    for name in actual:
        if name == expected[index]:
            index += 1
            if index == len(expected):
                return True
    return False


def _tool_sequence_ok(
    actual: list[str],
    expected: list[str],
    *,
    strict: bool,
) -> bool:
    if not expected:
        return True
    return actual == expected if strict else _contains_ordered_sequence(actual, expected)


def _lcs_length(actual: list[str], expected: list[str]) -> int:
    if not actual or not expected:
        return 0
    previous = [0] * (len(expected) + 1)
    for actual_name in actual:
        current = [0] * (len(expected) + 1)
        for index, expected_name in enumerate(expected, start=1):
            if actual_name == expected_name:
                current[index] = previous[index - 1] + 1
            else:
                current[index] = max(previous[index], current[index - 1])
        previous = current
    return previous[-1]


def _route_metrics(
    actual: list[str],
    reference: list[str],
) -> tuple[float | None, float | None, float | None]:
    if not reference:
        return None, None, None
    matched = _lcs_length(actual, reference)
    recall = matched / len(reference)
    precision = matched / len(actual) if actual else 0.0
    f1 = 0.0 if precision + recall == 0 else 2 * precision * recall / (precision + recall)
    return precision, recall, f1


def _subgoal_status(
    subgoals: list[dict[str, Any]],
    *,
    successful_tools: list[str],
    final_text: str,
) -> tuple[int, list[dict[str, Any]]]:
    completed = 0
    evidence: list[dict[str, Any]] = []
    tool_counts = Counter(successful_tools)
    for subgoal in subgoals:
        required_tools = [str(name) for name in (subgoal.get("requiredTools") or [])]
        required_text = [str(value) for value in (subgoal.get("finalContains") or [])]
        raw_minimum = subgoal.get("minSuccessfulTools")
        minimum = (
            int(raw_minimum)
            if raw_minimum is not None
            else (1 if required_tools else 0)
        )
        tools_ok = all(tool_counts[name] >= minimum for name in required_tools)
        text_ok = all(value in final_text for value in required_text)
        passed = tools_ok and text_ok
        if passed:
            completed += 1
        evidence.append(
            {
                "id": str(subgoal.get("id")),
                "passed": passed,
                "requiredTools": required_tools,
                "minSuccessfulTools": minimum,
                "requiredText": required_text,
                "matchedToolCounts": {
                    name: tool_counts[name] for name in required_tools
                },
                "textMatched": text_ok,
            }
        )
    return completed, evidence


def _skill_loads(trace: RunTrace) -> list[dict[str, str]]:
    loads: list[dict[str, str]] = []
    for item in trace.tool_invocations:
        if str(item.get("toolName", "")) != "skill_view":
            continue
        raw_input = item.get("inputJson")
        if isinstance(raw_input, dict):
            arguments = raw_input
        else:
            try:
                decoded = json.loads(str(raw_input or "{}"))
            except json.JSONDecodeError:
                decoded = {}
            arguments = decoded if isinstance(decoded, dict) else {}
        name = arguments.get("name")
        if not isinstance(name, str) or not name.strip():
            continue
        loads.append(
            {
                "name": name.strip(),
                "status": str(item.get("status", "")).upper(),
            }
        )
    return loads


def _sql_from_invocation(item: dict[str, Any]) -> str | None:
    raw_input = item.get("inputJson")
    if isinstance(raw_input, dict):
        arguments = raw_input
    else:
        try:
            decoded = json.loads(str(raw_input or "{}"))
        except json.JSONDecodeError:
            return None
        arguments = decoded if isinstance(decoded, dict) else {}
    sql = arguments.get("sql")
    return sql.strip() if isinstance(sql, str) and sql.strip() else None


def _sqlite_authorizer(action, arg1, arg2, db_name, trigger_name):
    allowed = {
        getattr(sqlite3, "SQLITE_SELECT", 21),
        getattr(sqlite3, "SQLITE_READ", 20),
        getattr(sqlite3, "SQLITE_FUNCTION", 31),
        getattr(sqlite3, "SQLITE_RECURSIVE", 33),
    }
    return sqlite3.SQLITE_OK if action in allowed else sqlite3.SQLITE_DENY


def _value_matches(actual: Any, expected: Any, tolerance: float) -> bool:
    if isinstance(actual, bool) or isinstance(expected, bool):
        return actual == expected
    if isinstance(actual, (int, float)) and isinstance(expected, (int, float)):
        return abs(float(actual) - float(expected)) <= tolerance
    return actual == expected


def _row_matches(actual: list[Any], expected: list[Any], tolerance: float) -> bool:
    return len(actual) == len(expected) and all(
        _value_matches(actual_value, expected_value, tolerance)
        for actual_value, expected_value in zip(actual, expected, strict=True)
    )


def _rows_match(
    actual_rows: list[list[Any]],
    expected_rows: list[list[Any]],
    *,
    tolerance: float,
    ordered: bool,
) -> bool:
    if len(actual_rows) != len(expected_rows):
        return False
    if ordered:
        return all(
            _row_matches(actual, expected, tolerance)
            for actual, expected in zip(actual_rows, expected_rows, strict=True)
        )

    remaining = list(actual_rows)
    for expected in expected_rows:
        match_index = next(
            (
                index
                for index, actual in enumerate(remaining)
                if _row_matches(actual, expected, tolerance)
            ),
            None,
        )
        if match_index is None:
            return False
        remaining.pop(match_index)
    return not remaining


def _read_only_sql(fixture_path: Path, sql: str) -> tuple[list[str], list[list[Any]]]:
    if not re.match(r"(?is)^\s*(select|with)\b", sql):
        raise ValueError("only a single read-only SELECT/CTE statement is allowed")
    if not fixture_path.exists():
        raise FileNotFoundError(f"SQL fixture not found: {fixture_path}")

    connection = sqlite3.connect(":memory:")
    try:
        connection.executescript(fixture_path.read_text(encoding="utf-8"))
        connection.execute("PRAGMA query_only=ON")
        connection.set_authorizer(_sqlite_authorizer)
        cursor = connection.execute(sql)
        columns = [str(item[0]) for item in (cursor.description or [])]
        rows = [list(row) for row in cursor.fetchall()]
        return columns, rows
    finally:
        connection.close()


def score_case(
    case: EvalCase,
    trace: RunTrace,
    trace_path: str,
    *,
    profile: str = "pr",
    require_judge: bool = False,
    judge: JudgeConfig | None = None,
    judge_cache_dir: Path | None = None,
    attempt: int = 1,
) -> CaseResult:
    scores: list[ScoreRecord] = []
    final_text = trace.final_text
    missing = [value for value in case.expected.final_contains if value not in final_text]
    missing_any = (
        []
        if not case.expected.final_contains_any
        or any(value in final_text for value in case.expected.final_contains_any)
        else list(case.expected.final_contains_any)
    )
    forbidden = [
        value for value in case.expected.final_not_contains if value in final_text
    ]
    tool_failed = any(
        _tool_failure_signal(item) is not None
        for item in trace.tool_invocations
        if isinstance(item, dict)
    )
    task_ok = (
        trace.status == "SUCCESS"
        and not tool_failed
        and not missing
        and not missing_any
        and not forbidden
    )
    scores.append(
        _record(
            case,
            "task_success",
            task_ok,
            passed=task_ok,
            unit="boolean",
            hard_gate=case.hard_gate,
            evidence={
                "runStatus": trace.status,
                "missingFinalContains": missing,
                "missingFinalContainsAny": missing_any,
                "forbiddenFinalContains": forbidden,
            },
        )
    )

    if case.compaction:
        mode = str(case.compaction.get("mode") or "compressed")
        events = list(trace.context_events)
        successful = [
            event
            for event in events
            if str(event.get("type")) == "compaction"
            and str(event.get("status")) == "success"
        ]
        if mode == "uncompressed-control":
            control_clean = not events
            scores.append(
                _record(
                    case,
                    "compaction_control_clean",
                    control_clean,
                    passed=control_clean,
                    unit="boolean",
                    hard_gate=case.hard_gate,
                    evidence={"contextEventCount": len(events)},
                )
            )
        else:
            triggered = bool(successful)
            scores.append(
                _record(
                    case,
                    "compaction_triggered",
                    triggered,
                    passed=triggered,
                    unit="boolean",
                    hard_gate=case.hard_gate,
                    evidence={
                        "contextEventCount": len(events),
                        "strategies": [event.get("strategy") for event in successful],
                    },
                )
            )
            if successful:
                event = successful[-1]
                before = event.get("beforeTokens")
                after = event.get("afterTokens")
                ratio = (
                    float(before) / float(after)
                    if isinstance(before, (int, float))
                    and isinstance(after, (int, float))
                    and float(after) > 0
                    else None
                )
                scores.append(
                    _record(
                        case,
                        "compression_ratio",
                        ratio,
                        passed=None,
                        unit="ratio",
                        hard_gate=False,
                        status="verified" if ratio is not None else "unverified",
                        evidence={
                            "beforeTokens": before,
                            "afterTokens": after,
                            "eventId": event.get("eventId"),
                        },
                    )
                )
                summary_hash = event.get("summaryHash")
                summary_hash_ok = bool(summary_hash)
                scores.append(
                    _record(
                        case,
                        "summary_hash_present",
                        summary_hash_ok,
                        passed=summary_hash_ok,
                        unit="boolean",
                        hard_gate=case.hard_gate,
                        evidence={"eventId": event.get("eventId")},
                    )
                )

    tool_names = trace.tool_names()
    missing_tools = [name for name in case.expected.required_tools if name not in tool_names]
    forbidden_tools = [name for name in case.expected.forbidden_tools if name in tool_names]
    expected_sequence = case.expected.required_tool_sequence or case.expected.required_tools
    sequence_ok = _tool_sequence_ok(
        tool_names,
        expected_sequence,
        strict=case.expected.strict_tool_sequence,
    )
    tool_sequence_ok = not missing_tools and not forbidden_tools and sequence_ok
    scores.append(
        _record(
            case,
            "tool_sequence",
            tool_names,
            passed=tool_sequence_ok,
            unit="tool_names",
            hard_gate=case.hard_gate,
            evidence={
                "expectedSequence": expected_sequence,
                "strict": case.expected.strict_tool_sequence,
                "sequenceMatched": sequence_ok,
                "missingTools": missing_tools,
                "forbiddenTools": forbidden_tools,
            },
        )
    )

    route_precision, route_recall, route_f1 = _route_metrics(
        tool_names,
        case.expected.reference_tool_sequence,
    )
    if route_f1 is not None:
        for name, value in (
            ("route_precision", route_precision),
            ("route_recall", route_recall),
            ("route_f1", route_f1),
        ):
            scores.append(
                _record(
                    case,
                    name,
                    value,
                    passed=None,
                    unit="ratio",
                    hard_gate=False,
                    evidence={
                        "actualTools": tool_names,
                        "referenceTools": case.expected.reference_tool_sequence,
                    },
                )
            )

    if case.expected.subgoals:
        completed, subgoal_evidence = _subgoal_status(
            case.expected.subgoals,
            successful_tools=trace.successful_tool_names(),
            final_text=final_text,
        )
        progress = completed / len(case.expected.subgoals)
        scores.append(
            _record(
                case,
                "subgoal_progress",
                progress,
                passed=progress == 1.0,
                unit="ratio",
                hard_gate=case.hard_gate,
                evidence={
                    "completed": completed,
                    "total": len(case.expected.subgoals),
                    "subgoals": subgoal_evidence,
                },
            )
        )

    success_rate = trace.tool_success_rate()
    min_success_rate = case.expected.min_tool_success_rate
    success_rate_ok = (
        min_success_rate is None
        or not case.expected.required_tools
        or (success_rate is not None and success_rate >= min_success_rate)
    )
    scores.append(
        _record(
            case,
            "tool_success_rate",
            success_rate,
            passed=success_rate_ok,
            unit="ratio",
            hard_gate=(
                case.hard_gate
                and min_success_rate is not None
                and bool(case.expected.required_tools)
            ),
            status=(
                "verified"
                if success_rate is not None
                else (
                    "not_applicable"
                    if not case.expected.required_tools
                    else "unverified"
                )
            ),
            evidence={"minimum": min_success_rate},
        )
    )

    if case.expected.require_tool_recovery:
        recovered = trace.recovered_after_tool_failure()
        scores.append(
            _record(
                case,
                "tool_recovery",
                recovered,
                passed=recovered,
                unit="boolean",
                hard_gate=case.hard_gate,
            )
        )

    skill_loads = _skill_loads(trace)
    attempted_skills = [load["name"] for load in skill_loads]
    successful_skills = [
        load["name"] for load in skill_loads if load["status"] == "SUCCESS"
    ]
    if (
        case.suite == "skill-sim"
        or case.expected.required_skill is not None
        or case.expected.forbidden_skills
        or case.expected.max_skill_loads is not None
    ):
        required_skill = case.expected.required_skill
        forbidden_skills = sorted(set(case.expected.forbidden_skills))
        missing_skill = (
            required_skill is not None
            and required_skill not in successful_skills
        )
        forbidden_hits = sorted(set(attempted_skills) & set(forbidden_skills))
        selection_ok = not missing_skill and not forbidden_hits
        scores.append(
            _record(
                case,
                "skill_selection",
                attempted_skills,
                passed=selection_ok,
                unit="skill_names",
                hard_gate=case.hard_gate,
                evidence={
                    "requiredSkill": required_skill,
                    "forbiddenSkills": forbidden_skills,
                    "attemptedSkills": attempted_skills,
                    "successfulSkills": successful_skills,
                    "forbiddenHits": forbidden_hits,
                },
            )
        )

    if case.expected.max_skill_loads is not None:
        load_count = len(skill_loads)
        load_budget_ok = load_count <= case.expected.max_skill_loads
        scores.append(
            _record(
                case,
                "skill_load_count",
                load_count,
                passed=load_budget_ok,
                unit="count",
                hard_gate=case.hard_gate,
                evidence={"maxSkillLoads": case.expected.max_skill_loads},
            )
        )

    repeated = trace.repeated_tool_calls()
    scores.append(
        _record(
            case,
            "redundant_tool_calls",
            repeated,
            passed=repeated == 0,
            unit="count",
            hard_gate=False,
        )
    )

    step_count = trace.max_step_no()
    step_ok = case.expected.max_steps is None or step_count <= case.expected.max_steps
    scores.append(
        _record(
            case,
            "max_steps",
            step_count,
            passed=step_ok,
            unit="steps",
            hard_gate=case.hard_gate,
            evidence={"maxSteps": case.expected.max_steps},
        )
    )

    budget_hard_gate = profile in {"nightly", "release"} and case.hard_gate
    token_ok = (
        case.budgets.max_total_tokens is None
        or trace.total_tokens is None
        or trace.total_tokens <= case.budgets.max_total_tokens
    )
    scores.append(
        _record(
            case,
            "token_budget",
            trace.total_tokens,
            passed=token_ok,
            unit="tokens",
            hard_gate=budget_hard_gate and case.budgets.max_total_tokens is not None,
            status="verified" if trace.total_tokens is not None else "unverified",
            evidence={"maxTotalTokens": case.budgets.max_total_tokens},
        )
    )

    wall_ok = (
        case.budgets.max_wall_ms is None
        or trace.duration_ms is None
        or trace.duration_ms <= case.budgets.max_wall_ms
    )
    scores.append(
        _record(
            case,
            "wall_time",
            trace.duration_ms,
            passed=wall_ok,
            unit="ms",
            hard_gate=budget_hard_gate and case.budgets.max_wall_ms is not None,
            status="verified" if trace.duration_ms is not None else "unverified",
            evidence={"maxWallMs": case.budgets.max_wall_ms},
        )
    )

    first_token_ms = trace.first_token_ms()
    scores.append(
        _record(
            case,
            "first_token_latency",
            first_token_ms,
            passed=None if first_token_ms is None else True,
            unit="ms",
            hard_gate=False,
            status="verified" if first_token_ms is not None else "unverified",
            evidence={"sampleCount": len(trace.first_token_samples())},
        )
    )

    artifact_count = len(trace.artifacts)
    artifact_ok = artifact_count >= case.expected.min_artifacts
    scores.append(
        _record(
            case,
            "artifact_count",
            artifact_count,
            passed=artifact_ok,
            unit="count",
            hard_gate=case.hard_gate and case.expected.min_artifacts > 0,
            evidence={"minimum": case.expected.min_artifacts},
        )
    )

    if case.expected.required_citations:
        missing_citations = [
            citation
            for citation in case.expected.required_citations
            if citation not in final_text
        ]
        coverage = (
            len(case.expected.required_citations) - len(missing_citations)
        ) / len(case.expected.required_citations)
        scores.append(
            _record(
                case,
                "citation_coverage",
                coverage,
                passed=not missing_citations,
                unit="ratio",
                hard_gate=case.hard_gate,
                evidence={
                    "requiredCitations": case.expected.required_citations,
                    "missingCitations": missing_citations,
                },
            )
        )

    if case.suite == "data-core":
        sql_calls = [
            item for item in trace.tool_invocations if item.get("toolName") == "sql_query"
        ]
        sql = _sql_from_invocation(sql_calls[-1]) if sql_calls else None
        oracle = case.expected.sql_oracle
        fixture_path = (
            Path(case.source_path).parent / str(oracle["fixture"])
            if oracle and oracle.get("fixture")
            else None
        )
        actual_columns: list[str] = []
        actual_rows: list[list[Any]] = []
        sql_error: str | None = None
        sql_ok = bool(sql_calls) and sql is not None and all(
            str(item.get("status", "")).upper() == "SUCCESS" for item in sql_calls
        )
        if sql_ok and fixture_path is not None:
            try:
                actual_columns, actual_rows = _read_only_sql(fixture_path, sql)
            except Exception as exc:  # noqa: BLE001 - SQL errors are evaluation facts.
                sql_ok = False
                sql_error = f"{type(exc).__name__}: {exc}"
        scores.append(
            _record(
                case,
                "sql_execution",
                sql_ok,
                passed=sql_ok,
                unit="boolean",
                hard_gate=case.hard_gate,
                evidence={
                    "sqlCallCount": len(sql_calls),
                    "actualSql": sql,
                    "actualColumns": actual_columns,
                    "actualRows": actual_rows,
                    "error": sql_error,
                },
            )
        )
        if oracle is not None:
            expected_columns = [str(value) for value in oracle.get("expectedColumns", [])]
            expected_rows = [
                list(row) for row in (oracle.get("expectedRows") or [])
            ]
            tolerance = float(oracle.get("numericTolerance", 0.0))
            ordered = bool(oracle.get("ordered", False))
            result_ok = (
                sql_ok
                and (not expected_columns or actual_columns == expected_columns)
                and _rows_match(
                    actual_rows,
                    expected_rows,
                    tolerance=tolerance,
                    ordered=ordered,
                )
            )
            scores.append(
                _record(
                    case,
                    "sql_result",
                    result_ok,
                    passed=result_ok,
                    unit="boolean",
                    hard_gate=case.hard_gate,
                    evidence={
                        "expectedColumns": expected_columns,
                        "actualColumns": actual_columns,
                        "expectedRows": expected_rows,
                        "actualRows": actual_rows,
                        "numericTolerance": tolerance,
                        "ordered": ordered,
                    },
                )
            )

    scores.extend(score_agentevals(case, trace, judge=judge))
    scores.append(
        score_deepeval(
            case,
            trace,
            judge=judge,
            cache_dir=judge_cache_dir,
            require_judge=require_judge,
        )
    )
    scores.extend(
        score_ragas(
            case,
            trace,
            judge=judge,
            require_judge=require_judge,
        )
    )

    return CaseResult(
        case=case,
        trace=trace,
        scores=scores,
        trace_path=trace_path,
        attempt=attempt,
    )
