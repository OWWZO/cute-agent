from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path

import pytest
import yaml

from cute_evals.benchmarks.memoryagentbench import MemoryAgentBenchAdapter
from cute_evals.benchmarks.memoryagentbench import official_summarization
from cute_evals.benchmarks.memoryagentbench.official_metrics import (
    exact_match,
    ruler_recall,
    score_answer,
    substring_exact_match,
)
from cute_evals.benchmarks.memoryagentbench.official_recsys import (
    find_nearest_movie,
    score_recsys_recommendations,
)
from cute_evals.models import JudgeConfig, RunTrace


def _row(
    category: str,
    source: str,
    prefix: str,
) -> dict:
    questions = [
        f"{category} question {index}?"
        for index in range(1, 6)
    ]
    answer_rows = (
        [[str(index)] for index in range(1, 6)]
        if source.startswith("recsys_")
        else [[f"{prefix}-{index}"] for index in range(1, 6)]
    )
    question_types = (
        [
            "multi-session",
            "temporal-reasoning",
            "knowledge-update",
            "single-session-user",
            "single-session-preference",
        ]
        if source.startswith("longmemeval_")
        else ["unknown"] * 5
    )
    return {
        "context": (
            f"{category} context one. {category} context two. "
            f"{category} context three. {category} context four."
        ),
        "questions": questions,
        "answers": answer_rows,
        "metadata": {
            "source": source,
            "qa_pair_ids": [
                f"{source}_no{index}"
                for index in range(5)
            ],
            "question_ids": [
                f"{prefix}_no{index}"
                for index in range(5)
            ],
            "question_types": question_types,
            "keypoints": (
                [f"keypoint {index}" for index in range(1, 6)]
                if source.startswith("infbench_")
                else None
            ),
        },
    }


