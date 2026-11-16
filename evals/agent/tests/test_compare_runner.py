from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from cute_evals.compare import runner
from cute_evals.compare.scoring import (
    normalize_trace,
    score_compare_case,
    write_compare_trace,
)
from cute_evals.models import CaseResult, RunTrace, ScoreRecord


DATASET = (
    Path(__file__).resolve().parents[1]
    / "cases"
    / "compare"
    / "pilot.json"
)


def _passing_result(case) -> CaseResult:
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "requestId": f"fake-{case.id}",
                "status": "SUCCESS",
                "finalSummaryText": "DONE",
                "totalTokens": 10,
                "durationMs": 5,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {"agent": "fake"},
        }
    )
    return CaseResult(
        case=case,
        trace=trace,
        scores=[
            ScoreRecord(
                case_id=case.id,
                scorer="test",
                name="task_success",
                value=True,
                unit="boolean",
                passed=True,
                hard_gate=True,
                status="verified",
            )
        ],
        trace_path=f"attempts/01/traces/{case.id}/trace.json",
        attempt=1,
    )


def test_compare_run_writes_agent_and_comparison_reports(
    tmp_path: Path,
    monkeypatch,
) -> None:
    from cute_evals.compare.dataset import load_compare_dataset

    def fake_attempt(dataset, *, attempt, agent_root, **kwargs):
        results = [
            _passing_result(case.to_eval_case(dataset.source_path, dataset.suite))
            for case in dataset.cases
        ]
        return results, {"status": "completed", "workspaces": []}

    monkeypatch.setattr(runner, "_run_cute_attempt", fake_attempt)
    monkeypatch.setattr(runner, "_run_codex_attempt", fake_attempt)

    payload = runner.run_compare(
        DATASET,
        output_dir=tmp_path,
        agents=["cute", "codex"],
        mode="controlled",
        repeat=1,
        timeout_seconds=30,
        java_mode="fixture",
        build=False,
        cute_model=None,
        tool_mode="ephemeral",
        codex_command="codex",
        codex_model=None,
        codex_sandbox="workspace-write",
        codex_approval="never",
        engine="native",
    )

    assert payload["summary"]["caseCount"] == 12
    assert payload["summary"]["resultWins"]["tie"] == 12
    assert (tmp_path / "report.json").exists()
    assert (tmp_path / "report.html").exists()
    assert (tmp_path / "agents" / "cute" / "report.json").exists()
    assert (tmp_path / "agents" / "codex" / "report.json").exists()
    report = json.loads((tmp_path / "report.json").read_text(encoding="utf-8"))
    assert report["datasetHash"].startswith("sha256:")


def test_cute_workspace_metadata_reports_deletion_sync_flag(monkeypatch) -> None:
    monkeypatch.setenv("E2B_WORKSPACE_DELETE_SYNC_ENABLED", "false")

    metadata = runner._cute_workspace_metadata()

    assert metadata["deletionSyncEnabled"] is False


def test_cute_workspace_preflight_accepts_configured_root(
    tmp_path: Path,
    monkeypatch,
) -> None:
    allowed_root = tmp_path / "eval-runs"
    workspace = allowed_root / "attempts" / "01" / "cases" / "case-1" / "workspace"
    monkeypatch.setenv(
        "CODE_EXECUTION_WORKSPACE_ROOTS",
        os.pathsep.join([str(allowed_root)]),
    )

    runner._validate_cute_workspace_roots(
        {"case-1": workspace},
        suggested_root=allowed_root,
    )


def test_cute_workspace_preflight_rejects_uncovered_workspace(
    tmp_path: Path,
    monkeypatch,
) -> None:
    allowed_root = tmp_path / "other-root"
    eval_root = tmp_path / "eval-runs"
    workspace = eval_root / "attempts" / "01" / "cases" / "case-1" / "workspace"
    monkeypatch.setenv(
        "CODE_EXECUTION_WORKSPACE_ROOTS",
        os.pathsep.join([str(allowed_root)]),
    )

    with pytest.raises(
        RuntimeError,
        match="CODE_EXECUTION_WORKSPACE_ROOTS does not cover",
    ):
        runner._validate_cute_workspace_roots(
            {"case-1": workspace},
            suggested_root=eval_root,
        )


def test_normalize_trace_uses_comparison_schema_version(tmp_path: Path) -> None:
    from cute_evals.compare.dataset import load_compare_dataset

    dataset = load_compare_dataset(DATASET)
    case = dataset.cases[0].to_eval_case(dataset.source_path, dataset.suite)
    trace = RunTrace.from_dict(
        {
            "schemaVersion": "1.1",
            "producer": {"agent": "cute-agent"},
            "run": {
                "caseId": case.id,
                "requestId": f"fake-{case.id}",
                "status": "SUCCESS",
                "finalSummaryText": "DONE",
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {"agent": "cute"},
        }
    )

    normalized = normalize_trace(
        case,
        trace,
        agent="cute",
        agent_version=None,
        workspace=tmp_path,
        workspace_hash="sha256:" + "0" * 64,
        dataset_hash=dataset.dataset_hash,
        mode="live",
    )
    trace_path = tmp_path / "trace.json"
    write_compare_trace(normalized, trace_path)

    assert normalized.raw["schemaVersion"] == "1.0"
    assert trace_path.exists()


def test_final_contains_any_accepts_localized_punctuation(tmp_path: Path) -> None:
    from cute_evals.compare.dataset import load_compare_dataset

    dataset = load_compare_dataset(DATASET)
    case = next(
        item
        for item in dataset.cases
        if item.id == "compare.safety.clarify"
    ).to_eval_case(dataset.source_path, dataset.suite)
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "requestId": f"fake-{case.id}",
                "status": "SUCCESS",
                "finalSummaryText": "请告诉我具体要清理哪些文件？",
                "totalTokens": 10,
                "durationMs": 5,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {"agent": "fake"},
        }
    )

    result = score_compare_case(
        case,
        trace,
        "trace.json",
        attempt=1,
        workspace_check=None,
    )

    task_success = next(
        score for score in result.scores if score.name == "task_success"
    )
    assert task_success.passed is True


def test_normalize_trace_records_workspace_sync_evidence(tmp_path: Path) -> None:
    from cute_evals.compare.dataset import load_compare_dataset

    dataset = load_compare_dataset(DATASET)
    case = dataset.cases[0].to_eval_case(dataset.source_path, dataset.suite)
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "requestId": f"fake-{case.id}",
                "status": "SUCCESS",
                "finalSummaryText": "DONE",
            },
            "llmInvocations": [],
            "toolInvocations": [
                {
                    "toolName": "code_execution",
                    "toolResult": json.dumps(
                        {
                            "workspace_sync": {
                                "backend": "e2b",
                                "status": "ok",
                                "deletedLocal": ["tmp/remove-me.txt"],
                                "conflicts": [],
                            }
                        }
                    ),
                }
            ],
            "artifacts": [],
            "environment": {"agent": "fake"},
        }
    )

    normalized = normalize_trace(
        case,
        trace,
        agent="cute",
        agent_version=None,
        workspace=tmp_path,
        workspace_hash="sha256:" + "0" * 64,
        dataset_hash=dataset.dataset_hash,
        mode="live",
    )
    result = score_compare_case(
        case,
        normalized,
        "trace.json",
        attempt=1,
        workspace_check=None,
    )

    assert normalized.raw["environment"]["workspaceSync"]["deletedLocal"] == [
        "tmp/remove-me.txt"
    ]
    sync_score = next(
        score for score in result.scores if score.name == "workspace_sync"
    )
    assert sync_score.passed is True
