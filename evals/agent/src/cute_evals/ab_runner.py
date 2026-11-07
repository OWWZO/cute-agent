from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from .ab_report import build_ab_report, write_ab_report
from .experiment import (
    CellResult,
    CellSpec,
    ExperimentSpec,
    build_arm_fingerprints,
    build_cells,
    isolated_environment,
    load_existing_cells,
    load_experiment,
    load_experiment_cases,
    now_iso,
    prepare_arm_roots,
    reusable_cell,
    write_json_atomic,
)
from .java_runner import compile_java_fixture_runner

CellExecutor = Callable[[CellSpec, int], CellResult]
RETRYABLE_STATUSES = {"infra_error", "adapter_error"}
FUSE_STATUSES = {"infra_error"}


def _failure_result(
    cell: CellSpec,
    *,
    status: str,
    error: str,
    retry_history: list[dict[str, Any]],
) -> CellResult:
    return CellResult(
        cell_key=cell.cell_key,
        experiment_id=cell.experiment_id,
        experiment_spec_hash=cell.experiment_spec_hash,
        arm=cell.arm,
        model=cell.model,
        case_id=cell.case.id,
        suite=cell.case.suite,
        repetition=cell.repetition,
        status=status,
        task_score=0.0,
        hard_gate_passed=False,
        in_accuracy_denominator=False,
        failure_ownership="environment" if status == "infra_error" else "adapter",
        metrics={
            "apiTurns": None,
            "toolCalls": None,
            "toolErrors": None,
            "retries": len(retry_history),
            "totalTokens": None,
            "wallMs": None,
            "firstTokenMs": None,
            "estimatedCost": None,
        },
        scores=[],
        retry_history=retry_history,
        started_at=now_iso(),
        finished_at=now_iso(),
        source_fingerprint=cell.source_fingerprint,
        error=error,
    )


def _copy_work_artifacts(
    result: CellResult,
    work_dir: Path,
    output_dir: Path,
) -> CellResult:
    traces_dir = output_dir / "traces"
    transcripts_dir = output_dir / "transcripts"
    traces_dir.mkdir(parents=True, exist_ok=True)
    transcripts_dir.mkdir(parents=True, exist_ok=True)
    trace = work_dir / "trace.json"
    transcript = work_dir / "transcript.json"
    if trace.exists():
        target = traces_dir / f"{result.cell_key}.json"
        target.write_bytes(trace.read_bytes())
        result.trace_path = target.relative_to(output_dir).as_posix()
    if transcript.exists():
        target = transcripts_dir / f"{result.cell_key}.json"
        target.write_bytes(transcript.read_bytes())
        result.transcript_path = target.relative_to(output_dir).as_posix()
    return result


