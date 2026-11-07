from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any

import jsonschema
import yaml

from .case_loader import CASE_SCHEMA_PATH, load_case_file, schema_path
from .java_runner import compile_java_fixture_runner, run_java_cases
from .models import EvalCase, JudgeConfig, RunTrace
from .report import build_report
from .scorers import score_case

DRAFT_SCHEMA_PATH = schema_path("case-draft.schema.json")
TRACE_SCHEMA_PATH = schema_path("trace.schema.json")
FAILURE_CLASSES = {"model", "tool", "environment", "persistence", "unknown"}
EXPECTED_ORACLE_KEYS = {
    "finalContains",
    "finalNotContains",
    "maxSteps",
    "requiredTools",
    "requiredToolSequence",
    "strictToolSequence",
    "forbiddenTools",
    "requireToolRecovery",
    "minToolSuccessRate",
    "requiredSkill",
    "forbiddenSkills",
    "maxSkillLoads",
    "requiredCitations",
    "noEvidence",
    "minArtifacts",
    "subgoals",
    "sqlOracle",
}


def load_trace(path: Path) -> RunTrace:
    payload = json.loads(path.read_text(encoding="utf-8"))
    schema = json.loads(TRACE_SCHEMA_PATH.read_text(encoding="utf-8"))
    jsonschema.validate(payload, schema)
    return RunTrace.from_dict(payload)


def infer_failure_class(trace: RunTrace) -> str:
    run = trace.raw.get("run") or {}
    status = str(run.get("status") or trace.status).upper()
    errors = " ".join(
        str(item.get("errorMsg") or "")
        for item in trace.tool_invocations
        if str(item.get("status", "")).upper() in {"FAILED", "TIMEOUT"}
    )
    combined = f"{run.get('errorMsg') or ''} {errors}".lower()
    if status not in {"FAILED", "TIMEOUT", "STOPPED"}:
        return "unknown"
    if any(word in combined for word in ("persist", "database", "mysql", "ledger", "commit")):
        return "persistence"
    if any(word in combined for word in ("tool", "timeout", "429", "permission", "malformed")):
        return "tool"
    if any(word in combined for word in ("environment", "container", "network", "browser")):
        return "environment"
    return "model"


def build_case_draft(
    trace: RunTrace,
    *,
    trace_path: Path,
    failure_class: str | None = None,
) -> dict[str, Any]:
    run = trace.raw.get("run") or {}
    query = str(run.get("queryText") or "").strip()
    if not query:
        raise ValueError("trace has no run.queryText; cannot build a badcase draft")

    category = failure_class or infer_failure_class(trace)
    if category not in FAILURE_CLASSES:
        raise ValueError(f"unsupported failure class: {category}")

    request_id = str(run.get("requestId") or trace.request_id or "unknown-run")
    safe_id = re.sub(r"[^A-Za-z0-9._-]+", "-", request_id).strip("-") or "unknown-run"
    trace_ref = f"{trace_path.parent.name}/{trace_path.name}"
    tool_sequence = trace.tool_names()
    failed_tools = [
        {
            "toolName": str(item.get("toolName") or ""),
            "status": str(item.get("status") or "UNKNOWN"),
            "errorMsg": str(item.get("errorMsg") or ""),
        }
        for item in trace.tool_invocations
        if str(item.get("status", "")).upper() in {"FAILED", "TIMEOUT"}
    ]

    return {
        "id": f"private.{safe_id}.{category}",
        "suite": "private-badcase",
        "version": 1,
        "hardGate": False,
        "status": "draft",
        "manualReviewRequired": True,
        "source": {
            "trace": trace_ref,
            "requestId": request_id,
            "sessionId": str(run.get("sessionId") or ""),
            "failureClass": category,
        },
        "input": {"message": query},
        "observed": {
            "runStatus": str(run.get("status") or trace.status),
            "errorCode": run.get("errorCode"),
            "errorMsg": run.get("errorMsg"),
            "toolSequence": tool_sequence,
            "failedTools": failed_tools,
            "finalText": trace.final_text,
            "totalTokens": trace.total_tokens,
            "durationMs": trace.duration_ms,
            "firstTokenMs": trace.first_token_ms(),
            "toolSuccessRate": trace.tool_success_rate(),
            "recoveredAfterToolFailure": trace.recovered_after_tool_failure(),
            "artifactCount": len(trace.artifacts),
        },
        "environment": trace.raw.get("environment") or {},
        "expected": {},
        "budgets": {
            "maxTotalTokens": None,
            "maxWallMs": None,
        },
        "script": [],
        "replay": {
            "mode": "badcase-regression",
            "oracleReviewed": False,
            "baselineTrace": trace_ref,
            "counterfactualModel": None,
        },
    }


def write_case_draft(
    trace_path: Path,
    output_path: Path,
    *,
    failure_class: str | None = None,
) -> dict[str, Any]:
    trace = load_trace(trace_path)
    draft = build_case_draft(trace, trace_path=trace_path, failure_class=failure_class)
    schema = json.loads(DRAFT_SCHEMA_PATH.read_text(encoding="utf-8"))
    jsonschema.validate(draft, schema)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        yaml.safe_dump(draft, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )
    return draft


