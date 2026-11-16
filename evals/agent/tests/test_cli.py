import copy
from types import SimpleNamespace

import pytest

from cute_evals.benchmarks.budget import BudgetPolicy
from cute_evals.case_loader import load_case_file
from cute_evals.cli import (
    _report_benchmark,
    _case_dirs,
    _dataset_hash,
    _repo_root,
    build_parser,
    ops_schedule_command,
)
from cute_evals.models import (
    BudgetSpec,
    CaseResult,
    EvalCase,
    ExpectedSpec,
    RunTrace,
    ScoreRecord,
)


def test_run_rejects_multi_agent_switch() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args(["run", "--agent", "other"])


def test_private_badcase_uses_badcase_directory() -> None:
    assert _case_dirs(_repo_root(), "private-badcase")[0].name == "badcase"


def test_local_skill_simulation_uses_skill_directory() -> None:
    path = _case_dirs(_repo_root(), "skill-sim")[0]

    assert path.parent.name == "skill"
    assert path.name == "core"


def test_run_does_not_execute_scriptless_private_cases() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args(["run", "--suite", "private-badcase"])


def test_benchmark_command_is_registered() -> None:
    args = build_parser().parse_args(
        ["benchmark", "--name", "gaia", "--profile", "pr"]
    )

    assert args.func.__name__ == "benchmark_command"
    assert args.java_mode == "auto"
    assert args.budget_mode == "product"
    assert args.capability_token_multiplier == 8.0


def test_compare_commands_are_registered() -> None:
    validate = build_parser().parse_args(
        ["compare", "validate", "--dataset", "pilot.json"]
    )
    materialize = build_parser().parse_args(
        ["compare", "materialize", "--dataset", "pilot.json"]
    )
    run = build_parser().parse_args(
        [
            "compare",
            "run",
            "--dataset",
            "pilot.json",
            "--agents",
            "cute,codex",
            "--repeat",
            "3",
        ]
    )

    assert validate.func.__name__ == "compare_validate_command"
    assert materialize.func.__name__ == "compare_materialize_command"
    assert run.func.__name__ == "compare_run_command"
    assert run.mode == "controlled"
    assert run.codex_command == "codex"
    assert run.budget_mode == "product"


def test_capability_benchmark_gate_ignores_budget_slo(tmp_path) -> None:
    case = EvalCase(
        id="gate.case",
        suite="gate",
        version=1,
        hard_gate=True,
        query="test",
        expected=ExpectedSpec(),
        budgets=BudgetSpec(max_total_tokens=1),
        script=[],
        source_path="gate.json",
    )
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": case.id,
                "requestId": "request",
                "status": "SUCCESS",
                "finalSummaryText": "ok",
                "totalTokens": 2,
                "durationMs": 1,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {},
            "raw": {},
        }
    )
    result = CaseResult(
        case=case,
        trace=trace,
        scores=[
            ScoreRecord(
                case_id=case.id,
                scorer="official",
                name="official_exact_match",
                value=True,
                unit="boolean",
                passed=True,
                hard_gate=True,
                status="verified",
            ),
            ScoreRecord(
                case_id=case.id,
                scorer="budget",
                name="token_budget",
                value=2,
                unit="tokens",
                passed=False,
                hard_gate=True,
                status="verified",
            ),
        ],
        trace_path="trace.json",
    )

    class Adapter:
        name = "fixture"
        version = "1"
        upstream = "fixture"
        score_label = "fixture"

        def report_metadata(self):
            return {
                "name": self.name,
                "version": self.version,
                "split": "fixture",
                "datasetHash": "sha256:test",
                "scoreLabel": self.score_label,
            }

    args = SimpleNamespace(profile="nightly", require_judge=False)

    capability = _report_benchmark(
        adapter=Adapter(),
        args=args,
        results=[result],
        output_dir=tmp_path / "capability",
        mode="live",
        repeat=1,
        budget_policy=BudgetPolicy(mode="capability"),
    )
    product = _report_benchmark(
        adapter=Adapter(),
        args=args,
        results=[result],
        output_dir=tmp_path / "product",
        mode="live",
        repeat=1,
        budget_policy=BudgetPolicy(mode="product"),
    )

    assert capability == 0
    assert product == 1


