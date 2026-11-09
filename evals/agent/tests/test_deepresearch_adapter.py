from __future__ import annotations

import json
import subprocess
from pathlib import Path

from cute_evals.benchmarks.deepresearch.adapter import (
    DEFAULT_MANIFEST,
    DeepResearchAdapter,
)
from cute_evals.benchmarks.deepresearch.official import evaluate_official
from cute_evals.models import RunTrace


def _write_query(path: Path, count: int = 2) -> str:
    rows = []
    for index in range(1, count + 1):
        rows.append(
            {
                "id": index,
                "topic": "Finance & Business" if index % 2 else "Science",
                "language": "zh" if index % 2 else "en",
                "prompt": f"Research task {index}",
            }
        )
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )
    payload = path.read_bytes().replace(b"\r\n", b"\n")
    return __import__("hashlib").sha256(payload).hexdigest()


def _manifest_for_hash(path: Path, digest: str) -> Path:
    text = DEFAULT_MANIFEST.read_text(encoding="utf-8")
    text = text.replace(
        "62b197a264a027fd4e2e795ff322a82dd6d566f5e350596ccc7572539ec987d1",
        digest,
    )
    path.write_text(text, encoding="utf-8")
    return path


def _trace(case_id: str, text: str) -> RunTrace:
    return RunTrace.from_dict(
        {
            "run": {
                "caseId": case_id,
                "requestId": f"request-{case_id}",
                "sessionId": f"session-{case_id}",
                "status": "SUCCESS",
                "finalSummaryText": text,
                "totalTokens": 123,
                "durationMs": 456,
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {},
        }
    )


def test_deepresearch_adapter_exports_official_submission_shape(
    tmp_path: Path,
) -> None:
    query = tmp_path / "query.jsonl"
    digest = _write_query(query)
    manifest = _manifest_for_hash(tmp_path / "manifest.yaml", digest)
    adapter = DeepResearchAdapter(
        manifest_path=manifest,
        query_path=query,
        data_home=tmp_path / "cache",
        limit=2,
    )
    tasks = adapter.tasks("official-full")

    assert [task.id for task in tasks] == ["1", "2"]
    assert tasks[0].metadata["language"] == "zh"
    assert tasks[1].metadata["language"] == "en"
    assert adapter.report_metadata()["split"] == "compatibility-subset"
    assert adapter.report_metadata()["scoreLabel"] == "submission-contract"

    runs = [
        adapter.collect(
            tasks[0],
            _trace("1", "# Report\n\nFact [source](https://example.com/a)."),
            "trace.json",
        ),
        adapter.collect(tasks[1], _trace("2", "# Report\n\nNo URL."), "trace.json"),
    ]
    submission = adapter.write_submission(runs, tmp_path / "submission.jsonl")
    rows = [json.loads(line) for line in submission.read_text().splitlines()]

    assert rows[0] == {
        "id": 1,
        "prompt": "Research task 1",
        "article": "# Report\n\nFact [source](https://example.com/a).",
    }
    assert rows[1]["id"] == 2
    assert rows[1]["article"] == "# Report\n\nNo URL."

    result = adapter.score(runs[0])
    assert result.quality_passed is True
    assert result.unverified is True
    assert result.to_dict()["scores"][0]["name"] == "article_present"
    assert result.to_dict()["scores"][1]["evidence"]["citationCount"] == 1


def test_deepresearch_missing_official_credentials_is_unverified(
    tmp_path: Path,
) -> None:
    query = tmp_path / "query.jsonl"
    digest = _write_query(query)
    manifest = _manifest_for_hash(tmp_path / "manifest.yaml", digest)
    adapter = DeepResearchAdapter(
        manifest_path=manifest,
        query_path=query,
        data_home=tmp_path / "cache",
        limit=1,
    )
    tasks = adapter.tasks("official-full")
    submission = adapter.write_submission(
        [adapter.collect(tasks[0], _trace("1", "report"), "trace.json")],
        tmp_path / "submission.jsonl",
    )

    result = adapter.evaluate_official(
        raw_data_path=submission,
        output_dir=tmp_path / "official",
        race_model=None,
        clean_model=None,
        fact_model=None,
        base_url=None,
        api_key=None,
        jina_api_key=None,
    )

    assert result["status"] == "unverified"
    assert "API key" in result["reason"]


def test_deepresearch_official_evaluator_runs_race_and_fact(
    tmp_path: Path,
    monkeypatch,
) -> None:
    source = tmp_path / "source"
    query = source / "data" / "prompt_data" / "query.jsonl"
    query.parent.mkdir(parents=True)
    digest = _write_query(query)
    raw = tmp_path / "submission" / "cute-agent.jsonl"
    raw.parent.mkdir()
    raw.write_text('{"id":1,"prompt":"q","article":"a"}\n', encoding="utf-8")

    def fake_run(command, *, cwd, env, text, encoding, errors, capture_output, check):
        if command[2] == "deepresearch_bench_race.py":
            output_dir = Path(command[command.index("--output_dir") + 1])
            output_dir.mkdir(parents=True, exist_ok=True)
            (output_dir / "race_result.txt").write_text(
                "Comprehensiveness: 0.7\n"
                "Insight: 0.8\n"
                "Instruction Following: 0.9\n"
                "Readability: 0.6\n"
                "Overall Score: 0.75\n",
                encoding="utf-8",
            )
        elif "utils.stat" in command:
            output = Path(command[command.index("--output_path") + 1])
            output.write_text(
                "total_citations: 12.0\n"
                "total_valid_citations: 9.0\n"
                "valid_rate: 0.75\n",
                encoding="utf-8",
            )
        else:
            output = Path(command[command.index("--output_path") + 1])
            output.write_text("", encoding="utf-8")
        return subprocess.CompletedProcess(command, 0, "", "")

    monkeypatch.setattr(
        "cute_evals.benchmarks.deepresearch.official.subprocess.run",
        fake_run,
    )

    result = evaluate_official(
        source_dir=source,
        data_home=tmp_path / "cache",
        revision="test-revision",
        query_sha256=digest,
        raw_data_path=raw,
        target_model="cute-agent",
        output_dir=tmp_path / "official",
        race_model="gpt-test",
        clean_model="gpt-clean",
        fact_model="gpt-fact",
        base_url="http://127.0.0.1:3425/v1",
        api_key="test-key",
        jina_api_key="jina-key",
    )

    assert result["status"] == "complete"
    assert result["race"]["Overall Score"] == 0.75
    assert result["fact"]["valid_rate"] == 0.75
    assert result["judge"]["raceModel"] == "gpt-test"
