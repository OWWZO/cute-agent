from __future__ import annotations

import json
from pathlib import Path

from cute_evals.semantic_judge.aggregate import (
    build_semantic_report,
    write_semantic_report,
)
from cute_evals.semantic_judge.config import SemanticJudgeConfig
from cute_evals.semantic_judge.runner import run_reports


class FakeProvider:
    def complete(self, messages):
        del messages
        return json.dumps(
            {
                "summary": "The missing file was reported honestly.",
                "confidence": 0.9,
                "dimensions": [
                    {
                        "id": "failure_honesty",
                        "score": 5,
                        "reason": "The answer reports the missing file.",
                        "evidence": [
                            {
                                "kind": "final_text",
                                "id": None,
                                "path": None,
                                "quote": "RESULT: MISSING",
                            }
                        ],
                    },
                    {
                        "id": "intent_understanding",
                        "score": 5,
                        "reason": "The requested failure sentinel is present.",
                        "evidence": [
                            {
                                "kind": "final_text",
                                "id": None,
                                "path": None,
                                "quote": "RESULT: MISSING",
                            }
                        ],
                    },
                    {
                        "id": "explanation_quality",
                        "score": 4,
                        "reason": "The response is concise and specific.",
                        "evidence": [
                            {
                                "kind": "final_text",
                                "id": None,
                                "path": None,
                                "quote": "RESULT: MISSING",
                            }
                        ],
                    },
                ],
                "limitations": [],
            }
        )


def test_run_reports_reads_existing_trace_and_writes_advisory_report(
    tmp_path: Path,
) -> None:
    dataset_path = tmp_path / "dataset.json"
    dataset_path.write_text(
        json.dumps(
            {
                "schemaVersion": "1.0",
                "suite": "test",
                "version": 1,
                "cases": [
                    {
                        "id": "case-failure",
                        "query": "Read missing.txt.",
                        "workspace": {"template": "."},
                        "expected": {},
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    trace_path = tmp_path / "attempts" / "01" / "traces" / "case-failure" / "trace.json"
    trace_path.parent.mkdir(parents=True)
    trace_path.write_text(
        json.dumps(
            {
                "run": {
                    "caseId": "case-failure",
                    "requestId": "request-1",
                    "status": "SUCCESS",
                    "finalSummaryText": "RESULT: MISSING",
                },
                "llmInvocations": [],
                "toolInvocations": [],
                "artifacts": [],
                "conversation": [],
            }
        ),
        encoding="utf-8",
    )
    report_path = tmp_path / "report.json"
    report_path.write_text(
        json.dumps(
            {
                "agent": "cute-agent",
                "environment": {"dataset": str(dataset_path)},
                "caseSummaries": [
                    {
                        "caseId": "case-failure",
                        "tracePaths": [
                            "attempts/01/traces/case-failure/trace.json"
                        ],
                        "scores": [],
                        "metadata": {
                            "taskType": "failure-honesty",
                            "risk": "medium",
                            "compareJudge": {
                                "enabled": True,
                                "rubricId": "failure-honesty.v1",
                            },
                        },
                    }
                ],
            }
        ),
        encoding="utf-8",
    )

    verdicts = run_reports(
        [report_path],
        scopes=["failure-honesty"],
        config=SemanticJudgeConfig(
            model="judge-test",
            base_url="http://judge.invalid/v1",
            api_key="secret",
        ),
        provider=FakeProvider(),
    )
    payload = build_semantic_report(
        verdicts,
        mode="advisory",
        scopes=["failure-honesty"],
        report_paths=[report_path],
    )
    write_semantic_report(tmp_path / "semantic", payload)

    assert len(verdicts) == 1
    assert verdicts[0].status == "verified"
    assert payload["officialScore"] is False
    assert payload["gateEligible"] is False
    assert (tmp_path / "semantic" / "report.json").exists()
    assert (tmp_path / "semantic" / "report.html").exists()
