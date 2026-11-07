from __future__ import annotations

import json
from datetime import datetime, timedelta
from pathlib import Path

import pytest
import yaml

from cute_evals.models import RunTrace
from cute_evals.ops_sample import (
    audit_trace,
    load_sample_config,
    prune_sample_runs,
    sample_export,
    validate_sample_config,
)


def _trace(
    request_id: str,
    *,
    status: str = "SUCCESS",
    final: str = "ok",
    tokens: int = 100,
    duration_ms: int = 1000,
    first_token_ms: int = 100,
    tools: list[dict] | None = None,
    error_code: str | None = None,
) -> dict:
    first_token_at = (
        datetime(2026, 11, 6, 10, 0, 0)
        + timedelta(milliseconds=first_token_ms)
    ).isoformat()
    return {
        "schemaVersion": "1.0",
        "producer": {"agent": "cute-agent", "runtimeVersion": "test"},
        "run": {
            "caseId": None,
            "requestId": request_id,
            "sessionId": f"session-{request_id}",
            "entryAgent": "react",
            "status": status,
            "queryText": f"query {request_id}",
            "finalSummaryText": final,
            "startedAt": "2026-11-06T10:00:00",
            "finishedAt": "2026-11-06T10:00:01",
            "durationMs": duration_ms,
            "promptTokens": tokens - 20,
            "completionTokens": 20,
            "totalTokens": tokens,
            "llmCallCount": 1,
            "toolCallCount": len(tools or []),
            "artifactCount": 0,
            "errorCode": error_code,
            "errorMsg": final if status in {"FAILED", "TIMEOUT"} else None,
        },
        "llmInvocations": [
            {
                "status": "SUCCESS",
                "startedAt": "2026-11-06T10:00:00",
                "firstTokenAt": first_token_at,
                "finishedAt": "2026-11-06T10:00:01",
                "totalTokens": tokens,
            }
        ],
        "toolInvocations": tools or [],
        "artifacts": [],
        "environment": {"profile": "ledger-export", "mode": "read-only"},
    }


def _write_export(root: Path, payloads: list[dict]) -> Path:
    relative_paths = []
    for payload in payloads:
        request_id = payload["run"]["requestId"]
        relative = f"{request_id}/trace.json"
        path = root / relative
        path.parent.mkdir(parents=True)
        path.write_text(json.dumps(payload), encoding="utf-8")
        relative_paths.append(relative)
    (root / "export-manifest.json").write_text(
        json.dumps(
            {
                "schemaVersion": "1.0",
                "exportedTraces": len(relative_paths),
                "traces": relative_paths,
            }
        ),
        encoding="utf-8",
    )
    return root


def _config(**overrides):
    config = load_sample_config()
    config.update(overrides)
    validate_sample_config(config)
    return config


def test_sample_prefers_problem_traces_and_keeps_deterministic_controls(
    tmp_path: Path,
) -> None:
    export = _write_export(
        tmp_path / "export",
        [
            _trace("failed", status="FAILED", final="boom"),
            _trace(
                "timeout",
                status="TIMEOUT",
                final="timed out",
                tools=[
                    {
                        "toolName": "web_search",
                        "status": "TIMEOUT",
                        "errorMsg": "timeout",
                    }
                ],
            ),
            _trace("healthy-a"),
            _trace("healthy-b"),
            _trace("healthy-c"),
        ],
    )
    config = _config(failureQuota=2, healthQuota=2, seed=7)

    first = sample_export(
        export,
        tmp_path / "first",
        config=config,
        from_time=datetime(2026, 11, 6),
        to_time=datetime(2026, 11, 7),
    )
    second = sample_export(
        export,
        tmp_path / "second",
        config=config,
        from_time=datetime(2026, 11, 6),
        to_time=datetime(2026, 11, 7),
    )

    first_ids = [item["requestId"] for item in first["selection"]]
    second_ids = [item["requestId"] for item in second["selection"]]
    assert first_ids == second_ids
    assert {item["requestId"] for item in first["selection"][:2]} == {
        "failed",
        "timeout",
    }
    assert [item["category"] for item in first["selection"][:2]] == [
        "problem",
        "problem",
    ]
    assert all(
        item["category"] == "healthy_control"
        for item in first["selection"][2:]
    )
    assert first["summary"]["scanned"] == 5
    assert first["summary"]["problemCount"] == 2
    assert first["summary"]["selectedTotal"] == 4

    draft_path = tmp_path / "first" / "drafts" / "failed.yaml"
    draft = yaml.safe_load(draft_path.read_text(encoding="utf-8"))
    assert draft["expected"] == {}
    assert draft["replay"]["oracleReviewed"] is False
    assert first["approval"]["automatic"] is False
    assert (tmp_path / "first" / "audit-report.html").exists()


