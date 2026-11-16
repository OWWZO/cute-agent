from __future__ import annotations

import json
from pathlib import Path

from cute_evals.benchmarks.base import BenchmarkRun, BenchmarkTask
from cute_evals.benchmarks.tau2.adapter import Tau2Adapter
from cute_evals.models import EvalCase, JudgeConfig, RunTrace


def _task() -> BenchmarkTask:
    return BenchmarkTask(
        id="0",
        category="airline",
        question="Baggage allowance",
        functions=[],
        ground_truth=None,
        raw={
            "id": "0",
            "evaluation_criteria": {
                "reward_basis": ["DB", "COMMUNICATE"],
                "nl_assertions": [
                    "Agent should tell the user they are a silver member.",
                    "Agent should clearly state four checked bags.",
                ],
            },
        },
        metadata={"domain": "airline", "officialTaskId": "0"},
    )


def _trace() -> RunTrace:
    return RunTrace.from_dict(
        {
            "run": {
                "caseId": "0",
                "requestId": "request-0",
                "status": "SUCCESS",
                "finalSummaryText": "You are silver and may bring four bags.",
                "terminationReason": "user_stop",
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "conversation": [],
        }
    )


def _case() -> EvalCase:
    return EvalCase.from_dict(
        {
            "id": "0",
            "suite": "tau2-official-subset",
            "hardGate": True,
            "input": {"message": "Baggage allowance"},
            "expected": {},
            "budgets": {"maxTotalTokens": 40000},
            "script": [{"type": "final", "content": "fixture"}],
        },
        "case.json",
    )


def test_tau2_nl_assertion_is_advisory_and_does_not_change_official_reward() -> None:
    run = BenchmarkRun(
        task=_task(),
        trace=_trace(),
        trace_path="trace.json",
        calls=[],
        oracle={
            "reward": 1.0,
            "db_check": {"db_match": True, "db_reward": 1.0},
        },
        metadata={
            "nlAssertion": {
                "applicable": True,
                "status": "verified",
                "reward": 1.0,
                "nl_assertions": [
                    {"nl_assertion": "Agent should tell the user they are a silver member.", "met": True},
                    {"nl_assertion": "Agent should clearly state four checked bags.", "met": True},
                ],
                "judgeModel": "judge-test",
            }
        },
    )

    result = Tau2Adapter().score(run, case=_case())
    by_name = {score.name: score for score in result.scores}

    assert by_name["official_reward"].value == 1.0
    assert by_name["official_reward"].hard_gate is True
    assert by_name["nl_assertion_reward"].value == 1.0
    assert by_name["nl_assertion_reward"].hard_gate is False
    assert by_name["nl_assertion_reward"].status == "verified"
    assert len(by_name["nl_assertion_details"].value) == 2


def test_tau2_nl_assertion_without_judge_is_unverified(tmp_path: Path) -> None:
    class Server:
        def evaluate_nl(self, payload, judge):
            raise AssertionError("server must not be called without a judge")

    result = Tau2Adapter().evaluate_nl(
        Server(),
        _trace(),
        JudgeConfig(),
    )

    assert result["status"] == "unverified"
    assert "not configured" in result["error"]


def test_tau2_nl_assertion_uses_official_server_result() -> None:
    class Server:
        def __init__(self) -> None:
            self.payload = None

        def evaluate_nl(self, payload, judge):
            self.payload = (payload, judge)
            return {
                "applicable": True,
                "reward": 0.0,
                "nl_assertions": [
                    {
                        "nl_assertion": "Agent should clearly state four checked bags.",
                        "met": False,
                        "justification": "The answer said five.",
                    }
                ],
                "info": None,
            }

    server = Server()
    result = Tau2Adapter().evaluate_nl(
        server,
        _trace(),
        JudgeConfig(
            model="judge-test",
            base_url="http://judge.invalid/v1",
            api_key="secret",
        ),
    )

    assert result["status"] == "verified"
    assert result["reward"] == 0.0
    assert server.payload[1].model == "judge-test"
