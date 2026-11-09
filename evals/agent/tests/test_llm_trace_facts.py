from __future__ import annotations

import json
from pathlib import Path

import jsonschema

from cute_evals.models import (
    BudgetSpec,
    CaseResult,
    EvalCase,
    ExpectedSpec,
    RunTrace,
    ScoreRecord,
)
from cute_evals.report import build_report


TRACE_SCHEMA = json.loads(
    (
        Path(__file__).parents[1] / "schemas" / "trace.schema.json"
    ).read_text(encoding="utf-8")
)


def _trace() -> dict:
    return {
        "schemaVersion": "1.1",
        "producer": {"agent": "cute-agent"},
        "run": {
            "caseId": "llm.cache",
            "requestId": "req-cache",
            "sessionId": "sess-cache",
            "status": "SUCCESS",
            "finalSummaryText": "ok",
            "totalTokens": 500,
            "durationMs": 100,
        },
        "llmInvocations": [
            {
                "promptTokens": 100,
                "completionTokens": 20,
                "totalTokens": 120,
                "cachedPromptTokens": 40,
                "promptTextTokens": 90,
                "promptAudioTokens": None,
                "promptImageTokens": 10,
                "completionTextTokens": 18,
                "completionAudioTokens": None,
                "reasoningTokens": 2,
                "systemFingerprint": "sha256:system",
                "estTotalTokens": 110,
                "estSystemTokens": 20,
                "estMessageTokens": 70,
                "estToolTokens": 20,
                "messageCount": 4,
                "toolCount": 2,
                "cacheStatus": "OK",
                "cacheRiskFlags": "none",
            },
            {
                "promptTokens": 50,
                "cachedPromptTokens": None,
                "cacheStatus": "UNKNOWN",
            },
            {
                "promptTokens": 75,
            },
            {
                "promptTokens": 150,
                "cachedPromptTokens": 0,
                "cacheStatus": "MISS",
            },
            {
                "promptTokens": None,
                "cachedPromptTokens": 10,
            },
        ],
        "toolInvocations": [],
        "artifacts": [],
        "environment": {},
    }


def test_legacy_trace_without_new_llm_facts_remains_valid() -> None:
    legacy = _trace()
    legacy["schemaVersion"] = "1.0"
    for invocation in legacy["llmInvocations"]:
        for key in list(invocation):
            if key not in {"promptTokens", "cachedPromptTokens"}:
                invocation.pop(key)

    jsonschema.validate(legacy, TRACE_SCHEMA)


def test_llm_facts_preserve_zero_and_missing_values() -> None:
    jsonschema.validate(_trace(), TRACE_SCHEMA)
    trace = RunTrace.from_dict(_trace())
    first = trace.llm_invocations[0]

    assert first["cachedPromptTokens"] == 40
    assert first["promptAudioTokens"] is None
    assert first["reasoningTokens"] == 2
    assert first["systemFingerprint"] == "sha256:system"
    assert first["estTotalTokens"] == 110
    assert trace.llm_invocations[1]["cachedPromptTokens"] is None
    assert trace.llm_invocations[3]["cachedPromptTokens"] == 0
    assert "cachedPromptTokens" not in trace.llm_invocations[2]


def test_cache_metrics_only_aggregate_verified_samples() -> None:
    trace = RunTrace.from_dict(_trace())
    metrics = trace.cache_hit_metrics()

    assert metrics == {
        "sampledInvocations": 2,
        "unverifiedInvocations": 3,
        "missingCachedPromptTokens": 1,
        "nullCachedPromptTokens": 1,
        "invalidPromptTokens": 1,
        "promptTokens": 250,
        "cachedPromptTokens": 40,
        "hitRatio": 0.16,
    }


def test_report_exposes_cache_sample_count(tmp_path: Path) -> None:
    case = EvalCase(
        id="llm.cache",
        suite="cache-efficiency",
        version=1,
        hard_gate=True,
        query="answer",
        expected=ExpectedSpec(final_contains=["ok"]),
        budgets=BudgetSpec(),
        script=[{"type": "final", "content": "ok"}],
        source_path="test.yaml",
    )
    result = CaseResult(
        case=case,
        trace=RunTrace.from_dict(_trace()),
        scores=[
            ScoreRecord(
                case_id=case.id,
                scorer="deterministic",
                name="task_success",
                value=True,
                unit="boolean",
                passed=True,
                hard_gate=True,
                status="verified",
            )
        ],
        trace_path="attempts/01/trace.json",
    )

    report = build_report(
        profile="nightly",
        agent="cute-agent",
        cases=[result],
        output_dir=tmp_path,
    )

    assert report["summary"]["cache"] == {
        "sampledInvocations": 2,
        "unverifiedInvocations": 3,
        "missingCachedPromptTokens": 1,
        "nullCachedPromptTokens": 1,
        "invalidPromptTokens": 1,
        "promptTokens": 250,
        "cachedPromptTokens": 40,
        "hitRatio": 0.16,
    }
    assert report["caseSummaries"][0]["cacheHitMetrics"]["sampledInvocations"] == 2
    assert (
        "16.0% (samples=2, unverified=3, missing=1, null=1)"
        in (
        tmp_path / "report.html"
        ).read_text(encoding="utf-8")
    )
