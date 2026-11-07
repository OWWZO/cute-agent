from __future__ import annotations

import json
from pathlib import Path

import pytest
import yaml

from cute_evals.case_loader import load_case_file, load_cases
from cute_evals.models import RunTrace
from cute_evals.trace_tools import (
    approve_case_draft,
    build_case_draft,
    compare_runs,
    counterfactual_replay,
    replay_trace,
    replay_trace_set,
    write_case_draft,
)


def _trace(*, failed_tool: bool = False) -> dict:
    return {
        "schemaVersion": "1.0",
        "producer": {"agent": "cute-agent"},
        "run": {
            "caseId": None,
            "requestId": "req-private-1",
            "sessionId": "sess-private-1",
            "status": "FAILED" if failed_tool else "SUCCESS",
            "queryText": "帮我读取配置并总结",
            "finalSummaryText": "读取失败",
            "totalTokens": 123,
            "durationMs": 500,
            "errorCode": "TOOL_ERROR" if failed_tool else None,
            "errorMsg": "tool timeout" if failed_tool else None,
        },
        "llmInvocations": [
            {
                "status": "SUCCESS",
                "startedAt": "2026-11-06T10:00:00",
                "firstTokenAt": "2026-11-06T10:00:00.100",
                "totalTokens": 123,
            }
        ],
        "toolInvocations": [
            {
                "toolName": "read_file",
                "status": "TIMEOUT" if failed_tool else "SUCCESS",
                "errorMsg": "tool timeout" if failed_tool else None,
                "toolResult": "ok",
            }
        ],
        "artifacts": [],
        "environment": {"profile": "ledger-export", "mode": "read-only"},
    }


def test_build_case_draft_keeps_oracle_empty(tmp_path: Path) -> None:
    trace_path = tmp_path / "trace.json"
    trace_path.write_text(json.dumps(_trace(failed_tool=True)), encoding="utf-8")

    draft = build_case_draft(
        RunTrace.from_dict(_trace(failed_tool=True)),
        trace_path=trace_path,
    )

    assert draft["suite"] == "private-badcase"
    assert draft["source"]["failureClass"] == "tool"
    assert draft["expected"] == {}
    assert draft["script"] == []
    assert draft["replay"]["oracleReviewed"] is False
    assert draft["manualReviewRequired"] is True
    assert draft["source"]["trace"] == f"{trace_path.parent.name}/trace.json"
    assert draft["replay"]["baselineTrace"] == f"{trace_path.parent.name}/trace.json"

    output = tmp_path / "case-draft.yaml"
    write_case_draft(trace_path, output)
    written = yaml.safe_load(output.read_text(encoding="utf-8"))
    assert written["expected"] == {}
    assert written["source"]["requestId"] == "req-private-1"


def test_private_badcase_can_load_without_script(tmp_path: Path) -> None:
    case_path = tmp_path / "private.yaml"
    case_path.write_text(
        yaml.safe_dump(
            {
                "id": "private.replay-case",
                "suite": "private-badcase",
                "version": 1,
                "hardGate": True,
                "input": {"message": "replay"},
                "expected": {"finalContains": ["OK"]},
                "budgets": {},
                "script": [],
            },
            allow_unicode=True,
        ),
        encoding="utf-8",
    )

    cases = load_cases(tmp_path, suite="private-badcase")

    assert [case.id for case in cases] == ["private.replay-case"]


