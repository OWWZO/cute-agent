from __future__ import annotations

import json
from pathlib import Path

from jsonschema import validate

from cute_evals.trajectory_quality import (
    QualityJudgeConfig,
    analyze_trace,
    run_trajectory_quality,
)


class FakeJudge:
    model = "fake/deepseek"

    def complete(self, messages, *, timeout_seconds=None):
        prompt = "\n".join(message["content"] for message in messages)
        if "stable_locations" in prompt:
            return json.dumps(
                {
                    "errors": [
                        {
                            "category": "Tool",
                            "location": "run",
                            "evidence": "the first tool invocation failed",
                            "impact": "the first route was interrupted",
                            "description": "retry with a changed strategy",
                        }
                    ],
                    "scores": [
                        {
                            "reliability_score": 3,
                            "security_score": 5,
                            "instruction_adherence_score": 4,
                            "plan_opt_score": 3,
                            "overall": 3,
                        }
                    ],
                }
            )
        return json.dumps(
            {
                "step_labels": {"1": -1, "3": 1, "5": 1},
                "final_label": -1,
                "explanations": {
                    "steps": {
                        "1": "the first tool call failed",
                        "3": "the retry used the same tool and succeeded",
                        "5": "the final answer reflects the observed state",
                    },
                    "final": "the task ended before the sub-agent returned",
                },
            }
        )


def _write_trace(path: Path) -> None:
    payload = {
        "schemaVersion": "1.1",
        "producer": {"agent": "cute-agent"},
        "run": {
            "runUid": "run-quality-1",
            "requestId": "req-quality-1",
            "sessionId": "sess-quality-1",
            "status": "SUCCESS",
            "queryText": "wake the sub-agent and inspect progress",
            "finalSummaryText": "the sub-agent is still running",
            "durationMs": 47750,
            "totalTokens": 60,
        },
        "llmInvocations": [
            {
                "id": 101,
                "invocationSeq": 1,
                "stepNo": 1,
                "status": "SUCCESS",
                "responseText": None,
                "reasoningContent": "plan the wake-up",
                "toolCallCount": 1,
                "promptTokens": 20,
                "completionTokens": 10,
                "reasoningTokens": 3,
                "totalTokens": 30,
                "cachedPromptTokens": None,
            },
            {
                "id": 102,
                "invocationSeq": 2,
                "stepNo": 2,
                "status": "SUCCESS",
                "responseText": None,
                "toolCallCount": 1,
                "promptTokens": 15,
                "completionTokens": 5,
                "totalTokens": 20,
                "cachedPromptTokens": 12,
            },
            {
                "id": 103,
                "invocationSeq": 3,
                "stepNo": 3,
                "status": "SUCCESS",
                "responseText": "the sub-agent is still running",
                "toolCallCount": 0,
                "promptTokens": 5,
                "completionTokens": 5,
                "totalTokens": 10,
                "cachedPromptTokens": 5,
            },
        ],
        "toolInvocations": [
            {
                "id": 201,
                "llmInvocationId": 101,
                "toolCallId": "call-send-1",
                "stepNo": 1,
                "dispatchIndex": 1,
                "toolName": "SendMessage",
                "inputJson": "{\"to\":\"agent-1\"}",
                "toolResult": "{\"ok\":false,\"error\":\"busy\"}",
                "status": "FAILED",
                "errorMsg": "busy",
            },
            {
                "id": 202,
                "llmInvocationId": 102,
                "toolCallId": "call-send-2",
                "stepNo": 2,
                "dispatchIndex": 1,
                "toolName": "SendMessage",
                "inputJson": "{\"to\":\"agent-1\"}",
                "toolResult": "{\"ok\":true}",
                "status": "SUCCESS",
            },
        ],
        "artifacts": [],
    }
    path.write_text(json.dumps(payload), encoding="utf-8")


def test_trajectory_quality_builds_product_derived_report(
    tmp_path: Path,
    monkeypatch,
) -> None:
    trace_path = tmp_path / "trace.json"
    _write_trace(trace_path)
    fake_judge = FakeJudge()
    monkeypatch.setattr(QualityJudgeConfig, "client", lambda self: fake_judge)

    report = run_trajectory_quality(
        [trace_path],
        output_dir=tmp_path / "report",
        config=QualityJudgeConfig(
            model="fake/deepseek",
            base_url="http://127.0.0.1:3425/v1",
            api_key="test-key",
        ),
    )

    assert report["scoreNamespace"] == "product-derived"
    assert report["scoreLabel"] == "internal-calibration"
    assert report["usagePolicy"] == {
        "mode": "internal-calibration",
        "officialScore": False,
        "publicationAllowed": False,
        "gateEligible": False,
        "advisory": True,
    }
    assert report["metrics"]["stepQualityVerifiedRuns"] == 1
    assert report["metrics"]["rootCauseVerifiedRuns"] == 1
    assert report["metrics"]["stepLabelCounts"] == {"-1": 1, "0": 0, "1": 2}
    assert report["metrics"]["runsWithFirstError"] == 1
    run = report["runs"][0]
    assert run["processViewSource"] == "ledger-reconstructed"
    assert run["facts"]["tools"]["failureCount"] == 1
    assert run["facts"]["tools"]["recoveryCount"] == 1
    assert run["facts"]["tools"]["repeatedCallExcess"] == 1
    assert run["facts"]["steps"]["assistantContentMissing"] == 2
    assert run["judgments"]["stepQuality"]["promptSource"] == "product-internal"
    assert run["judgments"]["rootCause"]["promptSource"] == "product-internal"

    schema_path = (
        Path(__file__).resolve().parents[1]
        / "schemas"
        / "trajectory_quality.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    validate(report, schema)


def test_invalid_root_cause_location_is_unverified(tmp_path: Path) -> None:
    trace_path = tmp_path / "trace.json"
    _write_trace(trace_path)

    class InvalidRootCauseJudge(FakeJudge):
        def complete(self, messages, *, timeout_seconds=None):
            response = super().complete(messages, timeout_seconds=timeout_seconds)
            payload = json.loads(response)
            if "errors" in payload:
                payload["errors"][0]["location"] = "somewhere I cannot prove"
            return json.dumps(payload)

    result = analyze_trace(
        trace_path,
        judge=InvalidRootCauseJudge(),
        apb_source_dir=None,
        trail_source_dir=None,
    )

    assert result["judgments"]["stepQuality"]["status"] == "verified"
    assert result["judgments"]["rootCause"]["status"] == "unverified"
    assert result["judgments"]["rootCause"]["error"]["kind"] == "invalid_location"
