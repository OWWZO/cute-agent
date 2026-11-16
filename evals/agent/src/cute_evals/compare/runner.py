from __future__ import annotations

import json
import os
import traceback
from dataclasses import asdict
from pathlib import Path
from typing import Any

from ..benchmarks.budget import BudgetPolicy
from ..models import CaseResult
from ..report import build_report
from .codex_adapter import CodexOptions, codex_version, run_codex_case
from .compare_report import build_comparison_report, write_comparison_report
from .cute_adapter import run_cute_batch
from .dataset import CompareDataset, load_compare_dataset
from .inspect_task import run_compare_inspect
from .scoring import (
    normalize_trace,
    score_compare_case,
    unavailable_result,
    write_compare_trace,
)
from .workspace import (
    check_workspace,
    materialize_workspace,
    reset_directory,
    snapshot_workspace,
    tree_hash,
)


def _case_timeout_seconds(case, configured: int) -> int:
    max_wall_ms = case.budgets.max_wall_ms
    if not max_wall_ms:
        return configured
    return max(1, min(configured, int((int(max_wall_ms) + 999) // 1000) + 10))


def _batch_timeout_seconds(
    dataset: CompareDataset,
    configured: int,
    budget_policy: BudgetPolicy,
) -> int:
    total_ms = sum(
        int(
            budget_policy.apply(
                case.to_eval_case(dataset.source_path, dataset.suite)
            ).budgets.max_wall_ms
            or 0
        )
        for case in dataset.cases
    )
    return max(configured, (total_ms + 999) // 1000 + 120)


def _trace_relative_path(agent_root: Path, trace_path: Path) -> str:
    return trace_path.relative_to(agent_root).as_posix()


def _collect_workspace_check(case, workspace: Path):
    return check_workspace(workspace, case.workspace_checks)


def _cute_workspace_metadata() -> dict[str, Any]:
    deletion_sync_raw = (
        os.getenv("E2B_WORKSPACE_DELETE_SYNC_ENABLED") or "true"
    ).strip()
    return {
        "sandboxBackend": (os.getenv("CODE_SANDBOX_BACKEND") or "unknown")
        .strip()
        .lower(),
        "workspaceRootMode": "isolated-case-workspace",
        "workspaceRootsConfigured": bool(
            os.getenv("CODE_EXECUTION_WORKSPACE_ROOTS")
            or os.getenv("CODE_EXECUTION_WORKSPACE_ROOT")
        ),
        "deletionSyncEnabled": deletion_sync_raw.lower()
        not in {"0", "false", "no", "off"},
    }


def _configured_workspace_roots() -> tuple[Path, ...]:
    roots: list[Path] = []
    multi_root = (os.getenv("CODE_EXECUTION_WORKSPACE_ROOTS") or "").strip()
    if multi_root:
        roots.extend(
            Path(item)
            for item in multi_root.split(os.pathsep)
            if item.strip()
        )
    for env_name in (
        "CODE_EXECUTION_WORKSPACE_ROOT",
        "SKILL_OUTPUT_ROOT",
        "CUTE_SKILL_OUTPUT_ROOT",
    ):
        value = (os.getenv(env_name) or "").strip()
        if value:
            roots.append(Path(value))
    default_root = (
        Path(__file__).resolve().parents[5] / "cute-tool" / "skilloutput"
    )
    roots.append(default_root)
    return tuple(dict.fromkeys(root.expanduser().resolve() for root in roots))


def _validate_cute_workspace_roots(
    workspaces: dict[str, Path],
    *,
    suggested_root: Path,
) -> None:
    allowed_roots = _configured_workspace_roots()
    uncovered = [
        workspace
        for workspace in workspaces.values()
        if not any(
            workspace.resolve().is_relative_to(root)
            for root in allowed_roots
        )
    ]
    if not uncovered:
        return
    suggested = str(suggested_root.resolve())
    raise RuntimeError(
        "CODE_EXECUTION_WORKSPACE_ROOTS does not cover the eval workspace: "
        f"{uncovered[0]}. Set it before starting cute-tool, for example: "
        f'$env:CODE_EXECUTION_WORKSPACE_ROOTS = "{suggested}"'
    )


def _run_cute_attempt(
    dataset: CompareDataset,
    *,
    attempt: int,
    agent_root: Path,
    java_mode: str,
    timeout_seconds: int,
    build: bool,
    model_id: str | None,
    tool_mode: str,
    budget_policy: BudgetPolicy,
) -> tuple[list[CaseResult], dict[str, Any]]:
    attempt_root = agent_root / "attempts" / f"{attempt:02d}"
    reset_directory(attempt_root, root=agent_root)
    workspaces = {}
    materialized = {}
    for case in dataset.cases:
        item = materialize_workspace(
            case,
            attempt_root / "cases" / case.id,
            root=attempt_root,
        )
        materialized[case.id] = item
        workspaces[case.id] = item.path

    eval_cases = [
        budget_policy.apply(
            case.to_eval_case(dataset.source_path, dataset.suite)
        )
        for case in dataset.cases
    ]
    try:
        _validate_cute_workspace_roots(
            workspaces,
            suggested_root=agent_root,
        )
        traces = run_cute_batch(
            eval_cases,
            workspaces,
            attempt_root,
            mode=java_mode,
            profile="nightly",
            timeout_seconds=_batch_timeout_seconds(
                dataset,
                timeout_seconds,
                budget_policy,
            ),
            build=build,
            tool_mode=tool_mode,
            model_id=model_id,
        )
    except Exception as exc:
        message = f"{type(exc).__name__}: {exc}"
        (attempt_root / "adapter-error.log").write_text(
            f"{message}\n\n{traceback.format_exc()}",
            encoding="utf-8",
        )
        results = []
        for case in dataset.cases:
            eval_case = budget_policy.apply(
                case.to_eval_case(dataset.source_path, dataset.suite)
            )
            trace_path = (
                attempt_root / "traces" / case.id / "trace.json"
            )
            results.append(
                unavailable_result(
                    eval_case,
                    agent="cute",
                    error=message,
                    attempt=attempt,
                    trace_path=_trace_relative_path(agent_root, trace_path),
                )
            )
            trace_path.parent.mkdir(parents=True, exist_ok=True)
            trace_path.write_text(
                json.dumps(results[-1].trace.raw, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
        return results, {
            "status": "unavailable",
            "error": message,
            "workspaces": [item.to_dict() for item in materialized.values()],
        }

    results: list[CaseResult] = []
    for case in dataset.cases:
        eval_case = budget_policy.apply(
            case.to_eval_case(dataset.source_path, dataset.suite)
        )
        trace = traces.get(case.id)
        if trace is None:
            results.append(
                unavailable_result(
                    eval_case,
                    agent="cute",
                    error=f"Java runner produced no trace for {case.id}",
                    attempt=attempt,
                    trace_path=(
                        f"attempts/{attempt:02d}/traces/{case.id}/trace.json"
                    ),
                )
            )
            continue
        workspace = materialized[case.id]
        normalized = normalize_trace(
            eval_case,
            trace,
            agent="cute",
            agent_version=None,
            workspace=workspace.path,
            workspace_hash=workspace.tree_hash,
            dataset_hash=dataset.dataset_hash,
            mode=java_mode,
            environment={
                "adapter": {
                    "kind": "java-ledger",
                    "javaMode": java_mode,
                    "toolMode": tool_mode,
                    **_cute_workspace_metadata(),
                }
            },
        )
        trace_path = attempt_root / "traces" / case.id / "trace.json"
        write_compare_trace(normalized, trace_path)
        snapshot_workspace(
            workspace.path,
            attempt_root / "cases" / case.id / "final-workspace",
        )
        results.append(
            score_compare_case(
                eval_case,
                normalized,
                _trace_relative_path(agent_root, trace_path),
                attempt=attempt,
                workspace_check=_collect_workspace_check(case, workspace.path),
            )
        )
    return results, {
        "status": "completed",
        "workspaces": [item.to_dict() for item in materialized.values()],
        **_cute_workspace_metadata(),
    }


def _run_codex_attempt(
    dataset: CompareDataset,
    *,
    attempt: int,
    agent_root: Path,
    timeout_seconds: int,
    command: str,
    model: str | None,
    sandbox: str,
    approval: str,
    budget_policy: BudgetPolicy,
) -> tuple[list[CaseResult], dict[str, Any]]:
    attempt_root = agent_root / "attempts" / f"{attempt:02d}"
    reset_directory(attempt_root, root=agent_root)
    version = codex_version(command)
    results: list[CaseResult] = []
    workspaces = []
    for case in dataset.cases:
        eval_case = budget_policy.apply(
            case.to_eval_case(dataset.source_path, dataset.suite)
        )
        materialized = materialize_workspace(
            case,
            attempt_root / "cases" / case.id,
            root=attempt_root,
        )
        workspaces.append(materialized.to_dict())
        trace_path = attempt_root / "traces" / case.id / "trace.json"
        trace = run_codex_case(
            eval_case,
            materialized.path,
            attempt_root / "cases" / case.id,
            options=CodexOptions(
                command=command,
                model=model,
                sandbox=sandbox,
                approval=approval,
            ),
            timeout_seconds=_case_timeout_seconds(
                eval_case,
                timeout_seconds,
            ),
            environment={
                "agent": "codex-cli",
                "agentVersion": version,
                "workspace": str(materialized.path),
                "workspaceHash": materialized.tree_hash,
                "datasetHash": dataset.dataset_hash,
                "mode": "codex-cli",
                "budgetMode": budget_policy.mode,
                "network": case.network,
                "budgets": asdict(eval_case.budgets),
            },
            app_server=True,
            soft_budget_finalize=budget_policy.mode == "product",
        )
        normalized = normalize_trace(
            eval_case,
            trace,
            agent="codex",
            agent_version=version,
            workspace=materialized.path,
            workspace_hash=materialized.tree_hash,
            dataset_hash=dataset.dataset_hash,
            mode="codex-cli",
            environment={
                "adapter": {
                    "kind": "codex-exec-json",
                    "sandbox": sandbox,
                    "approval": approval,
                    "model": model,
                    "budgetMode": budget_policy.mode,
                }
            },
        )
        write_compare_trace(normalized, trace_path)
        snapshot_workspace(
            materialized.path,
            attempt_root / "cases" / case.id / "final-workspace",
        )
        relative = _trace_relative_path(agent_root, trace_path)
        if normalized.status == "UNAVAILABLE":
            error = str((normalized.raw.get("run") or {}).get("errorMessage") or "")
            results.append(
                unavailable_result(
                    eval_case,
                    agent="codex",
                    error=error,
                    attempt=attempt,
                    trace_path=relative,
                )
            )
        else:
            results.append(
                score_compare_case(
                    eval_case,
                    normalized,
                    relative,
                    attempt=attempt,
                    workspace_check=_collect_workspace_check(case, materialized.path),
                )
            )
    return results, {
        "status": "completed" if results else "unavailable",
        "workspaces": workspaces,
        "version": version,
    }


def validate_compare_dataset(path: Path) -> CompareDataset:
    return load_compare_dataset(path)


def materialize_compare_dataset(
    dataset: CompareDataset,
    output_dir: Path,
) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    items = []
    for case in dataset.cases:
        materialized = materialize_workspace(
            case,
            output_dir / case.id,
            root=output_dir,
        )
        items.append(materialized.to_dict())
    payload = {
        "schemaVersion": "1.0",
        "suite": dataset.suite,
        "datasetHash": dataset.dataset_hash,
        "cases": items,
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return payload


def run_compare(
    dataset_path: Path,
    *,
    output_dir: Path,
    agents: list[str],
    mode: str,
    repeat: int,
    timeout_seconds: int,
    java_mode: str,
    build: bool,
    cute_model: str | None,
    tool_mode: str,
    codex_command: str,
    codex_model: str | None,
    codex_sandbox: str,
    codex_approval: str,
    engine: str,
    budget_policy: BudgetPolicy | None = None,
) -> dict[str, Any]:
    budget_policy = budget_policy or BudgetPolicy()
    dataset = load_compare_dataset(dataset_path)
    output_dir.mkdir(parents=True, exist_ok=True)
    agent_results: dict[str, list[CaseResult]] = {}
    agent_reports: dict[str, dict[str, Any]] = {}
    agent_metadata: dict[str, dict[str, Any]] = {}
    inspect_errors: dict[str, str] = {}

    for agent in agents:
        agent_root = output_dir / "agents" / agent
        agent_results[agent] = []
        attempt_metadata = []
        for attempt in range(1, repeat + 1):
            if agent == "cute":
                results, metadata = _run_cute_attempt(
                    dataset,
                    attempt=attempt,
                    agent_root=agent_root,
                    java_mode=java_mode,
                    timeout_seconds=timeout_seconds,
                    build=build,
                    model_id=cute_model,
                    tool_mode=tool_mode,
                    budget_policy=budget_policy,
                )
            elif agent == "codex":
                results, metadata = _run_codex_attempt(
                    dataset,
                    attempt=attempt,
                    agent_root=agent_root,
                    timeout_seconds=timeout_seconds,
                    command=codex_command,
                    model=codex_model,
                    sandbox=codex_sandbox,
                    approval=codex_approval,
                    budget_policy=budget_policy,
                )
            else:
                raise ValueError(f"unsupported compare agent: {agent}")
            agent_results[agent].extend(results)
            attempt_metadata.append({"attempt": attempt, **metadata})

        agent_metadata[agent] = {
            "attempts": attempt_metadata,
            "traceCount": len(agent_results[agent]),
        }
        if engine == "inspect":
            try:
                log_dir = run_compare_inspect(
                    agent=agent,
                    suite=dataset.suite,
                    case_queries={case.id: case.query for case in dataset.cases},
                    results=agent_results[agent],
                    output_dir=agent_root,
                    mode=mode,
                )
                agent_metadata[agent]["inspectLog"] = str(log_dir)
            except Exception as exc:
                inspect_errors[agent] = f"{type(exc).__name__}: {exc}"
                (agent_root / "inspect-error.log").write_text(
                    inspect_errors[agent],
                    encoding="utf-8",
                )

        report = build_report(
            profile="nightly",
            agent=agent,
            cases=agent_results[agent],
            output_dir=agent_root,
            inspect_log=agent_metadata[agent].get("inspectLog"),
            environment={
                "engine": engine,
                "compareMode": mode,
                "dataset": str(dataset.source_path),
                "datasetHash": dataset.dataset_hash,
                "repeat": repeat,
                "budgetMode": budget_policy.mode,
                "budgetPolicy": budget_policy.to_dict(),
                "inspectError": inspect_errors.get(agent),
            },
        )
        agent_reports[agent] = report

    payload = build_comparison_report(
        dataset=dataset,
        mode=mode,
        repeat=repeat,
        agent_reports=agent_reports,
        agent_results={
            agent: [
                {
                    **result.to_dict(),
                    "toolCallCount": len(result.trace.tool_invocations),
                }
                for result in results
            ]
            for agent, results in agent_results.items()
        },
        agent_metadata=agent_metadata,
        quality_only=budget_policy.mode == "capability",
        environment={
            "engine": engine,
            "mode": mode,
            "repeat": repeat,
            "agents": agents,
            "javaMode": java_mode,
            "cuteModel": cute_model,
            "codexModel": codex_model,
            "codexCommand": codex_command,
            "codexSandbox": codex_sandbox,
            "codexApproval": codex_approval,
            "budgetMode": budget_policy.mode,
            "budgetPolicy": budget_policy.to_dict(),
            "inspectErrors": inspect_errors,
            "modelControl": "unverified",
            "networkPolicyEnforced": False,
            **_cute_workspace_metadata(),
        },
    )
    write_comparison_report(output_dir, payload)
    (output_dir / "run.json").write_text(
        json.dumps(
            {
                "schemaVersion": "1.0",
                "dataset": str(dataset.source_path),
                "datasetHash": dataset.dataset_hash,
                "outputDir": str(output_dir),
                "agentMetadata": agent_metadata,
                "budgetMode": budget_policy.mode,
                "budgetPolicy": budget_policy.to_dict(),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    payload["inspectErrors"] = inspect_errors
    payload["hasUnavailableAgent"] = any(
        any(
            result.trace.status == "UNAVAILABLE"
            for result in agent_results.get(agent, [])
        )
        for agent in agents
    )
    return payload
