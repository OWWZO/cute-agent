import pytest

from cute_evals.cli import (
    _case_dirs,
    _repo_root,
    build_parser,
    ops_schedule_command,
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


def test_phase3_benchmarks_are_registered() -> None:
    for name in ("gaia", "tau2"):
        args = build_parser().parse_args(["benchmark", "--name", name])
        assert args.name == name
        assert args.func.__name__ == "benchmark_command"


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
