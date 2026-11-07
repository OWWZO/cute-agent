from __future__ import annotations

import json
from pathlib import Path

from cute_evals.benchmarks.base import PreparedTask
from cute_evals.benchmarks.gaia.adapter import DEFAULT_MANIFEST, GAIAAdapter
from cute_evals.models import RunTrace


def _row(task_id: str, level: str, question: str, answer: str, file_name: str = "") -> dict:
    return {
        "task_id": task_id,
        "Question": question,
        "Level": level,
        "Final answer": answer,
        "file_name": file_name,
        "file_path": f"2023/validation/{file_name}" if file_name else "",
    }


def test_gaia_uses_official_scorer_and_pinned_subset(tmp_path: Path) -> None:
    metadata = tmp_path / "metadata.jsonl"
    rows = [
        _row("b", "1", "What is 2 + 2?", "4"),
        _row("a", "1", "Name the planet.", "Earth"),
        _row("c", "2", "List two colors.", "red, blue"),
        _row("d", "2", "What is 10% of 50?", "5"),
        _row("e", "3", "Who wrote it?", "Ada Lovelace"),
    ]
    metadata.write_text(
        "\n".join(json.dumps(row) for row in rows),
        encoding="utf-8",
    )
    adapter = GAIAAdapter(metadata_path=metadata, data_home=tmp_path / "cache")
    tasks = adapter.tasks("official-subset")

    assert [task.id for task in tasks] == ["a", "b", "c", "d", "e"]
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": "a",
                "requestId": "request-a",
                "sessionId": "session-a",
                "status": "SUCCESS",
                "finalSummaryText": "FINAL_ANSWER: earth",
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {},
        }
    )
    run = adapter.collect(tasks[0], trace, "trace.json")
    result = adapter.score(run)

    assert result.passed is True
    assert result.scores[0].name == "official_exact_match"
    assert result.scores[0].value is True
    assert result.scores[1].name == "answer_contract"
    assert result.scores[1].value is True
    assert adapter.report_metadata()["scoreLabel"] == "official-subset"
    assert adapter.report_metadata()["answerContract"]["version"] == (
        "gaia-final-answer-v1"
    )


def test_gaia_official_full_loads_every_validation_row(tmp_path: Path) -> None:
    metadata = tmp_path / "metadata.jsonl"
    rows = [
        _row("b", "1", "Question B", "B"),
        _row("a", "1", "Question A", "A"),
        _row("c", "2", "Question C", "C"),
        _row("d", "2", "Question D", "D"),
        _row("e", "3", "Question E", "E"),
    ]
    metadata.write_text(
        "\n".join(json.dumps(row) for row in rows),
        encoding="utf-8",
    )
    adapter = GAIAAdapter(metadata_path=metadata, data_home=tmp_path / "cache")

    assert [task.id for task in adapter.tasks("official-subset")] == [
        "a",
        "b",
        "c",
        "d",
        "e",
    ]
    assert [task.id for task in adapter.tasks("official-full")] == [
        "a",
        "b",
        "c",
        "d",
        "e",
    ]
    assert adapter.report_metadata()["scoreLabel"] == "official-full"


def test_gaia_preserves_declared_calibration_label_and_level_metadata(
    tmp_path: Path,
) -> None:
    metadata = tmp_path / "metadata.jsonl"
    metadata.write_text(
        json.dumps(_row("a", "2", "Question A", "A")),
        encoding="utf-8",
    )
    manifest = tmp_path / "manifest.yaml"
    manifest.write_text(
        DEFAULT_MANIFEST.read_text(encoding="utf-8").replace(
            "scoreLabel: official-subset",
            "scoreLabel: official-calibration-20",
        ),
        encoding="utf-8",
    )
    adapter = GAIAAdapter(
        manifest_path=manifest,
        metadata_path=metadata,
        data_home=tmp_path / "cache",
    )
    task = adapter.tasks("official-subset")[0]

    case = adapter.case_for_task(task)

    assert adapter.report_metadata()["scoreLabel"] == "official-calibration-20"
    assert case.metadata["level"] == "2"
    assert case.metadata["split"] == "official-subset"


