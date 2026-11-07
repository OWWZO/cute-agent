from __future__ import annotations

import json
from pathlib import Path

import jsonschema
import yaml

from cute_evals.ab_runner import resume_experiment, run_experiment
from cute_evals.experiment import REPO_ROOT, CellResult, CellSpec, now_iso


def _write_case(directory: Path, case_id: str) -> None:
    payload = {
        "id": case_id,
        "suite": "ab-smoke",
        "version": 1,
        "hardGate": True,
        "input": {"message": "return OK"},
        "expected": {"finalContains": ["OK"], "maxSteps": 1},
        "budgets": {"maxTotalTokens": 200, "maxWallMs": 1000},
        "script": [{"type": "final", "content": "OK"}],
    }
    (directory / f"{case_id}.yaml").write_text(
        yaml.safe_dump(payload, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )


def _write_spec(
    path: Path,
    case_dir: Path,
    *,
    repetitions: int = 1,
    retries: int = 0,
    cases: int = 1,
) -> None:
    case_dir.mkdir(parents=True, exist_ok=True)
    for index in range(cases):
        _write_case(case_dir, f"ab-smoke.{index}")
    payload = {
        "schemaVersion": "1.0",
        "experimentId": "ab-smoke",
        "suite": "ab-smoke",
        "caseDirs": [str(case_dir)],
        "profile": "pr",
        "javaMode": "fixture",
        "base": {"source": "local", "root": "."},
        "candidate": {"source": "local", "root": "."},
        "models": ["fixture-model"],
        "repetitions": repetitions,
        "seed": 1,
        "parallelism": 1,
        "timeoutSeconds": 10,
        "retries": retries,
        "isolation": {
            "mode": "process",
            "workspace": "per-cell",
            "database": "experiment",
            "allowDirty": True,
        },
        "budgets": {"maxUsd": 1, "maxWallMinutes": 10},
        "thresholds": {
            "maxAccuracyRegression": 0.05,
            "maxTokenRegressionRatio": 0.10,
        },
    }
    path.write_text(
        yaml.safe_dump(payload, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )


def _result(cell: CellSpec, status: str, score: float) -> CellResult:
    passed = status == "passed"
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
        task_score=score,
        hard_gate_passed=passed,
        in_accuracy_denominator=status in {"passed", "failed", "model_error"},
        failure_ownership="none" if passed else "model",
        metrics={
            "apiTurns": 1,
            "toolCalls": 0,
            "toolErrors": 0,
            "retries": 0,
            "totalTokens": 100,
            "wallMs": 10,
            "firstTokenMs": 2.0,
            "estimatedCost": 0.0,
        },
        scores=[],
        retry_history=[],
        started_at=now_iso(),
        finished_at=now_iso(),
        source_fingerprint=cell.source_fingerprint,
        trace_path=None,
        transcript_path=None,
    )


def test_run_and_resume_do_not_repeat_completed_cells(tmp_path: Path) -> None:
    case_dir = tmp_path / "cases"
    spec_path = tmp_path / "experiment.yaml"
    _write_spec(spec_path, case_dir, repetitions=2)
    output = tmp_path / "out"
    calls: list[str] = []

    def executor(cell: CellSpec, attempt: int) -> CellResult:
        calls.append(cell.cell_key)
        return _result(cell, "passed", 1.0)

    report = run_experiment(spec_path, output, executor=executor)
    assert report["summary"]["actualCellCount"] == 4
    assert len(calls) == 4
    report_schema = json.loads(
        (REPO_ROOT / "evals/agent/schemas/ab-report.schema.json").read_text(
            encoding="utf-8"
        )
    )
    jsonschema.validate(
        json.loads((output / "report.json").read_text(encoding="utf-8")),
        report_schema,
    )

    def should_not_run(cell: CellSpec, attempt: int) -> CellResult:
        raise AssertionError(f"resume reran {cell.cell_key}")

    resumed = resume_experiment(output, executor=should_not_run)

    assert resumed["summary"]["actualCellCount"] == 4
    assert resumed["recommendation"] == "inconclusive"


def test_model_error_stays_in_denominator(tmp_path: Path) -> None:
    case_dir = tmp_path / "cases"
    spec_path = tmp_path / "experiment.yaml"
    _write_spec(spec_path, case_dir)

    def executor(cell: CellSpec, attempt: int) -> CellResult:
        if cell.arm == "base":
            return _result(cell, "passed", 1.0)
        return _result(cell, "model_error", 0.0)

    report = run_experiment(spec_path, tmp_path / "out", executor=executor)

    assert report["statusCounts"] == {"model_error": 1, "passed": 1}
    assert report["arms"]["candidate"]["validCellCount"] == 1
    assert report["summary"]["sameDenominator"] is True
    assert report["recommendation"] == "block"


def test_infra_error_is_reported_but_not_in_denominator(tmp_path: Path) -> None:
    case_dir = tmp_path / "cases"
    spec_path = tmp_path / "experiment.yaml"
    _write_spec(spec_path, case_dir)

    def executor(cell: CellSpec, attempt: int) -> CellResult:
        if cell.arm == "base":
            return _result(cell, "infra_error", 0.0)
        return _result(cell, "passed", 1.0)

    report = run_experiment(spec_path, tmp_path / "out", executor=executor)

    assert report["arms"]["base"]["validCellCount"] == 0
    assert report["statusCounts"]["infra_error"] == 1
    assert report["summary"]["sameDenominator"] is False
    assert report["recommendation"] == "needs-more-runs"


def test_retryable_infra_error_retries_the_same_cell(tmp_path: Path) -> None:
    case_dir = tmp_path / "cases"
    spec_path = tmp_path / "experiment.yaml"
    _write_spec(spec_path, case_dir, retries=1)
    calls: list[int] = []

    def executor(cell: CellSpec, attempt: int) -> CellResult:
        calls.append(attempt)
        if attempt == 0:
            return _result(cell, "infra_error", 0.0)
        return _result(cell, "passed", 1.0)

    report = run_experiment(spec_path, tmp_path / "out", executor=executor)

    assert calls == [0, 1, 0, 1]  # each arm retries once
    assert report["arms"]["base"]["tasks"]["ab-smoke.0"]["retryCount"] == 1
    assert report["statusCounts"] == {"passed": 2}
    assert report["recommendation"] == "inconclusive"


def test_three_consecutive_infra_errors_trip_fuse(tmp_path: Path) -> None:
    case_dir = tmp_path / "cases"
    spec_path = tmp_path / "experiment.yaml"
    _write_spec(spec_path, case_dir, cases=3, retries=0)

    def executor(cell: CellSpec, attempt: int) -> CellResult:
        return _result(cell, "infra_error", 0.0)

    report = run_experiment(spec_path, tmp_path / "out", executor=executor)

    assert report["summary"]["actualCellCount"] == 3
    assert report["summary"]["expectedCellCount"] == 6
    assert report["recommendation"] == "needs-more-runs"
