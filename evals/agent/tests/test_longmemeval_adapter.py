from __future__ import annotations

import hashlib
import json
from pathlib import Path

import yaml

from cute_evals.benchmarks.longmemeval import LongMemEvalAdapter
from cute_evals.benchmarks.longmemeval.official_prompt import (
    get_anscheck_prompt,
    official_label,
)
from cute_evals.benchmarks.longmemeval.judge import _label_from_content
from cute_evals.models import JudgeConfig, RunTrace


def _row(
    question_id: str,
    question_type: str,
    question: str,
    answer: str,
) -> dict:
    return {
        "question_id": question_id,
        "question_type": question_type,
        "question": question,
        "answer": answer,
        "question_date": "2025/03/01 (Sat) 12:00",
        "haystack_dates": ["2025/02/01 (Sat) 09:00"],
        "haystack_session_ids": [f"{question_id}-s1"],
        "haystack_sessions": [
            [
                {
                    "role": "user",
                    "content": f"Remember the evidence marker {answer}.",
                    "has_answer": True,
                },
                {
                    "role": "assistant",
                    "content": f"I will remember {answer}.",
                    "has_answer": False,
                },
            ]
        ],
        "answer_session_ids": [f"{question_id}-s1"],
    }


def _adapter(tmp_path: Path) -> tuple[LongMemEvalAdapter, Path]:
    rows = [
        _row("q-user", "single-session-user", "What is my marker?", "USER-1"),
        _row("q-assistant", "single-session-assistant", "What did you say?", "ASSIST-1"),
        _row("q-pref", "single-session-preference", "How should I format it?", "PREF-1"),
        _row("q-multi", "multi-session", "Combine my markers.", "MULTI-1"),
        _row("q-temporal", "temporal-reasoning", "When did it happen?", "TIME-1"),
        _row("q-update", "knowledge-update", "What is current?", "UPDATE-1"),
        _row("q-abs-1_abs", "single-session-user", "Missing one?", "unknown"),
        _row("q-abs-2_abs", "multi-session", "Missing two?", "unknown"),
    ]
    data = tmp_path / "longmemeval_oracle.json"
    data.write_text(json.dumps(rows), encoding="utf-8")
    digest = hashlib.sha256(data.read_bytes()).hexdigest()
    manifest = yaml.safe_load(
        (
            Path(__file__).resolve().parents[1]
            / "src"
            / "cute_evals"
            / "benchmarks"
            / "longmemeval"
            / "manifest.yaml"
        ).read_text(encoding="utf-8")
    )
    manifest["dataset"]["files"]["longmemeval_oracle.json"] = {
        "sha256": digest,
        "size": data.stat().st_size,
    }
    manifest_path = tmp_path / "manifest.yaml"
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    return (
        LongMemEvalAdapter(
            manifest_path,
            data_path=data,
            judge=JudgeConfig(
                model="group/deepseek",
                base_url="http://127.0.0.1:3425/v1",
                api_key="test",
            ),
        ),
        data,
    )


def test_subset_uses_official_rows_without_mutating_dataset(tmp_path: Path) -> None:
    adapter, data = _adapter(tmp_path)
    before = data.read_bytes()

    tasks = adapter.tasks("official-subset")
    after = data.read_bytes()

    assert len(tasks) == 8
    assert after == before
    assert {task.category for task in tasks} >= {
        "single-session-user",
        "single-session-assistant",
        "single-session-preference",
        "multi-session",
        "temporal-reasoning",
        "knowledge-update",
    }


def test_case_seeds_official_history_without_putting_it_in_query(
    tmp_path: Path,
) -> None:
    adapter, _ = _adapter(tmp_path)
    task = next(
        item
        for item in adapter.tasks("official-subset")
        if item.id == "q-update"
    )

    case = adapter.case_for_task(task, namespace="nightly-01-abcdef")

    assert case.user_id.startswith("eval-lme-q-update-")
    assert case.memory_seed is not None
    assert case.memory_seed["sessions"][0]["turns"][0]["content"].startswith(
        "Remember the evidence marker"
    )
    assert "Remember the evidence marker" not in case.query
    assert "Use session_search" in case.query
    assert "What is current?" in case.query
    assert case.answer_contract is not None
    assert case.answer_contract["linePrefix"] == "FINAL_ANSWER:"


def test_score_uses_official_prompt_semantics_and_reports_compatibility(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter, _ = _adapter(tmp_path)
    task = next(
        item
        for item in adapter.tasks("official-subset")
        if item.id == "q-update"
    )
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": task.id,
                "requestId": "req-1",
                "status": "SUCCESS",
                "finalSummaryText": "The current marker is UPDATE-1.\nFINAL_ANSWER: UPDATE-1",
                "totalTokens": 100,
                "durationMs": 20,
            },
            "llmInvocations": [],
            "toolInvocations": [
                {"toolName": "session_search", "status": "SUCCESS"}
            ],
            "artifacts": [],
        }
    )
    run = adapter.collect(task, trace, "trace.json")
    run.metadata["namespace"] = "nightly-01-abcdef"

    monkeypatch.setattr(
        "cute_evals.benchmarks.longmemeval.adapter.judge_longmemeval_answer",
        lambda **_kwargs: {
            "label": True,
            "rawLabel": "yes",
            "model": "group/deepseek",
        },
    )
    result = adapter.score(run)
    metadata = adapter.report_metadata()

    assert result.passed
    assert result.scores[0].name == "official_qa_accuracy"
    assert result.scores[0].status == "verified"
    assert any(score.name == "token_budget" for score in result.scores)
    assert metadata["scoreLabel"] == "compatibility-subset"
    assert metadata["judgeCompatibleWithOfficialScore"] is False
    assert metadata["officialEvaluator"] is False
    assert (
        metadata["evaluatorCompatibility"]
        == "exact-official-prompt-and-label-rule"
    )


def test_official_prompt_has_task_specific_temporal_rule() -> None:
    prompt = get_anscheck_prompt(
        "temporal-reasoning",
        "How many days?",
        "18 days",
        "19 days",
    )

    assert "do not penalize off-by-one errors" in prompt
    assert official_label("yes") is True
    assert official_label("no") is False


def test_judge_rejects_empty_sentinel_responses() -> None:
    assert _label_from_content(None) is None
    assert _label_from_content("") is None
    assert _label_from_content("None") is None
    assert _label_from_content("null") is None
    assert _label_from_content("yes") == "yes"
    assert _label_from_content("No, the response is incomplete.") == "no"


def test_score_marks_token_budget_exceeded_as_slo_failure(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter, _ = _adapter(tmp_path)
    task = next(
        item
        for item in adapter.tasks("official-subset")
        if item.id == "q-update"
    )
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": task.id,
                "requestId": "req-1",
                "status": "SUCCESS",
                "finalSummaryText": "FINAL_ANSWER: UPDATE-1",
                "totalTokens": 80_001,
                "durationMs": 20,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
        }
    )
    run = adapter.collect(task, trace, "trace.json")
    run.metadata["namespace"] = "nightly-01-abcdef"
    monkeypatch.setattr(
        "cute_evals.benchmarks.longmemeval.adapter.judge_longmemeval_answer",
        lambda **_kwargs: {
            "label": True,
            "rawLabel": "yes",
            "model": "group/deepseek",
        },
    )

    result = adapter.score(run)

    token_score = next(score for score in result.scores if score.name == "token_budget")
    assert token_score.passed is False
    assert result.quality_passed
    assert not result.slo_passed
    assert not result.passed
