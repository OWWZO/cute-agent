from __future__ import annotations

import argparse
import json
import shutil
import traceback
from pathlib import Path
from typing import Any

from .experiment import (
    CellResult,
    load_cell_case,
    now_iso,
    write_json_atomic,
)
from .java_runner import EvalRunnerError, run_java_cases
from .scorers import score_case


def _tool_failure_count(trace) -> int:
    return sum(
        1
        for item in trace.tool_invocations
        if str(item.get("status", "")).upper() != "SUCCESS"
    )


def _status_for(case_result) -> tuple[str, str, float]:
    if case_result.unverified:
        return "unverified", "oracle", 0.0
    if case_result.passed:
        return "passed", "none", 1.0
    tool_failures = _tool_failure_count(case_result.trace)
    if tool_failures:
        return "failed", "tool", _task_score(case_result)
    if case_result.trace.status != "SUCCESS":
        return "model_error", "model", 0.0
    return "failed", "model", _task_score(case_result)


def _task_score(case_result) -> float:
    hard_scores = [
        score
        for score in case_result.scores
        if score.hard_gate and score.passed is not None
    ]
    if not hard_scores:
        return 1.0 if case_result.passed else 0.0
    return sum(1 for score in hard_scores if score.passed) / len(hard_scores)


def _error_result(
    payload: dict[str, Any],
    *,
    started_at: str,
    status: str,
    error: str,
) -> CellResult:
    ownership = (
        "environment"
        if status == "infra_error"
        else ("adapter" if status == "adapter_error" else "oracle")
    )
    return CellResult(
        cell_key=str(payload["cellKey"]),
        experiment_id=str(payload["experimentId"]),
        experiment_spec_hash=str(payload["experimentSpecHash"]),
        arm=str(payload["arm"]),
        model=str(payload["model"]),
        case_id=str(payload["caseId"]),
        suite=str(payload.get("suite") or ""),
        repetition=int(payload["repetition"]),
        status=status,
        task_score=0.0,
        hard_gate_passed=False,
        in_accuracy_denominator=False,
        failure_ownership=ownership,
        metrics={
            "apiTurns": None,
            "toolCalls": None,
            "toolErrors": None,
            "retries": 0,
            "totalTokens": None,
            "wallMs": None,
            "firstTokenMs": None,
            "estimatedCost": None,
        },
        scores=[],
        retry_history=[],
        started_at=started_at,
        finished_at=now_iso(),
        source_fingerprint=str(payload["sourceFingerprint"]),
        error=error,
    )


def execute_cell(
    payload: dict[str, Any],
    work_dir: Path,
    *,
    profile: str,
    java_mode: str,
    timeout_seconds: int,
    require_judge: bool = False,
) -> CellResult:
    started_at = now_iso()
    work_dir.mkdir(parents=True, exist_ok=True)
    run_dir = work_dir / "run"
    try:
        case = load_cell_case(payload)
        traces = run_java_cases(
            [case],
            run_dir,
            mode=java_mode,
            profile=profile,
            model_id=str(payload["model"]),
            build=False,
            timeout_seconds=timeout_seconds,
        )
        trace = traces.get(case.id)
        if trace is None:
            raise EvalRunnerError(
                "adapter",
                f"Java runner returned no trace for {case.id}",
            )
        case_result = score_case(
            case,
            trace,
            "trace.json",
            profile=profile,
            require_judge=require_judge,
            judge=None,
            judge_cache_dir=None,
            attempt=int(payload["repetition"]),
        )
        status, ownership, task_score = _status_for(case_result)
        source_trace = run_dir / "traces" / case.id / "trace.json"
        if source_trace.exists():
            shutil.copy2(source_trace, work_dir / "trace.json")
        write_json_atomic(
            work_dir / "transcript.json",
            {
                "schemaVersion": "1.0",
                "caseId": case.id,
                "run": trace.raw.get("run") or {},
                "llmInvocations": trace.llm_invocations,
                "toolInvocations": trace.tool_invocations,
                "artifacts": trace.artifacts,
            },
        )
        result = CellResult(
            cell_key=str(payload["cellKey"]),
            experiment_id=str(payload["experimentId"]),
            experiment_spec_hash=str(payload["experimentSpecHash"]),
            arm=str(payload["arm"]),
            model=str(payload["model"]),
            case_id=case.id,
            suite=case.suite,
            repetition=int(payload["repetition"]),
            status=status,
            task_score=task_score,
            hard_gate_passed=case_result.passed and not case_result.unverified,
            in_accuracy_denominator=status in {"passed", "failed", "model_error"},
            failure_ownership=ownership,
            metrics={
                "apiTurns": trace.max_step_no() or None,
                "toolCalls": len(trace.tool_invocations),
                "toolErrors": _tool_failure_count(trace),
                "retries": 0,
                "totalTokens": trace.total_tokens,
                "wallMs": trace.duration_ms,
                "firstTokenMs": trace.first_token_ms(),
                "estimatedCost": None,
            },
            scores=[score.to_dict() for score in case_result.scores],
            retry_history=[],
            started_at=started_at,
            finished_at=now_iso(),
            source_fingerprint=str(payload["sourceFingerprint"]),
            trace_path="trace.json" if (work_dir / "trace.json").exists() else None,
            transcript_path="transcript.json",
            artifact_refs=list(trace.artifacts),
        )
    except EvalRunnerError as exc:
        status = "infra_error" if exc.category == "timeout" else "adapter_error"
        result = _error_result(
            payload,
            started_at=started_at,
            status=status,
            error=str(exc),
        )
    except Exception as exc:  # noqa: BLE001 - classify child boundary failures
        result = _error_result(
            payload,
            started_at=started_at,
            status="oracle_error",
            error=f"{type(exc).__name__}: {exc}\n{traceback.format_exc()}",
        )
    write_json_atomic(work_dir / "result.json", result.to_dict())
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cute-evals-ab-cell")
    parser.add_argument("--cell", required=True)
    parser.add_argument("--work-dir", required=True)
    parser.add_argument("--profile", default="pr")
    parser.add_argument("--java-mode", choices=["fixture", "live"], default="fixture")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--require-judge", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    payload = json.loads(Path(args.cell).read_text(encoding="utf-8"))
    result = execute_cell(
        payload,
        Path(args.work_dir).resolve(),
        profile=args.profile,
        java_mode=args.java_mode,
        timeout_seconds=args.timeout,
        require_judge=args.require_judge,
    )
    raise SystemExit(0 if result.status not in {"infra_error", "adapter_error", "oracle_error"} else 1)


if __name__ == "__main__":
    main()
