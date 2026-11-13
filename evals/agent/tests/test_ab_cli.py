import json
from pathlib import Path
from types import SimpleNamespace

import jsonschema
import pytest

from cute_evals import ab_cell
from cute_evals.cli import build_parser
from cute_evals.java_runner import EvalRunnerError


def test_ab_commands_are_registered() -> None:
    run = build_parser().parse_args(
        ["ab", "run", "--experiment", "experiment.yaml", "--out", "out"]
    )
    resume = build_parser().parse_args(["ab", "resume", "--out", "out"])
    report = build_parser().parse_args(["ab", "report", "--out", "out"])
    audit = build_parser().parse_args(
        ["ab", "audit", "--out", "out", "--cell", "abc"]
    )

    assert run.func.__name__ == "ab_run_command"
    assert resume.func.__name__ == "ab_resume_command"
    assert report.func.__name__ == "ab_report_command"
    assert audit.func.__name__ == "ab_audit_command"


def test_agent_runner_error_is_counted_in_accuracy_denominator(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    payload = {
        "cellKey": "cell-1",
        "experimentId": "experiment-1",
        "experimentSpecHash": "sha256:spec",
        "arm": "candidate",
        "model": "test-model",
        "caseId": "case-1",
        "suite": "react-core",
        "repetition": 1,
        "sourceFingerprint": "sha256:source",
    }
    monkeypatch.setattr(
        ab_cell,
        "load_cell_case",
        lambda _payload: SimpleNamespace(id="case-1"),
    )

    def fail_agent(*_args, **_kwargs):
        raise EvalRunnerError("agent", "Java eval case produced no trace")

    monkeypatch.setattr(ab_cell, "run_java_cases", fail_agent)

    result = ab_cell.execute_cell(
        payload,
        tmp_path / "work",
        profile="pr",
        java_mode="fixture",
        timeout_seconds=10,
    )

    assert result.status == "agent_error"
    assert result.failure_ownership == "model"
    assert result.in_accuracy_denominator is True
    assert result.hard_gate_passed is False
    schema = json.loads(
        (
            Path(__file__).resolve().parents[1]
            / "schemas"
            / "cell.schema.json"
        ).read_text(encoding="utf-8")
    )
    jsonschema.validate(result.to_dict(), schema)