def test_audit_reports_budget_empty_answer_tool_and_latency_findings() -> None:
    payload = _trace(
        "suspicious",
        final=" ",
        tokens=600,
        duration_ms=700,
        first_token_ms=400,
        error_code="EVAL_BUDGET_EXCEEDED",
        tools=[
            {"toolName": "read_file", "status": "FAILED", "errorMsg": "denied"},
            {"toolName": "read_file", "status": "SUCCESS", "errorMsg": None},
            {"toolName": "read_file", "status": "SUCCESS", "errorMsg": None},
            {"toolName": "read_file", "status": "SUCCESS", "errorMsg": None},
        ],
    )

    audit = audit_trace(
        RunTrace.from_dict(payload),
        relative_trace="suspicious/trace.json",
        thresholds={
            "maxTotalTokens": 500,
            "maxWallMs": 600,
            "maxFirstTokenMs": 300,
            "maxRepeatedToolCalls": 2,
            "minToolSuccessRate": 1.0,
        },
    )

    assert audit["problem"] is True
    assert {
        finding["code"] for finding in audit["findings"]
    } == {
        "budget_exceeded",
        "empty_final_answer",
        "high_total_tokens",
        "slow_run",
        "slow_first_token",
        "tool_failure",
        "low_tool_success_rate",
        "repeated_tool_calls",
    }
    assert audit["metrics"]["toolFailureCount"] == 1


def test_prune_only_removes_expired_managed_sample_runs(tmp_path: Path) -> None:
    now = datetime(2026, 11, 6, 12, 0, 0)
    old = tmp_path / "old"
    new = tmp_path / "new"
    unrelated = tmp_path / "unrelated"
    for path, generated_at in (
        (old, (now - timedelta(days=10)).isoformat()),
        (new, now.isoformat()),
    ):
        path.mkdir()
        (path / "sample-manifest.json").write_text(
            json.dumps({"schemaVersion": "1.0", "generatedAt": generated_at}),
            encoding="utf-8",
        )
    unrelated.mkdir()

    removed = prune_sample_runs(
        tmp_path,
        retention_days=7,
        now=now,
    )

    assert removed == [str(old.resolve())]
    assert not old.exists()
    assert new.exists()
    assert unrelated.exists()


def test_sample_config_rejects_unknown_status(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text(
        yaml.safe_dump(
            {
                "statuses": ["SUCCESS", "RUNNING"],
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="subset"):
        load_sample_config(path)


def test_sample_config_defaults_scheduler_to_off() -> None:
    config = load_sample_config()

    assert config["schedule"]["enabled"] is False


def test_sample_applies_left_closed_right_open_window(tmp_path: Path) -> None:
    start = datetime(2026, 11, 6, 10, 0, 0)
    export = _write_export(
        tmp_path / "export",
        [
            _trace("at-start"),
            _trace("at-end"),
        ],
    )
    payload_start = _trace("at-start")
    payload_start["run"]["startedAt"] = start.isoformat()
    (export / "at-start" / "trace.json").write_text(
        json.dumps(payload_start), encoding="utf-8"
    )
    payload_end = _trace("at-end")
    payload_end["run"]["startedAt"] = (start + timedelta(hours=1)).isoformat()
    (export / "at-end" / "trace.json").write_text(
        json.dumps(payload_end), encoding="utf-8"
    )

    manifest = sample_export(
        export,
        tmp_path / "sample",
        config=_config(failureQuota=0, healthQuota=10),
        from_time=start,
        to_time=start + timedelta(hours=1),
    )

    assert [item["requestId"] for item in manifest["selection"]] == ["at-start"]