def test_gaia_materializes_attachment_and_preserves_hash(tmp_path: Path) -> None:
    metadata = tmp_path / "metadata.jsonl"
    metadata.write_text(
        json.dumps(_row("a", "1", "Read the file.", "42", "paper.txt")),
        encoding="utf-8",
    )
    adapter = GAIAAdapter(metadata_path=metadata, data_home=tmp_path / "cache")
    task = adapter.tasks("official-subset")[0]
    cached = adapter.cache_dir / "files" / "paper.txt"
    cached.parent.mkdir(parents=True)
    cached.write_text("important evidence", encoding="utf-8")

    prepared = adapter.prepare(task, tmp_path / "workspace")
    attachment = prepared.metadata["attachment"]

    assert (prepared.workspace / "paper.txt").read_text(encoding="utf-8") == "important evidence"
    assert attachment["available"] is True
    assert len(attachment["sha256"]) == 64


def test_gaia_missing_attachment_is_unverified_not_passed(tmp_path: Path) -> None:
    metadata = tmp_path / "metadata.jsonl"
    metadata.write_text(
        json.dumps(_row("a", "1", "Read the file.", "42", "missing.txt")),
        encoding="utf-8",
    )
    adapter = GAIAAdapter(metadata_path=metadata, data_home=tmp_path / "cache")
    task = adapter.tasks("official-subset")[0]
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": "a",
                "requestId": "request-a",
                "sessionId": "session-a",
                "status": "SUCCESS",
                "finalSummaryText": "FINAL_ANSWER: 42",
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {},
        }
    )
    run = adapter.collect(
        task,
        trace,
        "trace.json",
        prepared=PreparedTask(
            task=task,
            workspace=tmp_path / "workspace",
            metadata={"attachment": None},
        ),
    )

    result = adapter.score(run)

    assert result.unverified is True
    assert result.to_dict()["status"] == "unverified"


def test_gaia_missing_answer_contract_fails_without_rejecting_raw_answer(tmp_path: Path) -> None:
    metadata = tmp_path / "metadata.jsonl"
    metadata.write_text(
        json.dumps(_row("a", "1", "Name the planet.", "Earth")),
        encoding="utf-8",
    )
    adapter = GAIAAdapter(metadata_path=metadata, data_home=tmp_path / "cache")
    task = adapter.tasks("official-subset")[0]
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": "a",
                "requestId": "request-a",
                "sessionId": "session-a",
                "status": "SUCCESS",
                "finalSummaryText": "Earth",
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {},
        }
    )

    result = adapter.score(adapter.collect(task, trace, "trace.json"))

    assert result.passed is False
    assert result.scores[0].name == "official_exact_match"
    assert result.scores[0].passed is False
    assert result.scores[0].evidence["answerContractPresent"] is False
    assert result.scores[1].name == "answer_contract"
    assert result.scores[1].passed is False


def test_gaia_budget_exceeded_cannot_pass_even_with_correct_final_answer(
    tmp_path: Path,
) -> None:
    metadata = tmp_path / "metadata.jsonl"
    metadata.write_text(
        json.dumps(_row("a", "1", "Name the planet.", "Earth")),
        encoding="utf-8",
    )
    adapter = GAIAAdapter(metadata_path=metadata, data_home=tmp_path / "cache")
    task = adapter.tasks("official-subset")[0]
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": "a",
                "requestId": "request-a",
                "sessionId": "session-a",
                "status": "FAILED",
                "errorCode": "EVAL_BUDGET_EXCEEDED",
                "finalSummaryText": "FINAL_ANSWER: Earth",
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {"budget": {"exceeded": "wall_time"}},
        }
    )

    result = adapter.score(adapter.collect(task, trace, "trace.json"))

    assert result.budget_exceeded is True
    assert result.quality_passed is True
    assert result.slo_passed is False
    assert result.passed is False
    assert result.to_dict()["status"] == "failed"
    assert result.to_dict()["qualityStatus"] == "passed"
    assert result.to_dict()["sloStatus"] == "failed"
