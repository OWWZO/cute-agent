from __future__ import annotations

import hashlib
import json
import shutil
import sqlite3
import textwrap
import urllib.error
import urllib.request
from pathlib import Path

import pytest
import yaml

from cute_evals.benchmarks.spider2.adapter import Spider2Adapter
from cute_evals.models import RunTrace

OFFICIAL_FUNCTIONS = textwrap.dedent(
    """
    import math
    import re
    from functools import lru_cache

    import pandas as pd


    @lru_cache(maxsize=None)
    def load_gold_csv(file_path: str) -> pd.DataFrame:
        return pd.read_csv(file_path)


    def extract_sql_query(pred_sql_query: str) -> str:
        pattern = r"```sql\\n(.*?)\\n```"
        match = re.search(pattern, pred_sql_query, re.DOTALL)
        if match:
            return match.group(1).strip()
        return pred_sql_query


    def compare_multi_pandas_table(pred, multi_gold, multi_condition_cols=None, multi_ignore_order=False) -> int:
        if not multi_gold:
            return 0
        if multi_condition_cols in (None, [], [[]], [None]):
            multi_condition_cols = [[] for _ in range(len(multi_gold))]
        elif len(multi_gold) > 1 and not all(isinstance(sublist, list) for sublist in multi_condition_cols):
            multi_condition_cols = [multi_condition_cols for _ in range(len(multi_gold))]
        multi_ignore_order = [multi_ignore_order for _ in range(len(multi_gold))]
        for index, gold in enumerate(multi_gold):
            if compare_pandas_table(pred, gold, multi_condition_cols[index], multi_ignore_order[index]):
                return 1
        return 0


    def compare_pandas_table(pred, gold, condition_cols=None, ignore_order=False) -> int:
        tolerance = 1e-2

        def normalize(value):
            if pd.isna(value):
                return 0
            return value

        def vectors_match(v1, v2, tol=tolerance, ignore_order_=False):
            v1 = [normalize(x) for x in v1]
            v2 = [normalize(x) for x in v2]
            if ignore_order_:
                v1 = sorted(v1, key=lambda x: (x is None, str(x), isinstance(x, (int, float))))
                v2 = sorted(v2, key=lambda x: (x is None, str(x), isinstance(x, (int, float))))
            if len(v1) != len(v2):
                return False
            for a, b in zip(v1, v2):
                if pd.isna(a) and pd.isna(b):
                    continue
                if isinstance(a, (int, float)) and isinstance(b, (int, float)):
                    if not math.isclose(float(a), float(b), abs_tol=tol):
                        return False
                elif a != b:
                    return False
            return True

        if condition_cols:
            if not isinstance(condition_cols, (list, tuple)):
                condition_cols = [condition_cols]
            gold_cols = gold.iloc[:, condition_cols]
        else:
            gold_cols = gold
        pred_cols = pred
        t_gold_list = gold_cols.transpose().values.tolist()
        t_pred_list = pred_cols.transpose().values.tolist()
        score = 1
        for gold_vector in t_gold_list:
            if not any(vectors_match(gold_vector, pred_vector, ignore_order_=ignore_order) for pred_vector in t_pred_list):
                score = 0
                break
        return score
    """
)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _source_files(root: Path) -> tuple[Path, Path, Path, Path]:
    evaluation = root / "evaluation_suite"
    gold = evaluation / "gold"
    gold_sql = gold / "sql" / "local004.sql"
    gold_csv = gold / "exec_result" / "local004_a.csv"
    gold_sql.parent.mkdir(parents=True)
    gold_csv.parent.mkdir(parents=True)
    evaluator = evaluation / "evaluate.py"
    evaluator.write_text(OFFICIAL_FUNCTIONS, encoding="utf-8")
    (evaluation / "evaluate_utils.py").write_text("", encoding="utf-8")
    (gold / "spider2lite_eval.jsonl").write_text(
        json.dumps(
            {
                "instance_id": "local004",
                "condition_cols": [0],
                "ignore_order": True,
                "toks": "1",
            }
        )
        + "\n",
        encoding="utf-8",
    )
    gold_sql.write_text(
        "SELECT name FROM students ORDER BY score DESC LIMIT 1",
        encoding="utf-8",
    )
    gold_csv.write_text("name\nAda\n", encoding="utf-8")
    return evaluator, gold / "spider2lite_eval.jsonl", gold_sql, gold_csv


