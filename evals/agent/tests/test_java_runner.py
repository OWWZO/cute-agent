from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

import cute_evals.java_runner as java_runner
from cute_evals.case_loader import load_case_file
from cute_evals.experiment import REPO_ROOT
from cute_evals.java_runner import (
    EvalRunnerError,
    export_ledger_traces,
    run_java_cases,
)


def _python_case():
    return load_case_file(
        REPO_ROOT
        / "evals"
        / "agent"
        / "cases"
        / "rag"
        / "core"
        / "01-cited-answer.yaml"
    )


def test_compile_uses_per_arm_maven_repository(
    tmp_path: Path,
    monkeypatch,
) -> None:
    commands: list[list[str]] = []

    def fake_run(command, timeout):
        commands.append(command)
        return subprocess.CompletedProcess(command, 0, "", "")

    monkeypatch.setenv("CUTE_EVAL_MAVEN_REPO", str(tmp_path / "m2"))
    monkeypatch.setattr(java_runner, "_run", fake_run)

    java_runner.compile_java_fixture_runner()

    expected = f"-Dmaven.repo.local={tmp_path / 'm2'}"
    assert commands
    assert all(expected in command for command in commands)


def test_java_case_exec_uses_per_arm_maven_repository(
    tmp_path: Path,
    monkeypatch,
) -> None:
    commands: list[list[str]] = []

    def fake_run(command, timeout):
        commands.append(command)
        return subprocess.CompletedProcess(command, 1, "", "failed")

    monkeypatch.setenv("CUTE_EVAL_MAVEN_REPO", str(tmp_path / "m2"))
    monkeypatch.setattr(java_runner, "_run", fake_run)

    with pytest.raises(EvalRunnerError, match="Java eval runner failed"):
        run_java_cases([_python_case()], tmp_path / "out", build=False)

    expected = f"-Dmaven.repo.local={tmp_path / 'm2'}"
    assert len(commands) == 1
    assert expected in commands[0]


def test_ledger_export_rejects_temp_dir_with_whitespace(
    tmp_path: Path,
    monkeypatch,
) -> None:
    temp_root = tmp_path / "with space"
    temp_root.mkdir()
    monkeypatch.setenv("CUTE_EVAL_JAVA_TMP", str(temp_root))

    with pytest.raises(EvalRunnerError, match="must not contain whitespace"):
        export_ledger_traces(
            tmp_path / "out",
            run_id="run-1",
            build=False,
        )


def test_java_cases_reject_temp_dir_with_whitespace(
    tmp_path: Path,
    monkeypatch,
) -> None:
    temp_root = tmp_path / "with space"
    temp_root.mkdir()
    monkeypatch.setenv("CUTE_EVAL_JAVA_TMP", str(temp_root))

    with pytest.raises(EvalRunnerError, match="must not contain whitespace"):
        run_java_cases([_python_case()], tmp_path / "out", build=False)


def test_ledger_export_rejects_time_values_with_whitespace() -> None:
    with pytest.raises(ValueError, match="must not contain whitespace"):
        export_ledger_traces(
            Path("out"),
            from_time="2026-11-06 10:00:00",
            to_time="2026-11-06T11:00:00",
            build=False,
        )
