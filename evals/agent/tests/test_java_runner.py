from __future__ import annotations

import subprocess
import json
from pathlib import Path

import pytest

import cute_evals.java_runner as java_runner
from cute_evals.case_loader import load_case_file
from cute_evals.experiment import REPO_ROOT
from cute_evals.java_runner import (
    EvalRunnerError,
    export_ledger_traces,
    run_java_cases,
    run_java_conversation,
)
from cute_evals.models import BudgetSpec, EvalCase, ExpectedSpec


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


def test_conversation_manifest_carries_memory_seed_and_turn_budgets(
    tmp_path: Path,
    monkeypatch,
) -> None:
    captured: dict = {}

    def fake_run(command, timeout):
        args = next(
            item
            for item in command
            if item.startswith("-Dexec.args=")
        )
        exec_args = args.removeprefix("-Dexec.args=")
        cases_path = Path(
            exec_args.split("--cases ", 1)[1].split(" --out ", 1)[0]
        )
        out_path = Path(
            exec_args.split("--out ", 1)[1].split(" --spring-profile ", 1)[0]
        )
        captured.update(
            json.loads(cases_path.read_text(encoding="utf-8"))
        )
        out_path.mkdir(parents=True, exist_ok=True)
        (out_path / "result.json").write_text(
            json.dumps(
                {
                    "cases": [
                        {
                            "caseId": "memory-case",
                            "conversationId": "session-1",
                            "status": "FAILED",
                            "finalText": "FINAL_ANSWER: ok",
                            "turns": [],
                            "tracePaths": [],
                            "error": "evaluation budget exceeded: total_tokens",
                            "errorCode": "EVAL_BUDGET_EXCEEDED",
                        }
                    ]
                }
            ),
            encoding="utf-8",
        )
        return subprocess.CompletedProcess(command, 0, "", "")

    monkeypatch.setattr(java_runner, "_run", fake_run)
    case = EvalCase(
        id="memory-case",
        suite="memoryagentbench-official-subset",
        version=1,
        hard_gate=True,
        query="Question",
        expected=ExpectedSpec(max_steps=10),
        budgets=BudgetSpec(
            max_total_tokens=80_000,
            max_wall_ms=300_000,
            max_tool_calls=12,
        ),
        script=[],
        source_path="test",
        user_id="eval-user",
        memory_seed={
            "userId": "eval-user",
            "namespace": "eval-ns",
            "sessions": [
                {
                    "sessionId": "eval-s1",
                    "timestamp": "2024-01-01 00:00:00",
                    "turns": [
                        {"role": "user", "content": "context"},
                    ],
                }
            ],
        },
        answer_contract={"linePrefix": "FINAL_ANSWER:"},
    )

    result = run_java_conversation(
        case,
        ["Question 1", "Question 2"],
        tmp_path / "out",
        independent_turns=True,
        build=False,
    )

    row = captured["cases"][0]
    assert row["turns"][0]["query"] == "Question 1"
    assert row["maxTotalTokens"] == 80_000
    assert row["memorySeed"]["sessions"][0]["sessionId"] == "eval-s1"
    assert row["answerContract"]["linePrefix"] == "FINAL_ANSWER:"
    assert row["independentTurns"] is True
    assert result.task_id == "memory-case"
    assert result.error_code == "EVAL_BUDGET_EXCEEDED"