def _manifest(
    root: Path,
    *,
    dataset: Path,
    database: Path,
    evaluator: Path,
    gold_config: Path,
    gold_sql: Path,
    gold_csv: Path,
) -> Path:
    source = (
        Path(__file__).parents[1]
        / "src"
        / "cute_evals"
        / "benchmarks"
        / "spider2"
        / "manifest.yaml"
    )
    payload = yaml.safe_load(source.read_text(encoding="utf-8"))
    payload["dataset"]["sha256"] = _sha256(dataset)
    payload["source"]["files"] = {
        "evaluation_suite/evaluate.py": {
            "path": "spider2-lite/evaluation_suite/evaluate.py",
            "sha256": _sha256(evaluator),
        },
        "evaluation_suite/evaluate_utils.py": {
            "path": "spider2-lite/evaluation_suite/evaluate_utils.py",
            "sha256": _sha256(evaluator.parent / "evaluate_utils.py"),
        },
        "evaluation_suite/gold/spider2lite_eval.jsonl": {
            "path": "spider2-lite/evaluation_suite/gold/spider2lite_eval.jsonl",
            "sha256": _sha256(gold_config),
        },
    }
    payload["taskIds"] = ["local004"]
    payload["tasks"] = {
        "local004": {
            "database": "db_one",
            "goldSql": {
                "path": "evaluation_suite/gold/sql/local004.sql",
                "sha256": _sha256(gold_sql),
            },
            "goldResults": [
                {
                    "path": "evaluation_suite/gold/exec_result/local004_a.csv",
                    "sha256": _sha256(gold_csv),
                }
            ],
        }
    }
    payload["databases"] = {"db_one": {"sha256": _sha256(database)}}
    path = root / "manifest.yaml"
    path.write_text(yaml.safe_dump(payload, sort_keys=False), encoding="utf-8")
    return path


def _fixtures(
    tmp_path: Path,
    *,
    external_knowledge: str | None = None,
) -> tuple[Spider2Adapter, dict, Path]:
    source = tmp_path / "source"
    source.mkdir()
    evaluator, gold_config, gold_sql, gold_csv = _source_files(source)
    database = tmp_path / "db_one.sqlite"
    connection = sqlite3.connect(database)
    connection.executescript(
        """
        CREATE TABLE students (id INTEGER PRIMARY KEY, name TEXT, score INTEGER);
        INSERT INTO students VALUES (1, 'Ada', 99), (2, 'Grace', 87);
        """
    )
    connection.close()
    row = {
        "instance_id": "local004",
        "db": "db_one",
        "question": "Which student has the highest score?",
        "external_knowledge": external_knowledge,
    }
    dataset = source / "spider2-lite.jsonl"
    dataset.write_text(json.dumps(row) + "\n", encoding="utf-8")
    adapter = Spider2Adapter(
        _manifest(
            tmp_path,
            dataset=dataset,
            database=database,
            evaluator=evaluator,
            gold_config=gold_config,
            gold_sql=gold_sql,
            gold_csv=gold_csv,
        ),
        data_home=tmp_path / "cache",
        source_root=source,
        database_root=tmp_path,
    )
    return adapter, row, database


def _post(endpoint: str, path: str, payload: dict) -> dict:
    request = urllib.request.Request(
        endpoint + path,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def _trace(sql: str) -> RunTrace:
    return RunTrace.from_dict(
        {
            "run": {
                "caseId": "local004",
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
                    "inputJson": json.dumps({"sql": sql}),
                    "status": "SUCCESS",
                },
            ],
            "llmInvocations": [],
            "artifacts": [],
        }
    )


