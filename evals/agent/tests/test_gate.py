from __future__ import annotations

import json
from pathlib import Path

from cute_evals.gate import evaluate_gate


def _write(path: Path, payload: dict) -> Path:
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


def test_gate_combines_eval_and_ab_reports(tmp_path: Path) -> None:
    eval_report = _write(
        tmp_path / "eval.json",
        {
            "summary": {
                "failed": 0,
                "unstable": 0,
                "unverified": 0,
                "passAtK": 1.0,
            }
        },
    )
    ab_report = _write(
        tmp_path / "ab.json",
        {
            "recommendation": "inconclusive",
            "cells": [],
            "statusCounts": {"passed": 2},
        },
    )

    report = evaluate_gate(
        [eval_report, ab_report],
        tmp_path / "out",
        min_pass_at_k=1.0,
    )

    assert report["passed"] is True
    assert (tmp_path / "out" / "gate-report.json").exists()


def test_gate_blocks_failed_or_missing_reports(tmp_path: Path) -> None:
    eval_report = _write(
        tmp_path / "eval.json",
        {
            "summary": {
                "failed": 1,
                "unstable": 0,
                "unverified": 0,
                "passAtK": 0.0,
            }
        },
    )

    report = evaluate_gate(
        [eval_report, tmp_path / "missing.json"],
        tmp_path / "out",
        min_pass_at_k=1.0,
    )

    assert report["passed"] is False
    assert report["reports"][0]["reasons"] == ["failed=1", "passAtK=0.0"]
    assert report["reports"][1]["kind"] == "missing"
