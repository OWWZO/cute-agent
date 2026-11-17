import copy
from types import SimpleNamespace

import pytest

import cute_evals.cli as cli
from cute_evals.benchmarks.base import (
    BenchmarkRun,
    BenchmarkTask,
    PreparedTask,
)
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


def test_workbuddybench_is_registered_for_internal_split() -> None:
    args = build_parser().parse_args(
        [
            "benchmark",
            "--name",
            "workbuddybench",
            "--split",
            "internal",
            "--java-mode",
            "live",
        ]
    )

    assert args.name == "workbuddybench"
    assert args.split == "internal"
    assert args.func.__name__ == "benchmark_command"


def test_workbuddybench_isolates_attempt_dirs_per_task(
    tmp_path,
    monkeypatch,
) -> None:
    monkeypatch.setenv(
        "CODE_EXECUTION_WORKSPACE_ROOTS",
        str(tmp_path),
    )
    tasks = [
        BenchmarkTask(
            id=f"task-{index}",
            category="bug-fix",
            question=f"Fix task {index}.",
            functions=[],
            ground_truth=None,
            raw={},
        )
        for index in (1, 2)
    ]
    prepared_workspaces = []
    run_output_dirs = []
    preflight_calls = []
    case_env_overrides = []

    class Adapter:
        name = "workbuddybench"
        version = "test"
        upstream = "workbuddy"
        score_label = "compatibility-subset"
        docker_verifier = object()

        def __init__(self, *args, **kwargs):
            pass

        def tasks(self, split):
            assert split == "internal"
            return tasks

        def prepare(self, task, workspace):
            prepared_workspaces.append(workspace)
            metadata = {
                "workspaceRoot": str(workspace),
                "verificationOutputDir": str(
                    workspace.parent.parent / "verifier" / task.id
                ),
                "agentResultPath": str(
                    workspace.parent.parent / "result.json"
                ),
            }
            return PreparedTask(task, workspace, metadata)

        def case_for_task(self, task):
            return EvalCase(
                id=task.id,
                suite="workbuddybench-internal",
                version=1,
                hard_gate=True,
                query=task.question,
                expected=ExpectedSpec(),
                budgets=BudgetSpec(),
                script=[],
                source_path="task.toml",
            )

        def collect(self, task, trace, trace_path, *, prepared):
            return BenchmarkRun(
                task=task,
                trace=trace,
                trace_path=trace_path,
                calls=[],
                metadata=dict(prepared.metadata),
            )

        def score(self, run, *, case=None):
            return CaseResult(
                case=case,
                trace=run.trace,
                scores=[],
                trace_path=run.trace_path,
            )

    adapter = Adapter()
    monkeypatch.setattr(
        cli,
        "WorkBuddyBenchAdapter",
        lambda *args, **kwargs: adapter,
    )
    monkeypatch.setattr(cli, "compile_java_fixture_runner", lambda: None)

    def fake_preflight(workspace, report_path, **kwargs):
        preflight_calls.append((workspace, report_path, kwargs))
        return {"requiredPassed": True, "checks": []}

    monkeypatch.setattr(
        cli,
        "run_workbuddy_tool_preflight",
        fake_preflight,
    )

    def fake_run_java_cases(cases, output_dir, **kwargs):
        run_output_dirs.append(output_dir)
        case_env_overrides.append(kwargs.get("env_overrides"))
        output_dir.mkdir(parents=True, exist_ok=True)
        (output_dir / "result.json").write_text(
            f'{{"case": "{cases[0].id}"}}',
            encoding="utf-8",
        )
        return {
            cases[0].id: RunTrace.from_dict(
                {
                    "run": {
                        "caseId": cases[0].id,
                        "requestId": "request",
                        "status": "SUCCESS",
                        "finalSummaryText": "done",
                    },
                    "llmInvocations": [],
                    "toolInvocations": [],
                    "artifacts": [],
                    "environment": {},
                }
            )
        }

    monkeypatch.setattr(cli, "run_java_cases", fake_run_java_cases)
    reported = {}

    def fake_report(**kwargs):
        reported.update(kwargs)
        return 0

    monkeypatch.setattr(cli, "_report_benchmark", fake_report)
    args = SimpleNamespace(
        agent="cute",
        name="workbuddybench",
        split="internal",
        source_dir=str(tmp_path / "source"),
        manifest=None,
        limit=None,
        java_mode="live",
        repeat=1,
        out=str(tmp_path / "out"),
        skip_build=True,
        profile="nightly",
        timeout=10,
        budget_mode="product",
        capability_token_multiplier=8.0,
        capability_wall_multiplier=4.0,
        capability_tool_multiplier=4.0,
        capability_step_multiplier=4.0,
    )

    assert cli._run_workbuddybench_benchmark(args) == 0

    attempt_dir = tmp_path / "out" / "attempts" / "01"
    assert prepared_workspaces == [
        attempt_dir / "task-1" / "workspace" / "task-1",
        attempt_dir / "task-2" / "workspace" / "task-2",
    ]
    assert run_output_dirs == [
        attempt_dir / "task-1",
        attempt_dir / "task-2",
    ]
    assert len(preflight_calls) == 1
    assert preflight_calls[0][2]["build"] is False
    assert case_env_overrides == [
        {"CUTE_FILE_ARTIFACT_ALLOWED_ROOT": str((tmp_path / "out").resolve())},
        {"CUTE_FILE_ARTIFACT_ALLOWED_ROOT": str((tmp_path / "out").resolve())},
    ]
    assert [
        result.trace_path for result in reported["results"]
    ] == [
        "attempts/01/task-1/traces/task-1/trace.json",
        "attempts/01/task-2/traces/task-2/trace.json",
    ]


def test_workbuddybench_requires_code_execution_workspace_root(
    tmp_path,
    monkeypatch,
) -> None:
    allowed = tmp_path / "eval-root"
    output_dir = allowed / "workbuddy"
    monkeypatch.setenv(
        "CODE_EXECUTION_WORKSPACE_ROOTS",
        str(allowed),
    )

    cli._validate_workbuddy_execution_roots(output_dir)

    outside = tmp_path / "outside" / "workbuddy"
    with pytest.raises(
        ValueError,
        match="outside the Code Execution allowed roots",
    ):
        cli._validate_workbuddy_execution_roots(outside)


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
