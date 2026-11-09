from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

from cute_evals import java_runner
from cute_evals.case_loader import load_case_file, load_cases
from cute_evals.cli import build_parser
from cute_evals.experiment import REPO_ROOT
from cute_evals.java_runner import EvalRunnerError, run_java_cases
from cute_evals.models import RunTrace
from cute_evals.scorers import score_case

CASES = REPO_ROOT / "evals/agent/cases/ab/compaction-carry-forward"


def _case(name: str):
    return load_case_file(CASES / name)


def _trace(final_text: str, context_events: list[dict]) -> RunTrace:
    return RunTrace(
        case_id="case",
        request_id="request",
        status="SUCCESS",
        final_text=final_text,
        total_tokens=100,
        duration_ms=10,
        run_uid="run",
        user_id_hash="user",
        llm_invocations=[],
        tool_invocations=[],
        artifacts=[],
        raw={"run": {"status": "SUCCESS", "finalSummaryText": final_text}},
        context_events=context_events,
    )


def test_compaction_suite_has_real_groups_and_negative_cases() -> None:
    cases = load_cases(CASES, suite="compaction-carry-forward")

    assert len(cases) == 8
    assert all(case.hard_gate for case in cases)
    assert sum(
        case.compaction["mode"] == "uncompressed-control" for case in cases
    ) == 1
    assert all(case.compaction["messages"] for case in cases)
    assert all(
        case.compaction.get("budget")
        for case in cases
        if case.compaction["mode"] == "compressed"
    )
    ids = {case.id for case in cases}
    assert "compaction-carry-forward.false-memory" in ids
    assert "compaction-carry-forward.pointer-recovery" in ids


def test_compaction_scorer_requires_real_event_but_control_stays_clean() -> None:
    compressed = _case("01-compressed-goal-constraints.yaml")
    control = _case("02-uncompressed-control.yaml")
    event = {
        "eventId": "compaction:1",
        "type": "compaction",
        "status": "success",
        "strategy": "full-llm",
        "beforeTokens": 2000,
        "afterTokens": 500,
        "summaryHash": "sha256:abc",
    }

    compressed_result = score_case(
        compressed,
        _trace(
            "GOAL-ALPHA CONSTRAINT-NO-DELETE NEXT-STEP-REPORT",
            [event],
        ),
        "trace.json",
    )
    control_result = score_case(
        control,
        _trace(
            "GOAL-ALPHA CONSTRAINT-NO-DELETE NEXT-STEP-REPORT",
            [],
        ),
        "trace.json",
    )

    compressed_scores = {score.name: score for score in compressed_result.scores}
    control_scores = {score.name: score for score in control_result.scores}
    assert compressed_scores["compaction_triggered"].passed is True
    assert compressed_scores["compression_ratio"].value == 4.0
    assert compressed_scores["summary_hash_present"].passed is True
    assert control_scores["compaction_control_clean"].passed is True
    assert compressed_result.passed
    assert control_result.passed


def test_compaction_scorer_fails_when_real_event_is_missing() -> None:
    case = _case("01-compressed-goal-constraints.yaml")

    result = score_case(
        case,
        _trace("GOAL-ALPHA CONSTRAINT-NO-DELETE NEXT-STEP-REPORT", []),
        "trace.json",
    )

    scores = {score.name: score for score in result.scores}
    assert scores["compaction_triggered"].passed is False
    assert not result.passed


def test_cli_accepts_compaction_mode() -> None:
    args = build_parser().parse_args(
        [
            "run",
            "--suite",
            "compaction-carry-forward",
            "--profile",
            "nightly",
            "--java-mode",
            "compaction",
        ]
    )
    assert args.suite == "compaction-carry-forward"
    assert args.java_mode == "compaction"


def test_java_runner_dispatches_compaction_cli_and_payload(
    tmp_path: Path,
    monkeypatch,
) -> None:
    captured: dict = {}

    def fake_run(command, timeout):
        captured["command"] = command
        exec_args = next(
            item for item in command if item.startswith("-Dexec.args=")
        )
        cases_path = Path(exec_args.split()[1])
        captured["manifest"] = json.loads(cases_path.read_text(encoding="utf-8"))
        return subprocess.CompletedProcess(command, 1, "", "failed")

    monkeypatch.setattr(java_runner, "_run", fake_run)
    case = _case("01-compressed-goal-constraints.yaml")

    with pytest.raises(EvalRunnerError, match="Java eval runner failed"):
        run_java_cases(
            [case],
            tmp_path / "out",
            mode="compaction",
            profile="nightly",
            build=False,
        )

    assert any(
        "EvalCompactionCarryForwardCli" in item
        for item in captured["command"]
    )
    assert captured["manifest"]["cases"][0]["compaction"]["mode"] == "compressed"