def approve_case_draft(
    draft_path: Path,
    output_path: Path,
    *,
    reviewer: str,
    notes: str | None = None,
    hard_gate: bool = True,
    force: bool = False,
) -> dict[str, Any]:
    draft_path = draft_path.resolve()
    output_path = output_path.resolve()
    if not reviewer.strip():
        raise ValueError("reviewer is required")
    draft = yaml.safe_load(draft_path.read_text(encoding="utf-8"))
    if not isinstance(draft, dict):
        raise TypeError(f"badcase draft must be a mapping: {draft_path}")
    jsonschema.validate(
        draft,
        json.loads(DRAFT_SCHEMA_PATH.read_text(encoding="utf-8")),
    )
    if not (draft.get("replay") or {}).get("oracleReviewed"):
        raise ValueError("badcase oracle must be reviewed before approval")
    expected = draft.get("expected") or {}
    if not EXPECTED_ORACLE_KEYS.intersection(expected):
        raise ValueError(
            "badcase expected must contain at least one deterministic oracle field"
        )
    if output_path.exists() and output_path != draft_path and not force:
        raise FileExistsError(
            f"approved badcase already exists: {output_path}; use --force to replace"
        )
    approved = json.loads(json.dumps(draft, ensure_ascii=False))
    approved["hardGate"] = bool(hard_gate)
    approved["status"] = "approved"
    approved["manualReviewRequired"] = False
    approved["approved"] = {
        "by": reviewer.strip(),
        "at": datetime.now().isoformat(timespec="milliseconds"),
        "notes": notes,
    }
    approved["replay"]["oracleReviewed"] = True
    jsonschema.validate(
        approved,
        json.loads(CASE_SCHEMA_PATH.read_text(encoding="utf-8")),
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        yaml.safe_dump(approved, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )
    load_case_file(output_path, require_script=False)
    return approved


def replay_trace(
    case_path: Path,
    trace_path: Path,
    output_dir: Path,
    *,
    profile: str = "pr",
    judge: JudgeConfig | None = None,
    require_judge: bool = False,
) -> dict[str, Any]:
    case = load_case_file(case_path, require_script=False)
    trace = load_trace(trace_path)
    result = score_case(
        case,
        trace,
        str(trace_path),
        profile=profile,
        require_judge=require_judge,
        judge=judge,
        judge_cache_dir=None,
        attempt=1,
    )
    return build_report(
        profile="trace-replay",
        agent="cute-agent",
        cases=[result],
        output_dir=output_dir,
        environment={
            "mode": "trace-replay",
            "casePath": str(case_path),
            "tracePath": str(trace_path),
            "judgeModel": judge.model if judge else None,
        },
    )


def replay_trace_set(
    cases: list[EvalCase],
    trace_root: Path,
    output_dir: Path,
    *,
    judge: JudgeConfig | None = None,
    require_judge: bool = False,
) -> dict[str, Any]:
    results = []
    for case in cases:
        if not case.baseline_trace:
            raise ValueError(f"case has no replay.baselineTrace: {case.source_path}")
        trace_path = (trace_root / case.baseline_trace).resolve()
        trace = load_trace(trace_path)
        results.append(
            score_case(
                case,
                trace,
                str(trace_path),
                profile="trace-replay",
                require_judge=require_judge,
                judge=judge,
                judge_cache_dir=None,
                attempt=1,
            )
        )
    return build_report(
        profile="trace-replay",
        agent="cute-agent",
        cases=results,
        output_dir=output_dir,
        environment={
            "mode": "trace-replay",
            "suite": "private-badcase",
            "judgeModel": judge.model if judge else None,
        },
    )


def compare_runs(baseline: RunTrace, candidate: RunTrace) -> dict[str, Any]:
    def snapshot(trace: RunTrace) -> dict[str, Any]:
        return {
            "status": trace.status,
            "finalText": trace.final_text,
            "toolSequence": trace.tool_names(),
            "toolSuccessRate": trace.tool_success_rate(),
            "recoveredAfterToolFailure": trace.recovered_after_tool_failure(),
            "repeatedToolCalls": trace.repeated_tool_calls(),
            "totalTokens": trace.total_tokens,
            "durationMs": trace.duration_ms,
            "firstTokenMs": trace.first_token_ms(),
            "artifactCount": len(trace.artifacts),
            "environment": trace.raw.get("environment") or {},
        }

    before = snapshot(baseline)
    after = snapshot(candidate)
    numeric_deltas = {}
    for key in ("toolSuccessRate", "totalTokens", "durationMs", "firstTokenMs"):
        old = before[key]
        new = after[key]
        numeric_deltas[key] = (
            None if old is None or new is None else new - old
        )
    return {
        "baseline": before,
        "candidate": after,
        "changed": {
            "status": before["status"] != after["status"],
            "finalText": before["finalText"] != after["finalText"],
            "toolSequence": before["toolSequence"] != after["toolSequence"],
            "recoveredAfterToolFailure": (
                before["recoveredAfterToolFailure"]
                != after["recoveredAfterToolFailure"]
            ),
            "environment": before["environment"] != after["environment"],
        },
        "deltas": numeric_deltas,
    }


def counterfactual_replay(
    case_path: Path,
    baseline_trace_path: Path,
    output_dir: Path,
    *,
    mode: str = "live",
    timeout_seconds: int = 600,
) -> dict[str, Any]:
    case = load_case_file(case_path, require_script=False)
    baseline = load_trace(baseline_trace_path)
    compile_java_fixture_runner()
    candidate_dir = output_dir / "candidate"
    traces = run_java_cases(
        [case],
        candidate_dir,
        mode=mode,
        profile="nightly",
        build=False,
        timeout_seconds=timeout_seconds,
    )
    candidate = traces[case.id]
    comparison = compare_runs(baseline, candidate)
    comparison["caseId"] = case.id
    comparison["mode"] = "counterfactual-replay"
    comparison["runnerMode"] = mode
    comparison["baselineTrace"] = str(baseline_trace_path)
    comparison["candidateTrace"] = str(candidate_dir / "traces" / case.id / "trace.json")
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "counterfactual.json").write_text(
        json.dumps(comparison, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return comparison
