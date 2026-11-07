from __future__ import annotations

import json
import subprocess
from pathlib import Path

import jsonschema
import yaml

from cute_evals.case_loader import load_cases
from cute_evals.experiment import (
    REPO_ROOT,
    CellResult,
    arm_fingerprint,
    build_cells,
    isolated_environment,
    load_experiment,
    load_experiment_cases,
    now_iso,
    prepare_arm_roots,
    reusable_cell,
    write_json_atomic,
)


def test_experiment_has_stable_cell_keys() -> None:
    spec = load_experiment(
        REPO_ROOT / "evals/agent/experiments/tool-discovery-ab.yaml"
    )
    cases = load_experiment_cases(spec, suite=spec.suite)
    root = REPO_ROOT
    roots = {"base": root, "candidate": root}
    fingerprints = {
        "base": arm_fingerprint("base", spec.base, root),
        "candidate": arm_fingerprint("candidate", spec.candidate, root),
    }

    first = build_cells(spec, cases, roots, fingerprints)
    second = build_cells(spec, cases, roots, fingerprints)

    assert len(first) == 12
    assert [cell.cell_key for cell in first] == [
        cell.cell_key for cell in second
    ]
    assert len({cell.cell_key for cell in first}) == len(first)


def test_all_five_ab_suites_have_six_cases() -> None:
    base = REPO_ROOT / "evals/agent/cases/ab"
    for suite in (
        "hostile-files",
        "tool-discovery",
        "session-recall",
        "compaction-recall",
        "clarify-multiturn",
    ):
        cases = load_cases(base / suite, suite=suite)
        assert len(cases) == 6
        assert all(case.hard_gate for case in cases)


def test_isolated_environment_uses_cell_home_and_drops_unlisted_secrets(
    tmp_path: Path,
    monkeypatch,
) -> None:
    monkeypatch.setenv("SECRET_TOKEN", "must-not-leak")
    monkeypatch.setenv("CUTE_EVAL_DB_PASSWORD", "allowed-eval-credential")

    env = isolated_environment(
        arm_env={"EXPERIMENT_ARM": "candidate"},
        arm_root=tmp_path,
        cell_work_dir=tmp_path / "cell",
    )

    assert env["HOME"] == str(tmp_path / "cell/home")
    assert env["USERPROFILE"] == str(tmp_path / "cell/home")
    assert env["EXPERIMENT_ARM"] == "candidate"
    assert env["CUTE_EVAL_DB_PASSWORD"] == "allowed-eval-credential"
    assert "SECRET_TOKEN" not in env
    assert env["CUTE_EVAL_BROWSER_DISABLED"] == "1"


def test_cell_and_report_payloads_validate_against_schemas(tmp_path: Path) -> None:
    spec = load_experiment(
        REPO_ROOT / "evals/agent/experiments/tool-discovery-ab.yaml"
    )
    cases = load_experiment_cases(spec, suite=spec.suite)
    roots = {"base": REPO_ROOT, "candidate": REPO_ROOT}
    fingerprints = {
        "base": arm_fingerprint("base", spec.base, REPO_ROOT),
        "candidate": arm_fingerprint("candidate", spec.candidate, REPO_ROOT),
    }
    cell = build_cells(spec, cases, roots, fingerprints)[0]
    result = CellResult(
        cell_key=cell.cell_key,
        experiment_id=cell.experiment_id,
        experiment_spec_hash=cell.experiment_spec_hash,
        arm=cell.arm,
        model=cell.model,
        case_id=cell.case.id,
        suite=cell.case.suite,
        repetition=cell.repetition,
        status="passed",
        task_score=1.0,
        hard_gate_passed=True,
        in_accuracy_denominator=True,
        failure_ownership="none",
        metrics={
            "apiTurns": 1,
            "toolCalls": 0,
            "toolErrors": 0,
            "retries": 0,
            "totalTokens": 100,
            "wallMs": 10,
            "firstTokenMs": 5.0,
            "estimatedCost": 0.0,
        },
        scores=[],
        retry_history=[],
        started_at=now_iso(),
        finished_at=now_iso(),
        source_fingerprint=cell.source_fingerprint,
    )
    cell_schema = json.loads(
        (REPO_ROOT / "evals/agent/schemas/cell.schema.json").read_text(
            encoding="utf-8"
        )
    )
    jsonschema.validate(result.to_dict(), cell_schema)
    write_json_atomic(tmp_path / "cell.json", result.to_dict())
    assert (tmp_path / "cell.json").exists()
    assert reusable_cell(cell, result)


def test_git_arms_use_independent_worktrees(
    tmp_path: Path,
    monkeypatch,
) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
    (repo / "README.md").write_text("base\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=repo, check=True)
    subprocess.run(
        [
            "git",
            "-c",
            "user.name=eval-test",
            "-c",
            "user.email=eval@test.local",
            "commit",
            "-qm",
            "init",
        ],
        cwd=repo,
        check=True,
    )
    spec_path = repo / "experiment.yaml"
    spec_path.write_text(
        yaml.safe_dump(
            {
                "schemaVersion": "1.0",
                "experimentId": "git-worktree-test",
                "suite": "tool-discovery",
                "base": {"source": "git", "ref": "HEAD"},
                "candidate": {"source": "git", "ref": "HEAD"},
                "models": ["fixture-model"],
                "repetitions": 1,
                "seed": 1,
                "parallelism": 1,
                "timeoutSeconds": 10,
                "isolation": {
                    "mode": "process",
                    "workspace": "per-cell",
                    "database": "experiment",
                    "allowDirty": True,
                },
                "budgets": {"maxUsd": 0, "maxWallMinutes": 10},
                "thresholds": {
                    "maxAccuracyRegression": 0.05,
                    "maxTokenRegressionRatio": 0.10,
                },
            },
            sort_keys=False,
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv("CUTE_EVAL_REPO_ROOT", str(repo))
    spec = load_experiment(spec_path)

    roots = prepare_arm_roots(spec, tmp_path / "out")

    assert roots["base"] != roots["candidate"]
    assert (roots["base"] / "README.md").read_text(encoding="utf-8") == "base\n"
    assert (roots["candidate"] / "README.md").read_text(encoding="utf-8") == "base\n"