def _adapter(
    tmp_path: Path,
    monkeypatch,
) -> MemoryAgentBenchAdapter:
    manifest = yaml.safe_load(
        (
            Path(__file__).resolve().parents[1]
            / "src"
            / "cute_evals"
            / "benchmarks"
            / "memoryagentbench"
            / "manifest.yaml"
        ).read_text(encoding="utf-8")
    )
    rows_by_category: dict[str, list[dict]] = {}
    for category in (
        "Accurate_Retrieval",
        "Test_Time_Learning",
        "Long_Range_Understanding",
        "Conflict_Resolution",
    ):
        source_specs = manifest["selection"]["official-full"]["categories"][
            category
        ]["sources"]
        rows_by_category[category] = [
            _row(
                category,
                str(spec["source"]),
                f"{category[:2].upper()}-{index}",
            )
            for index, spec in enumerate(source_specs)
        ]
    data_paths: dict[str, Path] = {}
    for category, rows in rows_by_category.items():
        path = tmp_path / f"{category}.json"
        path.write_text(json.dumps(rows), encoding="utf-8")
        data_paths[category] = path
        manifest["dataset"]["files"][category]["sha256"] = hashlib.sha256(
            path.read_bytes()
        ).hexdigest()
        manifest["dataset"]["files"][category]["size"] = path.stat().st_size
    catalog = tmp_path / "entity2id.json"
    catalog.write_text(
        json.dumps({
            f"<http://dbpedia.org/resource/Movie_{index}>": index
            for index in range(1, 6)
        }),
        encoding="utf-8",
    )
    manifest["dataset"]["auxiliaryFiles"]["recsysCatalog"]["sha256"] = (
        hashlib.sha256(catalog.read_bytes()).hexdigest()
    )
    manifest["dataset"]["auxiliaryFiles"]["recsysCatalog"]["size"] = (
        catalog.stat().st_size
    )
    manifest["officialFull"]["expectedContexts"] = sum(
        len(rows) for rows in rows_by_category.values()
    )
    manifest["officialFull"]["expectedQuestions"] = (
        manifest["officialFull"]["expectedContexts"] * 5
    )
    manifest_path = tmp_path / "manifest.yaml"
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    monkeypatch.setattr(
        "cute_evals.benchmarks.memoryagentbench.adapter."
        "chunk_text_into_sentences",
        lambda text, **_kwargs: [
            text[: len(text) // 2],
            text[len(text) // 2 :],
        ],
    )
    return MemoryAgentBenchAdapter(
        manifest_path,
        data_paths=data_paths,
        recsys_catalog_path=catalog,
        judge=JudgeConfig(
            model="gpt-4o",
            base_url="http://judge.invalid/v1",
            api_key="test",
        ),
    )


def _run_for_answers(
    adapter: MemoryAgentBenchAdapter,
    task,
    answers: list[str],
):
    traces = [
        RunTrace.from_dict(
            {
                "run": {
                    "caseId": task.id,
                    "requestId": f"req-{index}",
                    "status": "SUCCESS",
                    "finalSummaryText": f"FINAL_ANSWER: {answer}",
                    "totalTokens": 10,
                    "durationMs": 5,
                },
                "llmInvocations": [],
                "toolInvocations": [],
                "artifacts": [],
            }
        )
        for index, answer in enumerate(answers)
    ]
    turn_results = [
        {
            "status": "SUCCESS",
            "requestId": f"req-{index}",
            "finalText": f"FINAL_ANSWER: {answer}",
        }
        for index, answer in enumerate(answers)
    ]
    return adapter.collect(
        task,
        traces[-1],
        "result.json",
        turn_results=turn_results,
        traces=traces,
    )


def test_subset_selects_four_categories_and_twenty_official_questions(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter = _adapter(tmp_path, monkeypatch)

    tasks = adapter.tasks("official-subset")

    assert len(tasks) == 4
    assert [task.category for task in tasks] == [
        "Accurate_Retrieval",
        "Test_Time_Learning",
        "Long_Range_Understanding",
        "Conflict_Resolution",
    ]
    assert sum(task.metadata["questionCount"] for task in tasks) == 20
    assert all(len(task.turns) == 5 for task in tasks)
    assert adapter.report_metadata()["scoreLabel"] == "official-subset"
    assert adapter.report_metadata()["questionCount"] == 20


def test_case_seeds_each_chunk_once_and_keeps_context_out_of_queries(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter = _adapter(tmp_path, monkeypatch)
    task = adapter.tasks("official-subset")[0]
    prepared = adapter.prepare(task, tmp_path / "workspace")

    case = adapter.case_for_task(
        task,
        namespace="nightly-01-abcdef",
        prepared=prepared,
    )

    assert prepared.metadata["chunkCount"] == 2
    assert case.user_id.startswith("eval-mab-")
    assert case.memory_seed is not None
    assert case.memory_seed["namespace"].startswith("eval-mab-")
    assert len(case.memory_seed["sessions"]) == 2
    assert len(case.memory_seed["sessions"][0]["turns"]) == 2
    assert case.memory_seed["sessions"][0]["turns"][0]["content"].startswith(
        "Accurate_Retrieval context one"
    )
    assert "context three" in (
        case.memory_seed["sessions"][1]["turns"][0]["content"]
    )
    assert "Accurate_Retrieval context one" not in case.query
    assert case.query.startswith("Search Archival Memory, complete the task below:")
    assert "The event that happens next is:" in case.query
    assert case.answer_contract["linePrefix"] == "FINAL_ANSWER:"
    assert len(task.turns) == 5


def test_score_uses_official_metrics_for_all_questions(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter = _adapter(tmp_path, monkeypatch)
    task = adapter.tasks("official-subset")[1]
    prepared = adapter.prepare(task, tmp_path / "workspace")
    expected_answers = [
        task.metadata["answers"][index][0]
        for index in range(5)
    ]
    traces = [
        RunTrace.from_dict(
            {
                "run": {
                    "caseId": task.id,
                    "requestId": f"req-{index}",
                    "status": "SUCCESS",
                    "finalSummaryText": (
                        f"FINAL_ANSWER: {expected_answers[index]}"
                    ),
                    "totalTokens": 10,
                    "durationMs": 5,
                },
                "llmInvocations": [],
                "toolInvocations": [],
                "artifacts": [],
            }
        )
        for index in range(5)
    ]
    turn_results = [
        {
            "status": "SUCCESS",
            "requestId": f"req-{index}",
            "finalText": f"FINAL_ANSWER: {expected_answers[index]}",
        }
        for index in range(5)
    ]
    run = adapter.collect(
        task,
        traces[-1],
        "result.json",
        prepared=prepared,
        turn_results=turn_results,
        traces=traces,
    )

    result = adapter.score(run)

    assert result.passed
    official = next(
        score for score in result.scores if score.name == "official_accuracy"
    )
    assert official.value == 1.0
    assert official.passed is True
    assert sum(
        score.name == "official_question_accuracy"
        for score in result.scores
    ) == 5
    diagnostics = {
        score.name: score
        for score in result.scores
        if score.scorer == "memoryagentbench.diagnostic"
    }
    assert diagnostics["diagnostic_format_normalized_accuracy"].value == 1.0
    assert diagnostics["diagnostic_tokens_per_question"].value == 10.0
    assert all(not score.hard_gate for score in diagnostics.values())
    assert result.trace.total_tokens == 50
    assert result.trace.duration_ms == 25


def test_official_full_loads_all_pinned_sources(tmp_path: Path, monkeypatch) -> None:
    adapter = _adapter(tmp_path, monkeypatch)

    tasks = adapter.tasks("official-full")

    assert len(tasks) == 22
    assert sum(task.metadata["questionCount"] for task in tasks) == 110
    assert {task.metadata["scorer"] for task in tasks} == {
        "text",
        "longmemeval_judge",
        "ruler_recall",
        "summary_judge",
        "recsys_recall",
    }
    assert adapter.report_metadata()["scoreLabel"] == "official-full"


def test_official_full_uses_compatibility_label_for_nonofficial_judge(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter = _adapter(tmp_path, monkeypatch)
    adapter.judge = JudgeConfig(
        model="group/deepseek",
        base_url="http://judge.invalid/v1",
        api_key="test",
    )

    adapter.tasks("official-full")

    metadata = adapter.report_metadata()
    assert metadata["scoreLabel"] == "compatibility-full"
    assert (
        metadata["officialEvaluation"]["judgeCompatibility"]
        == "compatible-nonofficial-model"
    )


def test_dataset_path_reuses_category_named_local_parquet(
    tmp_path: Path,
) -> None:
    manifest = yaml.safe_load(
        (
            Path(__file__).resolve().parents[1]
            / "src"
            / "cute_evals"
            / "benchmarks"
            / "memoryagentbench"
            / "manifest.yaml"
        ).read_text(encoding="utf-8")
    )
    data_home = tmp_path / "data"
    local_file = (
        data_home
        / "memoryagentbench"
        / manifest["dataset"]["revision"]
        / "Accurate_Retrieval.parquet"
    )
    local_file.parent.mkdir(parents=True)
    local_file.write_bytes(b"local-official-parquet")
    manifest["dataset"]["files"]["Accurate_Retrieval"]["sha256"] = hashlib.sha256(
        local_file.read_bytes()
    ).hexdigest()
    manifest_path = tmp_path / "manifest.yaml"
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )

    adapter = MemoryAgentBenchAdapter(
        manifest_path,
        data_home=data_home,
    )

    assert adapter.dataset_path("Accurate_Retrieval") == local_file


def test_official_metric_functions_match_upstream_semantics() -> None:
    assert exact_match("The answer.", "answer")
    assert substring_exact_match("Answer: C. The Brandt couple", "C. The Brandt couple")
    assert ruler_recall("France, Germany", ["France", "Italy"]) == 0.5
    assert score_answer(
        "substring_exact_match",
        "final: AR-2",
        ["AR-1", "AR-2"],
    )


def test_official_full_metadata_matches_pinned_dataset() -> None:
    data_home = Path(os.environ.get("EVAL_DATA_HOME", Path.home() / ".cache" / "cute-evals"))
    adapter = MemoryAgentBenchAdapter(data_home=data_home)
    data_dir = data_home / "memoryagentbench" / adapter.revision
    required = [
        data_dir / f"{category}.parquet"
        for category in (
            "Accurate_Retrieval",
            "Test_Time_Learning",
            "Long_Range_Understanding",
            "Conflict_Resolution",
        )
    ]
    if not all(path.exists() for path in required):
        pytest.skip("pinned MemoryAgentBench parquet files are not available")

    tasks = adapter.tasks("official-full")

    assert len(tasks) == 146
    assert sum(task.metadata["questionCount"] for task in tasks) == 3671


def test_ruler_scorer_uses_official_recall(tmp_path: Path, monkeypatch) -> None:
    adapter = _adapter(tmp_path, monkeypatch)
    task = next(
        task
        for task in adapter.tasks("official-full")
        if task.metadata["scorer"] == "ruler_recall"
    )
    expected = [
        task.metadata["answers"][index][0]
        for index in range(5)
    ]
    run = _run_for_answers(adapter, task, expected[:-1] + ["not in memory"])

    result = adapter.score(run)

    primary = next(
        score for score in result.scores if score.name == "official_ruler_recall"
    )
    assert primary.value == 0.8
    assert primary.passed is False
    assert math.isclose(
        next(
            score.value
            for score in result.scores
            if score.name == "official_question_ruler_recall"
        ),
        1.0,
    )


def test_longmemeval_scorer_dispatches_official_judge(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter = _adapter(tmp_path, monkeypatch)
    task = next(
        task
        for task in adapter.tasks("official-full")
        if task.metadata["scorer"] == "longmemeval_judge"
    )
    calls: list[dict] = []

    def fake_judge(**kwargs):
        calls.append(kwargs)
        return {
            "label": True,
            "rawLabel": "yes",
            "prompt": f"prompt-{len(calls)}",
        }

    monkeypatch.setattr(
        "cute_evals.benchmarks.memoryagentbench.adapter."
        "judge_longmemeval_answer",
        fake_judge,
    )
    run = _run_for_answers(
        adapter,
        task,
        [f"answer {index}" for index in range(5)],
    )

    result = adapter.score(run)

    assert len(calls) == 5
    assert calls[0]["task"] == task.metadata["questionTypes"][0]
    assert calls[0]["question"] == task.metadata["questions"][0]
    assert calls[0]["answer"] == task.metadata["answers"][0][0]
    assert calls[0]["response"] == "answer 0"
    assert calls[0]["abstention"] is False
    primary = next(
        score for score in result.scores if score.name == "official_accuracy"
    )
    assert primary.value == 1.0
    assert primary.passed is True


def test_recsys_scorer_reports_recall_at_1_5_and_10(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter = _adapter(tmp_path, monkeypatch)
    task = next(
        task
        for task in adapter.tasks("official-full")
        if task.metadata["scorer"] == "recsys_recall"
    )
    recommendations = [
        "\n".join(
            f"{position}.Movie {movie_id}"
            for position, movie_id in enumerate(
                [index + 1, 5, 4, 3, 2],
                start=1,
            )
        )
        for index in range(5)
    ]
    run = _run_for_answers(adapter, task, recommendations)

    result = adapter.score(run)

    assert next(
        score.value
        for score in result.scores
        if score.name == "official_recall_at_5"
    ) == 1.0
    assert next(
        score.value
        for score in result.scores
        if score.name == "official_recall_at_1"
    ) == 1.0
    assert next(
        score.value
        for score in result.scores
        if score.name == "official_recall_at_10"
    ) == 1.0


def test_recsys_official_recall_uses_ground_truth_cutoffs() -> None:
    result = score_recsys_recommendations(
        prediction="1.Movie One\n2.Movie Three\n3.Movie Two",
        ground_truth_ids=["1", "2"],
        id_to_name={1: "Movie One", 2: "Movie Two", 3: "Movie Three"},
        candidate_movies=["Movie One", "Movie Two", "Movie Three"],
    )

    assert result["predictedMovies"] == [
        "Movie One",
        "Movie Three",
        "Movie Two",
    ]
    assert result["recall@1"] == 0.5
    assert result["recall@5"] == 1.0
    assert result["recall@10"] == 1.0
    assert find_nearest_movie(
        "Movie Too",
        ["Movie One", "Movie Too"],
    )["nearest_movie"] == "Movie Too"


def test_infbench_scorer_combines_three_official_scores(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter = _adapter(tmp_path, monkeypatch)
    task = next(
        task
        for task in adapter.tasks("official-full")
        if task.metadata["scorer"] == "summary_judge"
    )
    calls: list[dict] = []

    def fake_summary(**kwargs):
        calls.append(kwargs)
        return {
            "f1": 0.625,
            "fluency": 1.0,
            "recall": 0.5,
            "precision": 0.75,
        }

    monkeypatch.setattr(
        "cute_evals.benchmarks.memoryagentbench.adapter."
        "score_infbench_summary",
        fake_summary,
    )
    run = _run_for_answers(
        adapter,
        task,
        [f"summary {index}" for index in range(5)],
    )

    result = adapter.score(run)

    assert len(calls) == 5
    assert calls[0]["expert_summary"] == task.metadata["answers"][0][0]
    assert calls[0]["keypoints"] == task.raw["metadata"]["keypoints"]
    primary = next(
        score for score in result.scores if score.name == "official_infbench_f1"
    )
    assert primary.value == 0.625


def test_infbench_judge_uses_all_three_official_prompts(monkeypatch) -> None:
    responses = iter([
        {"fluency": 1},
        {"recall": 2},
        {"precision": 3, "sentence_count": 4},
    ])
    prompts: list[str] = []

    def fake_judge(_judge, prompt, *, timeout_seconds):
        prompts.append(prompt)
        return next(responses), prompt

    monkeypatch.setattr(official_summarization, "_judge_json", fake_judge)
    result = official_summarization.score_infbench_summary(
        judge=JudgeConfig(
            model="gpt-4o",
            base_url="http://judge.invalid/v1",
            api_key="test",
        ),
        summary="A concise summary.",
        expert_summary="The expert summary.",
        keypoints=["first", "second", "third", "fourth", "fifth"],
    )

    assert len(prompts) == 3
    assert "1. first\n2. second" in prompts[1]
    assert "The expert summary." in prompts[2]
    assert result["recall"] == 0.4
    assert result["precision"] == 0.75
    assert math.isclose(result["f1"], 2 * 0.4 * 0.75 / (0.4 + 0.75))


def test_judge_and_catalog_failures_are_unverified(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter = _adapter(tmp_path, monkeypatch)
    longmemeval_task = next(
        task
        for task in adapter.tasks("official-full")
        if task.metadata["scorer"] == "longmemeval_judge"
    )
    recsys_task = next(
        task
        for task in adapter.tasks("official-full")
        if task.metadata["scorer"] == "recsys_recall"
    )

    def failing_judge(**_kwargs):
        raise RuntimeError("judge unavailable")

    monkeypatch.setattr(
        "cute_evals.benchmarks.memoryagentbench.adapter."
        "judge_longmemeval_answer",
        failing_judge,
    )
    judge_result = adapter.score(
        _run_for_answers(adapter, longmemeval_task, ["answer"] * 5)
    )
    assert next(
        score
        for score in judge_result.scores
        if score.name == "official_accuracy"
    ).status == "unverified"

    def failing_catalog():
        raise ValueError("catalog unavailable")

    monkeypatch.setattr(adapter, "_recsys_catalog_data", failing_catalog)
    recsys_result = adapter.score(
        _run_for_answers(adapter, recsys_task, ["1.Movie 1"] * 5)
    )
    assert next(
        score
        for score in recsys_result.scores
        if score.name == "official_recall_at_5"
    ).status == "unverified"