def test_spider2_preserves_official_rows_and_subset(tmp_path: Path) -> None:
    adapter, row, _ = _fixtures(tmp_path)

    tasks = adapter.tasks("official-subset")

    assert [task.id for task in tasks] == ["local004"]
    assert tasks[0].question == row["question"]
    assert tasks[0].raw == row
    assert tasks[0].metadata["db_name"] == "db_one"
    assert adapter.report_metadata()["scoreLabel"] == "official-subset"
    assert adapter.report_metadata()["sourceCommit"] == (
        "cafb867313aab4e674652054198f383cf4018943"
    )


def test_spider2_official_full_fails_closed_when_assets_are_incomplete(
    tmp_path: Path,
) -> None:
    adapter, _, _ = _fixtures(tmp_path)
    dataset = adapter.ensure_dataset()
    original = dataset.read_text(encoding="utf-8")
    extra = {
        "instance_id": "cloud999",
        "db": "bigquery",
        "question": "Cloud only task",
    }
    dataset.write_text(
        original + json.dumps(extra) + "\n",
        encoding="utf-8",
    )
    adapter.manifest["dataset"]["sha256"] = _sha256(dataset)

    with pytest.raises(ValueError, match="official-full is blocked"):
        adapter.tasks("official-full")


def test_spider2_reports_missing_gold_standard(tmp_path: Path) -> None:
    adapter, _, _ = _fixtures(tmp_path)
    gold_config = (
        adapter.source_root
        / "evaluation_suite"
        / "gold"
        / "spider2lite_eval.jsonl"
    )
    gold_config.write_text("", encoding="utf-8")
    adapter.manifest["source"]["files"][
        "evaluation_suite/gold/spider2lite_eval.jsonl"
    ]["sha256"] = _sha256(gold_config)

    with pytest.raises(ValueError, match="gold standard not found"):
        adapter.tasks("official-subset")


