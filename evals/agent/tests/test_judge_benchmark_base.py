from __future__ import annotations

import pytest

from cute_evals.judge_benchmarks import (
    JudgeBenchmarkTask,
    JudgeOutputError,
    JudgeOutputKind,
    parse_judge_prediction,
)
from cute_evals.judge_benchmarks.base import (
    judge_score_label,
    judge_usage_policy,
)


def _task() -> JudgeBenchmarkTask:
    return JudgeBenchmarkTask(
        id="task-1",
        category="hotpotqa",
        messages=[
            {"role": "user", "content": "question"},
            {"role": "assistant", "content": "search"},
            {"role": "tool", "content": "result"},
            {"role": "assistant", "content": "answer"},
        ],
        assistant_message_indices=[1, 3],
        tools=[],
        raw={},
    )


def test_parses_fenced_json_and_normalizes_integer_keys() -> None:
    response = """analysis
```json
{
  "step_labels": {"1": "+1", "3": -1},
  "final_label": 1,
  "explanations": {
    "steps": {"1": "valid tool call", "3": "wrong final answer"},
    "final": "partial"
  }
}
```"""

    prediction = parse_judge_prediction(_task(), response)

    assert prediction.step_labels == {"1": 1, "3": -1}
    assert prediction.final_label == 1
    assert prediction.explanations["steps"]["3"] == "wrong final answer"


def test_missing_step_is_not_coerced_to_neutral() -> None:
    response = {
        "step_labels": {"1": 1},
        "final_label": 1,
        "explanations": {
            "steps": {"1": "ok", "3": "ok"},
            "final": "ok",
        },
    }

    with pytest.raises(JudgeOutputError) as error:
        parse_judge_prediction(_task(), response)

    assert error.value.kind is JudgeOutputKind.MISSING_STEPS
    assert error.value.details["missing"] == ["3"]


def test_unexpected_step_is_rejected() -> None:
    response = {
        "step_labels": {"1": 1, "3": 1, "4": 0},
        "final_label": 1,
        "explanations": {
            "steps": {"1": "ok", "3": "ok", "4": "extra"},
            "final": "ok",
        },
    }

    with pytest.raises(JudgeOutputError) as error:
        parse_judge_prediction(_task(), response)

    assert error.value.kind is JudgeOutputKind.UNEXPECTED_STEPS


def test_invalid_or_boolean_label_is_rejected() -> None:
    for label in (True, 2, "pass"):
        response = {
            "step_labels": {"1": label, "3": 1},
            "final_label": 1,
            "explanations": {
                "steps": {"1": "ok", "3": "ok"},
                "final": "ok",
            },
        }
        with pytest.raises(JudgeOutputError) as error:
            parse_judge_prediction(_task(), response)
        assert error.value.kind is JudgeOutputKind.INVALID_LABEL


def test_empty_response_is_explicitly_unverified_input() -> None:
    with pytest.raises(JudgeOutputError) as error:
        parse_judge_prediction(_task(), "")

    assert error.value.kind is JudgeOutputKind.EMPTY_RESPONSE


def test_local_research_cannot_emit_an_official_score() -> None:
    assert judge_score_label("local-research", "official-subset") == "local-research"
    assert judge_usage_policy("local-research") == {
        "mode": "local-research",
        "officialScore": False,
        "publicationAllowed": False,
        "gateEligible": False,
    }
