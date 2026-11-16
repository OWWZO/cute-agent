from __future__ import annotations

import json
from dataclasses import asdict
from pathlib import Path
from typing import Any

import jsonschema

from ..models import CaseResult, EvalCase, RunTrace, ScoreRecord
from ..scorers import score_case
from .workspace import WorkspaceCheckOutcome


def _workspace_sync_value(value: Any) -> dict[str, Any] | None:
    if isinstance(value, str):
        text = value.strip()
        if not text or (
            "workspace_sync" not in text and "workspaceSync" not in text
        ):
            return None
        try:
            return _workspace_sync_value(json.loads(text))
        except json.JSONDecodeError:
            return None
    if isinstance(value, dict):
        for key in ("workspace_sync", "workspaceSync"):
            candidate = value.get(key)
            if isinstance(candidate, dict):
                return candidate
        for key in ("llmData", "payload", "data", "result", "output"):
            candidate = _workspace_sync_value(value.get(key))
            if candidate is not None:
                return candidate
        for candidate in value.values():
            found = _workspace_sync_value(candidate)
            if found is not None:
                return found
    if isinstance(value, list):
        for item in value:
            candidate = _workspace_sync_value(item)
            if candidate is not None:
                return candidate
    return None


def _workspace_sync_summary(trace: RunTrace) -> dict[str, Any] | None:
    for invocation in trace.tool_invocations:
        sync = _workspace_sync_value(invocation)
        if sync is None:
            continue
        return {
            "backend": sync.get("backend"),
            "status": sync.get("status"),
            "uploaded": list(sync.get("uploaded") or []),
            "downloaded": list(sync.get("downloaded") or []),
            "deletedLocal": list(sync.get("deletedLocal") or []),
            "deletedRemote": list(sync.get("deletedRemote") or []),
            "ignoredDeletes": list(sync.get("ignoredDeletes") or []),
            "conflicts": list(sync.get("conflicts") or []),
            "errorCode": sync.get("errorCode"),
        }
    return None


def _schema_path() -> Path:
    candidates = [
        Path(__file__).resolve().parents[3] / "schemas" / "compare-trace.schema.json",
        Path(__file__).resolve().parents[1] / "schemas" / "compare-trace.schema.json",
    ]
    for path in candidates:
        if path.exists():
            return path
    raise FileNotFoundError("compare-trace.schema.json was not found")


def normalize_trace(
    case: EvalCase,
    trace: RunTrace,
    *,
    agent: str,
    agent_version: str | None,
    workspace: Path,
    workspace_hash: str,
    dataset_hash: str,
    mode: str,
    environment: dict[str, Any] | None = None,
) -> RunTrace:
    payload = json.loads(json.dumps(trace.raw, ensure_ascii=False))
    current_environment = dict(payload.get("environment") or {})
    current_environment.update(environment or {})
    current_environment.update(
        {
            "agent": agent,
            "agentVersion": agent_version,
            "workspace": str(workspace),
            "workspaceHash": workspace_hash,
            "datasetHash": dataset_hash,
            "mode": mode,
            "networkPolicyEnforced": False,
            "budgets": asdict(case.budgets),
        }
    )
    workspace_sync = _workspace_sync_summary(trace)
    if workspace_sync is not None:
        current_environment["workspaceSync"] = workspace_sync
    payload["environment"] = current_environment
    payload.setdefault(
        "producer",
        {
            "agent": agent,
            "runtimeVersion": agent_version,
        },
    )
    producer = dict(payload.get("producer") or {})
    producer.setdefault("agent", agent)
    producer.setdefault("runtimeVersion", agent_version)
    payload["producer"] = producer
    payload["schemaVersion"] = "1.0"
    payload.setdefault("llmInvocations", [])
    payload.setdefault("toolInvocations", [])
    payload.setdefault("artifacts", [])
    return RunTrace.from_dict(payload)


def write_compare_trace(trace: RunTrace, path: Path) -> None:
    schema = json.loads(_schema_path().read_text(encoding="utf-8"))
    try:
        jsonschema.validate(trace.raw, schema)
    except jsonschema.ValidationError as exc:
        location = "/".join(str(part) for part in exc.absolute_path)
        raise ValueError(
            f"compare trace validation failed at {location or '<root>'}: {exc.message}"
        ) from exc
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(trace.raw, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def score_compare_case(
    case: EvalCase,
    trace: RunTrace,
    trace_path: str,
    *,
    attempt: int,
    workspace_check: WorkspaceCheckOutcome | None,
) -> CaseResult:
    result = score_case(
        case,
        trace,
        trace_path,
        profile="nightly",
        require_judge=False,
        judge=None,
        judge_cache_dir=None,
        attempt=attempt,
    )
    adapter = dict((trace.raw.get("environment") or {}).get("adapter") or {})
    if adapter.get("stepCountVerified") is False:
        for score in result.scores:
            if score.name != "max_steps":
                continue
            score.value = None
            score.passed = None
            score.hard_gate = False
            score.status = "unverified"
            score.evidence = {
                **score.evidence,
                "reason": "Agent adapter does not expose exact model-step counts.",
            }
    if workspace_check is not None:
        result.scores.append(
            ScoreRecord(
                case_id=case.id,
                scorer="comparison.workspace",
                name="workspace_checks",
                value=workspace_check.passed,
                unit="boolean",
                passed=workspace_check.passed,
                hard_gate=case.hard_gate,
                status=workspace_check.status,
                evidence=workspace_check.evidence,
            )
        )
    workspace_sync = _workspace_sync_summary(trace)
    if workspace_sync is not None:
        sync_passed = not workspace_sync.get("conflicts") and (
            workspace_sync.get("status") in {None, "ok"}
        )
        result.scores.append(
            ScoreRecord(
                case_id=case.id,
                scorer="comparison.workspace_sync",
                name="workspace_sync",
                value=workspace_sync.get("status"),
                unit="status",
                passed=sync_passed,
                hard_gate=case.hard_gate,
                status="verified",
                evidence=workspace_sync,
            )
        )
    tool_call_budget = case.budgets.max_tool_calls
    if tool_call_budget is not None:
        tool_calls = len(trace.tool_invocations)
        result.scores.append(
            ScoreRecord(
                case_id=case.id,
                scorer="comparison.budget",
                name="tool_call_budget",
                value=tool_calls,
                unit="count",
                passed=tool_calls <= tool_call_budget,
                hard_gate=case.hard_gate,
                status="verified",
                evidence={"maxToolCalls": tool_call_budget},
            )
        )
    return result


def unavailable_result(
    case: EvalCase,
    *,
    agent: str,
    error: str,
    attempt: int,
    trace_path: str,
) -> CaseResult:
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "requestId": f"{agent}-{case.id}-attempt-{attempt}",
                "status": "UNAVAILABLE",
                "finalSummaryText": "",
                "totalTokens": None,
                "durationMs": None,
                "errorCode": "ADAPTER_UNAVAILABLE",
                "errorMessage": error,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {"agent": agent},
            "raw": {"adapterError": error},
        }
    )
    return CaseResult(
        case=case,
        trace=trace,
        scores=[
            ScoreRecord(
                case_id=case.id,
                scorer="comparison.adapter",
                name="adapter_status",
                value="unavailable",
                unit="status",
                passed=None,
                hard_gate=True,
                status="unverified",
                evidence={"agent": agent, "error": error},
            )
        ],
        trace_path=trace_path,
        attempt=attempt,
    )