def _default_executor(
    spec: ExperimentSpec,
    output_dir: Path,
) -> CellExecutor:
    def execute(cell: CellSpec, attempt: int) -> CellResult:
        work_dir = (
            output_dir
            / "work"
            / cell.cell_key
            / f"attempt-{attempt:02d}"
        )
        work_dir.mkdir(parents=True, exist_ok=True)
        spec_path = work_dir / "cell-spec.json"
        write_json_atomic(spec_path, cell.to_payload())
        env = isolated_environment(
            arm_env=cell.arm_env,
            arm_root=cell.arm_root,
            cell_work_dir=work_dir,
        )
        env.update(
            {
                "CUTE_EVAL_EXPERIMENT_ID": cell.experiment_id,
                "CUTE_EVAL_CELL_KEY": cell.cell_key,
                "CUTE_EVAL_CELL_ARM": cell.arm,
                "CUTE_EVAL_RUN_PREFIX": f"{cell.experiment_id}-{cell.cell_key[:12]}",
                "CUTE_EVAL_MODEL_ID": cell.model,
                "CUTE_EVAL_SEED": str(spec.seed),
                "CUTE_EVAL_DB_SCHEMA": "eval_"
                + "".join(
                    char if char.isalnum() else "_"
                    for char in cell.experiment_id
                ),
            }
        )
        command = [
            sys.executable,
            "-m",
            "cute_evals.ab_cell",
            "--cell",
            str(spec_path),
            "--work-dir",
            str(work_dir),
            "--profile",
            spec.profile,
            "--java-mode",
            spec.java_mode,
            "--timeout",
            str(spec.timeout_seconds),
        ]
        if spec.require_judge:
            command.append("--require-judge")
        try:
            completed = subprocess.run(
                command,
                cwd=cell.arm_root,
                env=env,
                text=True,
                encoding="utf-8",
                errors="replace",
                capture_output=True,
                timeout=spec.timeout_seconds + 30,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            return _failure_result(
                cell,
                status="infra_error",
                error=f"cell child timed out: {exc}",
                retry_history=[],
            )
        finally:
            shutil.rmtree(env.get("CUTE_EVAL_JAVA_TMP", ""), ignore_errors=True)
        logs_dir = output_dir / "logs"
        logs_dir.mkdir(parents=True, exist_ok=True)
        (logs_dir / f"{cell.cell_key}-attempt-{attempt:02d}.stdout.log").write_text(
            completed.stdout,
            encoding="utf-8",
        )
        (logs_dir / f"{cell.cell_key}-attempt-{attempt:02d}.stderr.log").write_text(
            completed.stderr,
            encoding="utf-8",
        )
        result_path = work_dir / "result.json"
        if not result_path.exists():
            return _failure_result(
                cell,
                status="adapter_error",
                error=(
                    f"cell child produced no result.json (exit={completed.returncode})\n"
                    f"stdout:\n{completed.stdout[-4000:]}\n"
                    f"stderr:\n{completed.stderr[-4000:]}"
                ),
                retry_history=[],
            )
        result = CellResult.from_dict(
            json.loads(result_path.read_text(encoding="utf-8"))
        )
        return _copy_work_artifacts(result, work_dir, output_dir)

    return execute


def _run_cell_with_retries(
    cell: CellSpec,
    *,
    retries: int,
    executor: CellExecutor,
) -> CellResult:
    history: list[dict[str, Any]] = []
    result: CellResult | None = None
    for attempt in range(retries + 1):
        try:
            result = executor(cell, attempt)
        except Exception as exc:  # noqa: BLE001 - executor boundary
            result = _failure_result(
                cell,
                status="adapter_error",
                error=f"{type(exc).__name__}: {exc}",
                retry_history=history,
            )
        if result.status not in RETRYABLE_STATUSES:
            result.retry_history = history
            result.metrics["retries"] = len(history)
            return result
        history.append(
            {
                "attempt": attempt + 1,
                "status": result.status,
                "error": result.error,
            }
        )
    assert result is not None
    result.retry_history = history
    result.metrics["retries"] = len(history)
    return result


def _write_experiment_snapshot(
    output_dir: Path,
    spec: ExperimentSpec,
    *,
    roots: dict[str, Path],
    fingerprints: dict[str, str],
) -> None:
    write_json_atomic(
        output_dir / "experiment.json",
        {
            "schemaVersion": "1.0",
            "experimentId": spec.experiment_id,
            "experimentSpecHash": spec.spec_hash,
            "sourcePath": str(spec.source_path),
            "createdAt": now_iso(),
            "spec": spec.raw,
            "armRoots": {name: str(path) for name, path in roots.items()},
            "armFingerprints": fingerprints,
        },
    )


def _load_snapshot(output_dir: Path) -> tuple[ExperimentSpec, dict[str, Any]]:
    snapshot_path = output_dir / "experiment.json"
    if not snapshot_path.exists():
        raise FileNotFoundError(f"experiment snapshot not found: {snapshot_path}")
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    raw = snapshot.get("spec")
    if not isinstance(raw, dict):
        raise TypeError(f"invalid experiment snapshot: {snapshot_path}")
    source_path = Path(str(snapshot.get("sourcePath") or snapshot_path))
    if source_path.exists():
        loaded = load_experiment(source_path)
        if loaded.spec_hash != snapshot.get("experimentSpecHash"):
            raise ValueError("experiment spec changed since the snapshot was created")
        return loaded, snapshot
    root = Path(str(snapshot.get("armRoots", {}).get("candidate") or output_dir))
    configured = raw.get("caseDirs")
    case_dirs = (
        [(root / str(item)).resolve() for item in configured]
        if configured
        else [root / "evals" / "agent" / "cases" / "ab" / str(raw["suite"])]
    )
    spec = ExperimentSpec.from_dict(raw, source_path=source_path, case_dirs=case_dirs)
    return spec, snapshot


def _run_loaded_experiment(
    spec: ExperimentSpec,
    output_dir: Path,
    *,
    executor: CellExecutor | None = None,
) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    cases = load_experiment_cases(spec)
    roots = prepare_arm_roots(spec, output_dir)
    fingerprints = build_arm_fingerprints(spec, roots)
    _write_experiment_snapshot(
        output_dir,
        spec,
        roots=roots,
        fingerprints=fingerprints,
    )
    cells = build_cells(spec, cases, roots, fingerprints)
    existing = load_existing_cells(
        output_dir,
        experiment_spec_hash=spec.spec_hash,
    )
    active_executor = executor or _default_executor(spec, output_dir)
    results: list[CellResult] = []
    pending: list[CellSpec] = []
    for cell in cells:
        saved = existing.get(cell.cell_key)
        if reusable_cell(cell, saved):
            results.append(saved)
        else:
            pending.append(cell)

    if executor is None and pending:
        for root in sorted({str(path) for path in roots.values()}):
            previous = os.environ.get("CUTE_EVAL_REPO_ROOT")
            os.environ["CUTE_EVAL_REPO_ROOT"] = root
            try:
                compile_java_fixture_runner()
            finally:
                if previous is None:
                    os.environ.pop("CUTE_EVAL_REPO_ROOT", None)
                else:
                    os.environ["CUTE_EVAL_REPO_ROOT"] = previous

    started = time.monotonic()
    fuse_count = 0
    stopped_for_budget = False
    for offset in range(0, len(pending), spec.parallelism):
        if stopped_for_budget:
            break
        batch = pending[offset : offset + spec.parallelism]
        with ThreadPoolExecutor(max_workers=min(spec.parallelism, len(batch))) as pool:
            batch_results = list(
                pool.map(
                    lambda item: _run_cell_with_retries(
                        item,
                        retries=spec.retries,
                        executor=active_executor,
                    ),
                    batch,
                )
            )
        for cell, result in zip(batch, batch_results, strict=True):
            write_json_atomic(
                output_dir / "cells" / f"{cell.cell_key}.json",
                result.to_dict(),
            )
            results.append(result)
            if result.status in FUSE_STATUSES:
                fuse_count += 1
            else:
                fuse_count = 0
        if fuse_count >= 3:
            break
        elapsed_minutes = (time.monotonic() - started) / 60
        if elapsed_minutes >= float(spec.budgets["maxWallMinutes"]):
            stopped_for_budget = True
        spent = sum(
            float(result.metrics.get("estimatedCost") or 0)
            for result in results
        )
        if spent > float(spec.budgets["maxUsd"]):
            stopped_for_budget = True

    report = build_ab_report(spec, results)
    write_ab_report(output_dir, report)
    return report


def run_experiment(
    spec_path: Path,
    output_dir: Path,
    *,
    executor: CellExecutor | None = None,
) -> dict[str, Any]:
    spec = load_experiment(spec_path)
    return _run_loaded_experiment(spec, output_dir.resolve(), executor=executor)


def resume_experiment(
    output_dir: Path,
    *,
    executor: CellExecutor | None = None,
) -> dict[str, Any]:
    output_dir = output_dir.resolve()
    spec, _ = _load_snapshot(output_dir)
    return _run_loaded_experiment(spec, output_dir, executor=executor)


def report_experiment(output_dir: Path) -> dict[str, Any]:
    output_dir = output_dir.resolve()
    spec, _ = _load_snapshot(output_dir)
    results = list(
        load_existing_cells(
            output_dir,
            experiment_spec_hash=spec.spec_hash,
        ).values()
    )
    report = build_ab_report(spec, results)
    write_ab_report(output_dir, report)
    return report


def audit_cell(output_dir: Path, cell_key: str) -> dict[str, Any]:
    results = load_existing_cells(output_dir.resolve())
    match = results.get(cell_key)
    if match is None:
        matches = [
            item for key, item in results.items() if key.startswith(cell_key)
        ]
        if len(matches) != 1:
            raise ValueError(
                f"cell not found or ambiguous: {cell_key}; matches={len(matches)}"
            )
        match = matches[0]
    return match.to_dict()


def exit_code_for(report: dict[str, Any]) -> int:
    # Inconclusive means the experiment completed but did not establish a
    # directional result; it is not an execution failure. Only a regression or
    # an incomplete/fuse-stopped experiment blocks the command.
    return 1 if report.get("recommendation") in {"block", "needs-more-runs"} else 0
