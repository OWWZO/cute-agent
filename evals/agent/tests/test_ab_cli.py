from cute_evals.cli import build_parser


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
