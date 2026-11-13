from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from ...judge_benchmarks.base import LicenseBlockedError
from .adapter import MarbleAdapter
from .bootstrap import KNOWN_EVALUATOR_SYNTAX_ERROR, KNOWN_EVALUATOR_SYNTAX_REPAIR


class MarbleRunBlocked(RuntimeError):
    def __init__(self, message: str, details: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.details = details or {}


@dataclass(frozen=True, slots=True)
class MarbleRunConfig:
    python_executable: Path
    domain: str
    task_ids: tuple[int, ...]
    llm_model: str
    output_dir: Path
    timeout_seconds: int = 1800
    compatibility_local: bool = False
    repair_evaluator_syntax: bool = False
    offline_tools: bool = False
    max_iterations: int = 3


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def _source_diagnostic(path: Path) -> dict[str, Any]:
    try:
        compile(path.read_text(encoding="utf-8"), str(path), "exec")
    except SyntaxError as exc:
        return {
            "path": str(path),
            "status": "syntax_error",
            "message": str(exc),
            "line": exc.lineno,
            "offset": exc.offset,
        }
    return {"path": str(path), "status": "ok"}


def official_source_diagnostics(
    adapter: MarbleAdapter,
) -> dict[str, Any]:
    diagnostics: dict[str, Any] = {}
    for name in ("entrypoint", "evaluator", "taskConverter"):
        path = adapter._path(str(adapter.manifest["scripts"][name]["file"]))
        diagnostics[name] = _source_diagnostic(path)
    return diagnostics


def _select_tasks(
    adapter: MarbleAdapter,
    domain: str,
    task_ids: tuple[int, ...],
) -> list[dict[str, Any]]:
    rows = adapter.task_rows(domain)
    selected = [
        row
        for row in rows
        if int(row.get("task_id", -1)) in set(task_ids)
    ]
    found = {int(row.get("task_id", -1)) for row in selected}
    missing = sorted(set(task_ids) - found)
    if missing:
        raise ValueError(f"MARBLE task ids not found in {domain}: {missing}")
    if not selected:
        raise ValueError("MARBLE selected no tasks")
    return selected


def _write_selection(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
        encoding="utf-8",
    )


def _config_task_id(path: Path) -> int:
    payload = yaml.safe_load(path.read_text(encoding="utf-8"))
    value = payload.get("task_id") if isinstance(payload, dict) else None
    if value is None:
        match = re.search(r"task_(\d+)", path.stem)
        if match is None:
            raise RuntimeError(f"MARBLE config has no task id: {path}")
        value = match.group(1)
    try:
        return int(value)
    except (TypeError, ValueError) as exc:
        raise RuntimeError(f"MARBLE config has invalid task id: {path}") from exc


def _generate_config(
    *,
    adapter: MarbleAdapter,
    config: MarbleRunConfig,
    selection_path: Path,
    config_dir: Path,
    output_file: Path,
) -> subprocess.CompletedProcess[str]:
    converter = adapter._path(
        str(adapter.manifest["scripts"]["taskConverter"]["file"])
    )
    environment = {
        "max_iterations": config.max_iterations,
        "name": "Research Collaboration Environment",
        "type": "Research",
    }
    command = [
        str(config.python_executable),
        str(converter),
        "--input_file",
        str(selection_path),
        "--output_folder",
        str(config_dir),
        "--default_coordinate_mode",
        "graph",
        "--default_environment",
        json.dumps(environment),
        "--default_llm",
        config.llm_model,
        "--default_memory",
        json.dumps({"type": "BaseMemory"}),
        "--default_metrics_evaluate_llm",
        config.llm_model,
        "--default_output",
        json.dumps({"file_path": output_file.as_posix()}),
    ]
    return subprocess.run(
        command,
        cwd=adapter.source_dir,
        text=True,
        capture_output=True,
        timeout=120,
        check=False,
    )


def _read_run_output(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    rows: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        value = json.loads(line)
        if isinstance(value, dict):
            rows.append(value)
    return rows[-1] if rows else None


def _result_metrics(
    run_output: dict[str, Any] | None,
    expected_agent_count: int,
) -> dict[str, Any]:
    if run_output is None:
        return {
            "executionStatus": "failed",
            "iterationCount": 0,
            "agentResultCount": 0,
            "communicationCount": 0,
            "tokenUsage": None,
            "observedTaskEvaluation": None,
            "planningScores": [],
            "communicationScores": [],
        }
    iterations = run_output.get("iterations")
    iteration_rows = iterations if isinstance(iterations, list) else []
    agent_result_count = 0
    communication_count = 0
    for iteration in iteration_rows:
        if not isinstance(iteration, dict):
            continue
        results = iteration.get("task_results")
        if isinstance(results, list):
            agent_result_count += len(results)
        communications = iteration.get("communications")
        if isinstance(communications, list):
            communication_count += len(communications)
    status = (
        "completed"
        if agent_result_count >= expected_agent_count and expected_agent_count > 0
        else "degraded"
    )
    planning_scores = run_output.get("planning_scores")
    communication_scores = run_output.get("communication_scores")
    return {
        "executionStatus": status,
        "iterationCount": len(iteration_rows),
        "agentResultCount": agent_result_count,
        "communicationCount": communication_count,
        "tokenUsage": run_output.get("token_usage"),
        "observedTaskEvaluation": run_output.get("task_evaluation"),
        "planningScores": (
            planning_scores if isinstance(planning_scores, list) else []
        ),
        "communicationScores": (
            communication_scores
            if isinstance(communication_scores, list)
            else []
        ),
    }


def _mean_numeric(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def _usage_policy(mode: str) -> dict[str, bool | str]:
    return {
        "mode": mode,
        "officialScore": False,
        "publicationAllowed": False,
        "gateEligible": False,
        "advisory": True,
    }


def run_marble(
    adapter: MarbleAdapter,
    config: MarbleRunConfig,
) -> dict[str, Any]:
    if config.domain != "research":
        raise ValueError(
            "current MARBLE runner supports the research domain only; "
            "coding/database/minecraft require their official runtime prerequisites"
        )
    if not config.python_executable.is_file():
        raise FileNotFoundError(config.python_executable)
    if not config.compatibility_local:
        adapter.assert_run_allowed()
    elif adapter.license_status not in {"approved", "local-research"}:
        if adapter.license_status != "blocked-license":
            raise LicenseBlockedError(
                adapter.name,
                adapter.license_status,
                str(adapter.manifest.get("licenseEvidence") or ""),
            )

    assets = adapter.verify_assets()
    diagnostics = official_source_diagnostics(adapter)
    evaluator_path = adapter._path(
        str(adapter.manifest["scripts"]["evaluator"]["file"])
    )
    evaluator_text = evaluator_path.read_text(encoding="utf-8")
    syntax_defect = evaluator_text.count(KNOWN_EVALUATOR_SYNTAX_ERROR)
    if syntax_defect and not config.repair_evaluator_syntax:
        raise MarbleRunBlocked(
            "pinned MARBLE evaluator contains a syntax error; "
            "official run is blocked",
            {
                "diagnostics": diagnostics,
                "knownDefects": syntax_defect,
                "repairRequired": True,
            },
        )
    if syntax_defect and not config.compatibility_local:
        raise MarbleRunBlocked(
            "syntax repair is only allowed in explicit local compatibility mode",
            {"diagnostics": diagnostics},
        )

    rows = _select_tasks(adapter, config.domain, config.task_ids)
    output_dir = config.output_dir.resolve()
    config_dir = output_dir / "config"
    output_file = output_dir / "official-output.jsonl"
    selection_path = output_dir / "selected-tasks.jsonl"
    log_path = output_dir / "runner.log"
    output_dir.mkdir(parents=True, exist_ok=True)
    config_dir.mkdir(parents=True, exist_ok=True)
    _write_selection(selection_path, rows)
    generated = _generate_config(
        adapter=adapter,
        config=config,
        selection_path=selection_path,
        config_dir=config_dir,
        output_file=output_file,
    )
    if generated.returncode != 0:
        raise RuntimeError(
            f"MARBLE task converter failed: {generated.stderr[-2000:]}"
        )
    config_files = sorted(config_dir.glob("task_*.yaml"))
    if not config_files:
        raise RuntimeError("MARBLE task converter produced no config")

    source_root = adapter.source_dir
    src_root = Path(__file__).resolve().parents[3]
    env = os.environ.copy()
    env["PYTHONPATH"] = os.pathsep.join(
        [str(src_root), str(source_root)]
    )
    env.setdefault("LITELLM_LOCAL_MODEL_COST_MAP", "True")
    env.setdefault("HF_HUB_OFFLINE", "1")
    env.setdefault("TRANSFORMERS_OFFLINE", "1")
    available_tasks = adapter.tasks("official-full")
    task_reports: list[dict[str, Any]] = []
    run_reports: list[dict[str, Any]] = []
    log_parts: list[str] = []
    numeric_scores: list[float] = []
    for config_file in config_files:
        official_task_id = _config_task_id(config_file)
        selected_task = next(
            (
                item
                for item in available_tasks
                if item.domain == config.domain
                and int(item.task_id) == official_task_id
                and int(item.task_id) in set(config.task_ids)
            ),
            None,
        )
        if selected_task is None:
            raise RuntimeError(
                f"selected MARBLE task disappeared: {official_task_id}"
            )
        command = [
            str(config.python_executable),
            "-m",
            "cute_evals.benchmarks.marble.bootstrap",
            "--source-dir",
            str(source_root),
            "--config-path",
            str(config_file),
        ]
        if config.repair_evaluator_syntax:
            command.append("--repair-evaluator-syntax")
        if config.offline_tools:
            command.append("--offline-tools")
        task_output = output_dir / f"official-output-task-{official_task_id}.jsonl"
        output_file.unlink(missing_ok=True)
        task_output.unlink(missing_ok=True)
        timed_out = False
        try:
            completed = subprocess.run(
                command,
                cwd=source_root / "marble",
                env=env,
                text=True,
                capture_output=True,
                timeout=config.timeout_seconds,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            timed_out = True
            completed = subprocess.CompletedProcess(
                command,
                -1,
                stdout=(
                    exc.stdout.decode(errors="replace")
                    if isinstance(exc.stdout, bytes)
                    else exc.stdout or ""
                ),
                stderr=(
                    exc.stderr.decode(errors="replace")
                    if isinstance(exc.stderr, bytes)
                    else exc.stderr or ""
                )
                + f"\nMARBLE run timed out after {config.timeout_seconds}s",
            )
        log_parts.append(
            f"=== {config_file.name} ===\n"
            f"{completed.stdout}\n{completed.stderr}"
        )
        if output_file.exists():
            shutil.copy2(output_file, task_output)
        run_output = _read_run_output(output_file)
        metrics = _result_metrics(run_output, selected_task.agent_count)
        task_status = (
            "verified"
            if not timed_out
            and completed.returncode == 0
            and metrics["executionStatus"] == "completed"
            else "unverified"
            if run_output is not None
            else "failed"
        )
        observed = metrics["observedTaskEvaluation"]
        if isinstance(observed, dict):
            numeric_scores.extend(
                float(value)
                for value in observed.values()
                if isinstance(value, (int, float))
                and not isinstance(value, bool)
            )
        task_reports.append(
            {
                "taskId": selected_task.id,
                "domain": selected_task.domain,
                "officialTaskId": selected_task.task_id,
                "agentCount": selected_task.agent_count,
                "status": task_status,
                **metrics,
            }
        )
        run_reports.append(
            {
                "officialTaskId": selected_task.task_id,
                "config": str(config_file),
                "officialOutput": (
                    str(task_output) if task_output.exists() else str(output_file)
                ),
                "returnCode": completed.returncode,
                "timedOut": timed_out,
            }
        )
    log_path.write_text("\n".join(log_parts), encoding="utf-8")
    all_verified = all(item["status"] == "verified" for item in task_reports)
    evaluator_sha = _sha256_file(evaluator_path)
    runtime_evaluator_sha = evaluator_sha
    if config.repair_evaluator_syntax and syntax_defect:
        repaired = evaluator_text.replace(
            KNOWN_EVALUATOR_SYNTAX_ERROR,
            KNOWN_EVALUATOR_SYNTAX_REPAIR,
            1,
        )
        runtime_evaluator_sha = (
            "sha256:" + hashlib.sha256(repaired.encode("utf-8")).hexdigest()
        )

    score_label = (
        "compatibility-subset"
        if all_verified and config.compatibility_local
        else "official-subset"
        if all_verified
        else "unverified"
    )
    limitations = [
        "the pinned official source requires a syntax-only compatibility repair",
        "the run is local-research compatibility evidence, not an official leaderboard score",
        "MARBLE research tools depend on external arXiv/network availability",
    ]
    if config.offline_tools:
        limitations.append(
            "external research tools were disabled for an offline compatibility run"
        )
    if any(
        item["executionStatus"] == "degraded" for item in task_reports
    ):
        limitations.append(
            "one or more MARBLE agents failed before returning task results; "
            "observed evaluator output is unverified"
        )
    if any(item["timedOut"] for item in run_reports):
        limitations.append(
            f"one or more MARBLE runs timed out after {config.timeout_seconds} seconds"
        )
    report = {
        "schemaVersion": "1.0",
        "subject": {
            "type": "multi-agent-system",
            "name": "MARBLE BaseAgent",
            "model": config.llm_model,
        },
        "benchmark": {
            **adapter.report_metadata(),
            "datasetHash": adapter.dataset_hash(assets),
            "evaluatorHash": assets["scripts"]["evaluator"]["sha256"],
            "runtimeEvaluatorHash": runtime_evaluator_sha,
        },
        "scoreNamespace": "agent-benchmark",
        "scoreLabel": score_label,
        "usagePolicy": _usage_policy(
            "local-research"
            if config.compatibility_local
            else "official"
        ),
        "tasks": task_reports,
        "metrics": {
            "taskCount": len(task_reports),
            "verifiedTasks": sum(
                1 for item in task_reports if item["status"] == "verified"
            ),
            "unverifiedTasks": sum(
                1 for item in task_reports if item["status"] == "unverified"
            ),
            "failedTasks": sum(
                1 for item in task_reports if item["status"] == "failed"
            ),
            "verifiedOverallScore": (
                _mean_numeric(numeric_scores)
                if all_verified
                else None
            ),
            "observedOverallScore": _mean_numeric(numeric_scores),
        },
        "environment": {
            "sourceDir": str(source_root),
            "upstreamCommit": adapter.manifest.get("upstreamCommit"),
            "python": str(config.python_executable),
            "config": str(config_files[0]),
            "configs": [str(path) for path in config_files],
            "officialOutput": str(output_file),
            "officialOutputs": [
                str(item["officialOutput"]) for item in run_reports
            ],
            "log": str(log_path),
            "returnCode": run_reports[-1]["returnCode"],
            "timedOut": any(item["timedOut"] for item in run_reports),
            "runs": run_reports,
            "sourceDiagnostics": diagnostics,
            "patch": (
                {
                    "kind": "syntax-indentation-only",
                    "reason": "pinned evaluator fails to compile",
                    "officialEvaluatorHash": evaluator_sha,
                    "runtimeEvaluatorHash": runtime_evaluator_sha,
                }
                if config.repair_evaluator_syntax and syntax_defect
                else None
            ),
        },
        "limitations": limitations,
    }
    (output_dir / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return report