def test_badcase_approval_requires_reviewed_oracle_and_marks_gate(
    tmp_path: Path,
) -> None:
    trace_path = tmp_path / "trace.json"
    trace_path.write_text(json.dumps(_trace(failed_tool=True)), encoding="utf-8")
    draft_path = tmp_path / "draft.yaml"
    write_case_draft(trace_path, draft_path)
    draft = yaml.safe_load(draft_path.read_text(encoding="utf-8"))

    with pytest.raises(ValueError, match="oracle must be reviewed"):
        approve_case_draft(
            draft_path,
            tmp_path / "approved.yaml",
            reviewer="reviewer",
        )

    draft["expected"] = {"finalContains": ["OK"]}
    draft["replay"]["oracleReviewed"] = True
    draft_path.write_text(
        yaml.safe_dump(draft, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )
    approved = approve_case_draft(
        draft_path,
        tmp_path / "approved.yaml",
        reviewer="reviewer",
        notes="confirmed",
    )

    assert approved["status"] == "approved"
    assert approved["manualReviewRequired"] is False
    assert approved["hardGate"] is True
    assert approved["approved"]["by"] == "reviewer"
    loaded = load_case_file(tmp_path / "approved.yaml", require_script=False)
    assert loaded.hard_gate is True


def test_badcase_approval_rejects_empty_oracle(tmp_path: Path) -> None:
    trace_path = tmp_path / "trace.json"
    trace_path.write_text(json.dumps(_trace(failed_tool=True)), encoding="utf-8")
    draft_path = tmp_path / "draft.yaml"
    write_case_draft(trace_path, draft_path)
    draft = yaml.safe_load(draft_path.read_text(encoding="utf-8"))
    draft["replay"]["oracleReviewed"] = True
    draft_path.write_text(
        yaml.safe_dump(draft, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="deterministic oracle field"):
        approve_case_draft(
            draft_path,
            tmp_path / "approved.yaml",
            reviewer="reviewer",
        )


def test_badcase_approval_can_finalize_draft_in_place(tmp_path: Path) -> None:
    trace_path = tmp_path / "trace.json"
    trace_path.write_text(json.dumps(_trace(failed_tool=True)), encoding="utf-8")
    draft_path = tmp_path / "draft.yaml"
    write_case_draft(trace_path, draft_path)
    draft = yaml.safe_load(draft_path.read_text(encoding="utf-8"))
    draft["expected"] = {"finalContains": ["OK"]}
    draft["replay"]["oracleReviewed"] = True
    draft_path.write_text(
        yaml.safe_dump(draft, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )

    approved = approve_case_draft(
        draft_path,
        draft_path,
        reviewer="reviewer",
    )

    assert approved["status"] == "approved"
    assert yaml.safe_load(draft_path.read_text(encoding="utf-8"))["status"] == (
        "approved"
    )


def test_compare_runs_reports_tool_sequence_and_cost_deltas() -> None:
    baseline = RunTrace.from_dict(_trace(failed_tool=True))
    candidate_payload = _trace(failed_tool=False)
    candidate_payload["run"]["totalTokens"] = 200
    candidate = RunTrace.from_dict(candidate_payload)

    comparison = compare_runs(baseline, candidate)

    assert comparison["changed"]["status"] is True
    assert comparison["deltas"]["totalTokens"] == 77
    assert comparison["baseline"]["toolSuccessRate"] == 0.0
    assert comparison["candidate"]["toolSuccessRate"] == 1.0


def test_replay_trace_scores_without_rerunning_java(tmp_path: Path) -> None:
    case_path = tmp_path / "case.yaml"
    case_path.write_text(
        yaml.safe_dump(
            {
                "id": "react.replay-smoke",
                "suite": "react-core",
                "version": 1,
                "hardGate": True,
                "input": {"message": "只回答 OK"},
                "expected": {
                    "finalContains": ["OK"],
                    "requiredTools": [],
                    "maxSteps": 1,
                },
                "budgets": {"maxTotalTokens": 300, "maxWallMs": 60000},
                "script": [{"type": "final", "content": "OK"}],
            },
            allow_unicode=True,
        ),
        encoding="utf-8",
    )
    trace_path = tmp_path / "trace.json"
    replay_payload = _trace()
    replay_payload["run"]["finalSummaryText"] = "OK"
    replay_payload["toolInvocations"] = []
    trace_path.write_text(json.dumps(replay_payload), encoding="utf-8")

    report = replay_trace(
        case_path,
        trace_path,
        tmp_path / "replay",
    )

    assert report["summary"]["caseCount"] == 1
    assert report["summary"]["failed"] == 0
    assert (tmp_path / "replay" / "report.json").exists()


def test_replay_trace_accepts_draft_without_script(tmp_path: Path) -> None:
    trace_payload = _trace(failed_tool=True)
    trace_path = tmp_path / "trace.json"
    trace_path.write_text(json.dumps(trace_payload), encoding="utf-8")
    draft = build_case_draft(
        RunTrace.from_dict(trace_payload),
        trace_path=trace_path,
    )
    case_path = tmp_path / "case.yaml"
    case_path.write_text(
        yaml.safe_dump(draft, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )

    report = replay_trace(
        case_path,
        trace_path,
        tmp_path / "replay",
    )

    assert report["summary"]["caseCount"] == 1


def test_replay_trace_set_resolves_baseline_trace(tmp_path: Path) -> None:
    trace_payload = _trace(failed_tool=True)
    trace_path = tmp_path / "baseline" / "trace.json"
    trace_path.parent.mkdir()
    trace_path.write_text(json.dumps(trace_payload), encoding="utf-8")
    draft = build_case_draft(
        RunTrace.from_dict(trace_payload),
        trace_path=trace_path,
    )
    case_path = tmp_path / "case.yaml"
    case_path.write_text(
        yaml.safe_dump(draft, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )

    report = replay_trace_set(
        [load_case_file(case_path, require_script=False)],
        tmp_path,
        tmp_path / "suite-report",
    )

    assert report["summary"]["caseCount"] == 1
    assert report["caseSummaries"][0]["suite"] == "private-badcase"


def test_counterfactual_replay_accepts_draft_without_script(
    tmp_path: Path,
    monkeypatch,
) -> None:
    baseline_payload = _trace(failed_tool=True)
    trace_path = tmp_path / "baseline" / "trace.json"
    trace_path.parent.mkdir()
    trace_path.write_text(json.dumps(baseline_payload), encoding="utf-8")
    draft = build_case_draft(
        RunTrace.from_dict(baseline_payload),
        trace_path=trace_path,
    )
    case_path = tmp_path / "case.yaml"
    case_path.write_text(
        yaml.safe_dump(draft, allow_unicode=True, sort_keys=False),
        encoding="utf-8",
    )
    candidate = RunTrace.from_dict(_trace())

    monkeypatch.setattr(
        "cute_evals.trace_tools.compile_java_fixture_runner",
        lambda: None,
    )
    monkeypatch.setattr(
        "cute_evals.trace_tools.run_java_cases",
        lambda cases, output_dir, **kwargs: {cases[0].id: candidate},
    )

    comparison = counterfactual_replay(
        case_path,
        trace_path,
        tmp_path / "counterfactual",
        mode="live",
        timeout_seconds=1,
    )

    assert comparison["mode"] == "counterfactual-replay"
    assert comparison["baseline"]["status"] == "FAILED"
    assert comparison["candidate"]["status"] == "SUCCESS"
