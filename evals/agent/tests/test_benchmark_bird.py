from __future__ import annotations

import hashlib
import json
import shutil
import sqlite3
import urllib.error
import urllib.request
from pathlib import Path

import pytest
import yaml

from cute_evals.benchmarks.bird.adapter import BirdAdapter, OfficialExecution
from cute_evals.models import RunTrace


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _manifest(
    root: Path,
    *,
    dataset: Path,
    database: Path,
) -> Path:
    source = (
        Path(__file__).parents[1]
        / "src"
        / "cute_evals"
        / "benchmarks"
        / "bird"
        / "manifest.yaml"
    )
    payload = yaml.safe_load(source.read_text(encoding="utf-8"))
    database_sha = _sha256(database)
    payload["dataset"]["sha256"] = _sha256(dataset)
    payload["taskIds"] = [0, 1]
    payload["databases"] = {
        "db_one": {
            "imageDigest": "sha256:" + "1" * 64,
            "seedLayer": "sha256:" + "2" * 64,
            "seedSha256": database_sha,
        },
        "db_two": {
            "imageDigest": "sha256:" + "3" * 64,
            "seedLayer": "sha256:" + "4" * 64,
            "seedSha256": database_sha,
        },
    }
    path = root / "manifest.yaml"
    path.write_text(yaml.safe_dump(payload, sort_keys=False), encoding="utf-8")
    return path


def _fixtures(tmp_path: Path) -> tuple[BirdAdapter, list[dict], Path]:
    database = tmp_path / "db_one.sqlite"
    connection = sqlite3.connect(database)
    connection.executescript(
        """
        CREATE TABLE students (id INTEGER PRIMARY KEY, name TEXT, score INTEGER);
        INSERT INTO students VALUES (1, 'Ada', 99), (2, 'Grace', 87);
        """
    )
    connection.close()
    shutil.copy2(database, tmp_path / "db_two.sqlite")
    rows = [
        {
            "question_id": 0,
            "db_id": "db_one",
            "question": "Which student has the highest score?",
            "evidence": "Scores are in the students table.",
            "SQL": "SELECT name FROM students ORDER BY score DESC LIMIT 1",
            "difficulty": "simple",
        },
        {
            "question_id": 1,
            "db_id": "db_two",
            "question": "How many students are there?",
            "evidence": "",
            "SQL": "SELECT COUNT(*) FROM students",
            "difficulty": "moderate",
        },
    ]
    dataset = tmp_path / "mini_dev_sqlite.jsonl"
    dataset.write_text(
        "\n".join(json.dumps(row) for row in rows) + "\n",
        encoding="utf-8",
    )
    adapter = BirdAdapter(
        _manifest(tmp_path, dataset=dataset, database=database),
        data_home=tmp_path / "cache",
        dataset_path=dataset,
        database_root=tmp_path,
    )
    return adapter, rows, database