def test_phase3_benchmarks_are_registered() -> None:
    for name in ("gaia", "tau2"):
        args = build_parser().parse_args(["benchmark", "--name", name])
        assert args.name == name
        assert args.func.__name__ == "benchmark_command"


def test_rag_python_benchmark_is_registered_for_internal_split() -> None:
    args = build_parser().parse_args(
        ["benchmark", "--name", "rag-python", "--split", "internal"]
    )

    assert args.name == "rag-python"
    assert args.split == "internal"
    assert args.mrag_url == "http://127.0.0.1:1601"


def test_official_skillsbench_benchmark_is_retired() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args(["benchmark", "--name", "skillsbench"])


def test_ops_commands_are_registered() -> None:
    record = build_parser().parse_args(
        ["ops", "record", "--report", "report.json"]
    )
    trend = build_parser().parse_args(["ops", "trend"])
    matrix = build_parser().parse_args(
        ["ops", "matrix", "--reports", "a/report.json", "--out", "matrix"]
    )
    sample = build_parser().parse_args(
        [
            "ops",
            "sample",
            "--out",
            "samples",
            "--from",
            "2026-11-06T00:00:00",
            "--to",
            "2026-11-07T00:00:00",
        ]
    )
    schedule = build_parser().parse_args(
        [
            "ops",
            "schedule",
            "--at",
            "02:30",
            "--run-now",
        ]
    )
    release = build_parser().parse_args(
        [
            "ops",
            "release",
            "--report",
            "report.json",
            "--out",
            "release",
            "--version",
            "v1",
            "--commit",
            "abc",
            "--model",
            "model",
        ]
    )

    assert record.func.__name__ == "ops_record_command"
    assert trend.func.__name__ == "ops_trend_command"
    assert matrix.func.__name__ == "ops_matrix_command"
    assert sample.func.__name__ == "ops_sample_command"
    assert schedule.func.__name__ == "ops_schedule_command"
    assert schedule.enable is False
    assert schedule.disable is False
    assert release.func.__name__ == "ops_release_command"


def test_ops_schedule_is_disabled_by_default(capsys) -> None:
    args = build_parser().parse_args(["ops", "schedule"])

    assert ops_schedule_command(args) == 0
    assert "disabled by default" in capsys.readouterr().out


def test_ops_schedule_enable_and_disable_are_mutually_exclusive() -> None:
    with pytest.raises(SystemExit):
        build_parser().parse_args(["ops", "schedule", "--enable", "--disable"])


def test_case_approve_command_is_registered() -> None:
    args = build_parser().parse_args(
        [
            "case-approve",
            "--draft",
            "draft.yaml",
            "--out",
            "approved.yaml",
            "--reviewer",
            "reviewer",
        ]
    )

    assert args.func.__name__ == "case_approve_command"


def test_dataset_hash_includes_execution_and_gate_fields() -> None:
    case = load_case_file(
        _repo_root()
        / "evals"
        / "agent"
        / "cases"
        / "rag"
        / "core"
        / "01-cited-answer.yaml"
    )
    original = _dataset_hash([case])

    variants = []
    hard_gate = copy.deepcopy(case)
    hard_gate.hard_gate = not hard_gate.hard_gate
    variants.append(hard_gate)
    tools = copy.deepcopy(case)
    tools.tools = [{"name": "changed"}]
    variants.append(tools)
    answer_contract = copy.deepcopy(case)
    answer_contract.answer_contract = {"format": "changed"}
    variants.append(answer_contract)
    baseline_trace = copy.deepcopy(case)
    baseline_trace.baseline_trace = "traces/changed.json"
    variants.append(baseline_trace)

    assert all(_dataset_hash([variant]) != original for variant in variants)