def test_spider2_prepare_copies_snapshot_and_keeps_sql_read_only(
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
        queried = _post(
            server.endpoint,
            "/call",
            {
                "toolName": "sql_query",
                "arguments": {
                    "sql": "-- leading comment\nSELECT name FROM students ORDER BY id"
                },
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
    finally:
        server.close()


def test_spider2_score_uses_final_submit_and_official_comparator(
    tmp_path: Path,
) -> None:
    adapter, _, _ = _fixtures(tmp_path)
    task = adapter.tasks("official-subset")[0]
    prepared = adapter.prepare(task, tmp_path / "workspace")
    trace = _trace(
        textwrap.dedent(
            """
            ```sql
            SELECT name FROM students ORDER BY score DESC LIMIT 1
            ```
            """
        ).strip()
    )
    try:
        result = adapter.score(
            adapter.collect(task, trace, "trace.json", prepared=prepared)
        )
    finally:
        prepared.metadata["taskServer"].close()

    assert result.passed is True
    assert result.scores[0].name == "official_execution_accuracy"
    assert result.scores[0].passed is True
    assert result.scores[0].evidence["actualSql"] == (
        "SELECT name FROM students ORDER BY score DESC LIMIT 1"
    )
    assert result.scores[2].name == "submit_sql_contract"
    assert result.scores[2].passed is True
    assert result.scores[3].name == "sql_validity"
    assert result.scores[3].passed is True


def test_spider2_executes_submitted_sql_once(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    pytest.importorskip(
        "pandas",
        reason="Spider2 official scoring requires the optional phase4 extra",
    )
    adapter, _, _ = _fixtures(tmp_path)
    task = adapter.tasks("official-subset")[0]
    prepared = adapter.prepare(task, tmp_path / "workspace")
    calls = 0
    original_execute = adapter._execute_sql

    def counted_execute(database_path: Path, sql: str):
        nonlocal calls
        calls += 1
        return original_execute(database_path, sql)

    monkeypatch.setattr(adapter, "_execute_sql", counted_execute)
    try:
        result = adapter.score(
            adapter.collect(
                task,
                _trace("SELECT name FROM students ORDER BY score DESC LIMIT 1"),
                "trace.json",
                prepared=prepared,
            )
        )
    finally:
        prepared.metadata["taskServer"].close()

    by_name = {score.name: score for score in result.scores}
    assert calls == 1
    assert result.passed is True
    assert by_name["sql_latency"].value is not None
    assert by_name["sql_result_hash"].value is not None


def test_spider2_reports_unverified_when_submitted_sql_cannot_be_evaluated(
    tmp_path: Path,
) -> None:
    adapter, _, _ = _fixtures(tmp_path)
    task = adapter.tasks("official-subset")[0]
    prepared = adapter.prepare(task, tmp_path / "workspace")
    database = prepared.workspace / "database.sqlite"
    connection = sqlite3.connect(database)
    connection.execute("DELETE FROM students")
    connection.commit()
    connection.close()
    try:
        result = adapter.score(
            adapter.collect(
                task,
                _trace("SELECT name FROM students ORDER BY score DESC LIMIT 1"),
                "trace.json",
                prepared=prepared,
            )
        )
    finally:
        prepared.metadata["taskServer"].close()

    assert result.passed is False
    assert result.unverified is True
    assert result.scores[0].status == "unverified"
    assert "submitted SQL could not be evaluated" in result.scores[0].evidence["error"]
    assert "no submitted SQL" not in result.scores[0].evidence["error"]


def test_spider2_write_sql_cannot_receive_official_credit(
    tmp_path: Path,
) -> None:
    adapter, _, _ = _fixtures(tmp_path)
    task = adapter.tasks("official-subset")[0]
    prepared = adapter.prepare(task, tmp_path / "workspace")
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": "local004",
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


def test_spider2_rejects_tampered_database_snapshot(tmp_path: Path) -> None:
    adapter, _, database = _fixtures(tmp_path)
    task = adapter.tasks("official-subset")[0]
    connection = sqlite3.connect(database)
    connection.execute("DELETE FROM students")
    connection.commit()
    connection.close()

    with pytest.raises(ValueError, match="database db_one hash mismatch"):
        adapter.prepare(task, tmp_path / "workspace")


def test_spider2_missing_external_knowledge_is_unverified(
    tmp_path: Path,
) -> None:
    adapter, _, _ = _fixtures(tmp_path, external_knowledge="guide.md")
    task = adapter.tasks("official-subset")[0]
    prepared = adapter.prepare(task, tmp_path / "workspace")
    try:
        result = adapter.score(
            adapter.collect(
                task,
                _trace("SELECT name FROM students ORDER BY score DESC LIMIT 1"),
                "trace.json",
                prepared=prepared,
            )
        )
    finally:
        prepared.metadata["taskServer"].close()

    assert result.passed is False
    assert result.unverified is True
    assert result.scores[0].status == "unverified"
    assert "external knowledge" in result.scores[0].evidence["error"]


def test_spider2_rejects_tampered_gold_result(tmp_path: Path) -> None:
    adapter, _, _ = _fixtures(tmp_path)
    task = adapter.tasks("official-subset")[0]
    prepared = adapter.prepare(task, tmp_path / "workspace")
    gold_csv = Path(task.metadata["gold_results"][0])
    shutil.copy2(gold_csv, tmp_path / "gold.csv.original")
    gold_csv.write_text("name\nGrace\n", encoding="utf-8")
    try:
        result = adapter.score(
            adapter.collect(
                task,
                _trace("SELECT name FROM students ORDER BY score DESC LIMIT 1"),
                "trace.json",
                prepared=prepared,
            )
        )
    finally:
        prepared.metadata["taskServer"].close()
        shutil.copy2(tmp_path / "gold.csv.original", gold_csv)

    assert result.passed is False
    assert result.unverified is True
    assert "gold result" in result.scores[0].evidence["error"]