def _post(endpoint: str, path: str, payload: dict) -> dict:
    request = urllib.request.Request(
        endpoint + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def test_bird_preserves_official_rows_and_subset(tmp_path: Path) -> None:
    adapter, rows, _ = _fixtures(tmp_path)

    tasks = adapter.tasks("official-subset")

    assert [task.id for task in tasks] == ["0", "1"]
    assert tasks[0].question == rows[0]["question"]
    assert tasks[0].raw == rows[0]
    assert tasks[0].metadata["evidence"] == rows[0]["evidence"]
    assert adapter.report_metadata()["scoreLabel"] == "official-subset"
    assert adapter.report_metadata()["datasetRevision"] == (
        "5b256b5a9c17478f06ed6912612e9a14d17065c8"
    )


def test_bird_official_full_selects_all_official_rows(tmp_path: Path) -> None:
    adapter, rows, _ = _fixtures(tmp_path)
    manifest = yaml.safe_load(adapter.manifest_path.read_text(encoding="utf-8"))
    manifest["taskIds"] = [0]
    adapter.manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    adapter = BirdAdapter(
        adapter.manifest_path,
        data_home=tmp_path / "cache",
        dataset_path=adapter.dataset_path,
        database_root=tmp_path,
    )

    assert [task.id for task in adapter.tasks("official-full")] == ["0", "1"]
    assert adapter.report_metadata()["scoreLabel"] == "official-full"
    assert len(rows) == 2


def test_bird_prepare_copies_seed_and_keeps_sql_runtime_read_only(
    tmp_path: Path,
) -> None:
    adapter, _, database = _fixtures(tmp_path)
    task = adapter.tasks("official-subset")[0]

    prepared = adapter.prepare(task, tmp_path / "workspace")
    server = prepared.metadata["taskServer"]
    try:
        assert prepared.metadata["databaseSha256"] == _sha256(database)
        tools = _post(server.endpoint, "/tools", {})["result"]
        assert [tool["name"] for tool in tools] == [
            "list_tables",
            "describe_table",
            "sql_query",
            "submit_sql",
        ]
        listed = _post(
            server.endpoint,
            "/call",
            {"toolName": "list_tables", "arguments": {}},
        )
        assert listed["result"] == ["students"]
        described = _post(
            server.endpoint,
            "/call",
            {"toolName": "describe_table", "arguments": {"table_name": "students"}},
        )
        assert described["result"]["columns"][1]["name"] == "name"
        queried = _post(
            server.endpoint,
            "/call",
            {
                "toolName": "sql_query",
                "arguments": {"sql": "SELECT name FROM students ORDER BY id"},
            },
        )
        assert queried["result"]["rows"] == [["Ada"], ["Grace"]]
        with pytest.raises(urllib.error.HTTPError, match="400"):
            _post(
                server.endpoint,
                "/call",
                {
                    "toolName": "sql_query",
                    "arguments": {"sql": "DELETE FROM students"},
                },
            )
        submitted = _post(
            server.endpoint,
            "/call",
            {
                "toolName": "submit_sql",
                "arguments": {
                    "sql": "SELECT name FROM students ORDER BY score DESC LIMIT 1"
                },
            },
        )
        assert submitted["result"]["submitted"] is True
        assert (
            sqlite3.connect(database).execute(
                "SELECT COUNT(*) FROM students"
            ).fetchone()[0]
            == 2
        )
    finally:
        server.close()


def test_bird_score_uses_last_ledger_sql_for_official_ex(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    adapter, _, _ = _fixtures(tmp_path)
    task = adapter.tasks("official-subset")[0]
    prepared = adapter.prepare(task, tmp_path / "workspace")
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": "0",
                "requestId": "request-0",
                "status": "SUCCESS",
                "finalSummaryText": "Ada",
                "totalTokens": 123,
                "durationMs": 456,
            },
            "toolInvocations": [
                {
                    "toolName": "sql_query",
                    "inputJson": '{"sql":"SELECT COUNT(*) FROM students"}',
                    "status": "SUCCESS",
                },
                {
                    "toolName": "submit_sql",
                    "inputJson": (
                        '{"sql":"SELECT name FROM students '
                        'ORDER BY score DESC LIMIT 1"}'
                    ),
                    "status": "SUCCESS",
                },
                {
                    "toolName": "sql_query",
                    "inputJson": '{"sql":"SELECT COUNT(*) FROM students"}',
                    "status": "SUCCESS",
                },
            ],
            "llmInvocations": [],
            "artifacts": [],
        }
    )
    monkeypatch.setattr(
        adapter,
        "_official_execution",
        lambda **_: OfficialExecution(passed=True, verified=True),
    )
    try:
        run = adapter.collect(task, trace, "trace.json", prepared=prepared)
        result = adapter.score(run)
    finally:
        prepared.metadata["taskServer"].close()

    official = result.scores[0]
    assert result.passed is True
    assert official.name == "official_execution_accuracy"
    assert official.passed is True
    assert official.evidence["actualSql"] == (
        "SELECT name FROM students ORDER BY score DESC LIMIT 1"
    )
    assert result.scores[2].name == "submit_sql_contract"
    assert result.scores[2].passed is True
    assert result.scores[3].name == "sql_validity"
    assert result.scores[3].passed is True


def test_bird_write_sql_cannot_receive_official_credit(tmp_path: Path) -> None:
    adapter, _, _ = _fixtures(tmp_path)
    task = adapter.tasks("official-subset")[0]
    prepared = adapter.prepare(task, tmp_path / "workspace")
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": "0",
                "requestId": "request-0",
                "status": "SUCCESS",
                "finalSummaryText": "done",
            },
            "toolInvocations": [
                {
                    "toolName": "sql_query",
                    "inputJson": '{"sql":"DELETE FROM students"}',
                    "status": "SUCCESS",
                }
            ],
            "llmInvocations": [],
            "artifacts": [],
        }
    )
    try:
        result = adapter.score(
            adapter.collect(task, trace, "trace.json", prepared=prepared)
        )
    finally:
        prepared.metadata["taskServer"].close()

    assert result.passed is False
    assert result.scores[0].passed is False
    assert result.scores[2].passed is False
    assert result.scores[3].passed is False
    assert "SELECT/CTE" in result.scores[3].evidence["error"]


def test_bird_rejects_tampered_seed_snapshot(tmp_path: Path) -> None:
    adapter, _, database = _fixtures(tmp_path)
    task = adapter.tasks("official-subset")[0]
    connection = sqlite3.connect(database)
    connection.execute("DELETE FROM students")
    connection.commit()
    connection.close()

    with pytest.raises(ValueError, match="database snapshot hash mismatch"):
        adapter.prepare(task, tmp_path / "workspace")
