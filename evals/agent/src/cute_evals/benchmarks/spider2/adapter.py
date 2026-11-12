from __future__ import annotations

import hashlib
import json
import os
import shutil
import time
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass, replace
from pathlib import Path
from typing import TYPE_CHECKING, Any

import yaml

from ...models import (
    BudgetSpec,
    CaseResult,
    EvalCase,
    ExpectedSpec,
    RunTrace,
    ScoreRecord,
)
from ..base import BenchmarkRun, BenchmarkTask, PreparedTask
from ..bird.server import (
    SqliteTaskServer,
    read_only_query,
    result_hash,
    validate_read_only_sql,
)

if TYPE_CHECKING:
    from .official_evaluator import Spider2OfficialEvaluator

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _invocation_arguments(item: dict[str, Any]) -> dict[str, Any]:
    raw = item.get("inputJson")
    if isinstance(raw, dict):
        return raw
    try:
        decoded = json.loads(str(raw or "{}"))
    except json.JSONDecodeError:
        return {}
    return decoded if isinstance(decoded, dict) else {}


def _verify_file(path: Path, expected: str, label: str) -> str:
    if not path.exists():
        raise FileNotFoundError(f"{label} not found: {path}")
    actual = _sha256_file(path)
    if actual != expected.lower():
        raise ValueError(
            f"{label} hash mismatch: expected {expected.lower()}, got {actual}"
        )
    return actual


def _download_bytes(url: str, *, timeout: int = 120) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github.raw+json",
            "User-Agent": "cute-evals",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def _download_file(url: str, target: Path, *, timeout: int = 300) -> None:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "cute-evals", "Accept": "*/*"},
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    try:
        with (
            urllib.request.urlopen(request, timeout=timeout) as response,
            partial.open("wb") as handle,
        ):
            shutil.copyfileobj(response, handle, length=1024 * 1024)
        partial.replace(target)
    finally:
        if partial.exists():
            partial.unlink()


@dataclass(frozen=True, slots=True)
class OfficialExecution:
    passed: bool
    verified: bool
    error: str | None = None


class Spider2Adapter:
    name = "spider2"

    def __init__(
        self,
        manifest_path: Path = DEFAULT_MANIFEST,
        *,
        data_home: Path | None = None,
        source_root: Path | None = None,
        database_root: Path | None = None,
        archive_path: Path | None = None,
    ) -> None:
        self.manifest_path = manifest_path.resolve()
        self.manifest = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(self.manifest, dict):
            raise TypeError(f"invalid Spider2 manifest: {self.manifest_path}")
        self.version = str(self.manifest["version"])
        self.upstream = str(self.manifest["upstream"])
        self.score_label = str(self.manifest["scoreLabel"])
        self.data_home = (
            data_home
            or Path(
                os.environ.get("EVAL_DATA_HOME", Path.home() / ".cache" / "cute-evals")
            )
        ).resolve()
        home = self.data_home / "spider2-lite"
        source_env = os.environ.get("CUTE_EVAL_SPIDER2_ROOT")
        database_env = os.environ.get("CUTE_EVAL_SPIDER2_DB_ROOT")
        archive_env = os.environ.get("CUTE_EVAL_SPIDER2_DB_ARCHIVE")
        self.source_root = (
            source_root
            or (Path(source_env) if source_env else home / "official")
        ).resolve()
        self.database_root = (
            database_root
            or (Path(database_env) if database_env else home / "databases")
        ).resolve()
        archive_file = str(self.manifest["databaseArchive"]["file"])
        self.archive_path = (
            archive_path
            or (Path(archive_env) if archive_env else home / archive_file)
        ).resolve()
        self._allow_source_download = source_root is None and not source_env
        self._database_root_explicit = database_root is not None or bool(database_env)
        self._tasks: dict[str, list[BenchmarkTask]] = {}
        self._active_split = "official-subset"
        self._official: Spider2OfficialEvaluator | None = None

    def _api_url(self, api_path: str) -> str:
        commit = str(self.manifest["dataset"]["commit"])
        encoded = urllib.parse.quote(api_path, safe="/")
        return (
            "https://api.github.com/repos/xlang-ai/Spider2/contents/"
            f"{encoded}?ref={commit}"
        )

    def _ensure_source_file(
        self,
        relative_path: str,
        sha256: str,
        *,
        api_path: str,
        label: str,
    ) -> Path:
        path = self.source_root / relative_path
        if path.exists():
            _verify_file(path, sha256, label)
            return path
        if not self._allow_source_download:
            raise FileNotFoundError(f"{label} not found: {path}")
        data = _download_bytes(self._api_url(api_path))
        actual = hashlib.sha256(data).hexdigest()
        if actual != sha256.lower():
            raise ValueError(
                f"{label} hash mismatch: expected {sha256.lower()}, got {actual}"
            )
        path.parent.mkdir(parents=True, exist_ok=True)
        partial = path.with_suffix(path.suffix + ".part")
        partial.write_bytes(data)
        partial.replace(path)
        return path

    def ensure_dataset(self) -> Path:
        dataset = self.manifest["dataset"]
        return self._ensure_source_file(
            str(dataset["file"]),
            str(dataset["sha256"]),
            api_path=str(dataset["apiPath"]),
            label="Spider2-Lite dataset",
        )

    def ensure_source_files(self) -> None:
        for relative_path, spec in self.manifest["source"]["files"].items():
            self._ensure_source_file(
                relative_path,
                str(spec["sha256"]),
                api_path=str(spec["path"]),
                label=f"Spider2-Lite source file {relative_path}",
            )

    def ensure_database_archive(self) -> Path:
        archive = self.manifest["databaseArchive"]
        expected = str(archive["sha256"]).lower()
        if not self.archive_path.exists():
            _download_file(str(archive["url"]), self.archive_path)
        if self.archive_path.stat().st_size != int(archive["size"]):
            raise ValueError(
                "Spider2-Lite database archive size mismatch: "
                f"expected {archive['size']}, got {self.archive_path.stat().st_size}"
            )
        _verify_file(
            self.archive_path,
            expected,
            "Spider2-Lite database archive",
        )
        return self.archive_path

    def ensure_database(self, db_name: str) -> Path:
        database = self.manifest["databases"].get(db_name)
        if database is None:
            raise ValueError(
                f"Spider2-Lite database is not pinned in manifest: {db_name}"
            )
        expected = str(database["sha256"]).lower()
        path = self.database_root / f"{db_name}.sqlite"
        if path.exists():
            _verify_file(path, expected, f"Spider2-Lite database {db_name}")
            return path
        if self._database_root_explicit:
            raise FileNotFoundError(f"Spider2-Lite database not found: {path}")

        archive = self.ensure_database_archive()
        with zipfile.ZipFile(archive) as bundle:
            try:
                member = bundle.getinfo(f"{db_name}.sqlite")
            except KeyError as exc:
                raise ValueError(
                    f"{db_name}.sqlite is missing from the official archive"
                ) from exc
            path.parent.mkdir(parents=True, exist_ok=True)
            partial = path.with_suffix(path.suffix + ".part")
            try:
                with bundle.open(member) as source, partial.open("wb") as target:
                    shutil.copyfileobj(source, target, length=1024 * 1024)
                actual = _sha256_file(partial)
                if actual != expected:
                    raise ValueError(
                        "Spider2-Lite database hash mismatch after extraction: "
                        f"expected {expected}, got {actual}"
                    )
                partial.replace(path)
            finally:
                if partial.exists():
                    partial.unlink()
        return path

    def _gold_path(self, relative_path: str, expected: str) -> Path:
        spec = self.manifest["source"]["files"].get(relative_path)
        if spec is not None:
            return self._ensure_source_file(
                relative_path,
                expected,
                api_path=str(spec["path"]),
                label=f"Spider2-Lite gold file {relative_path}",
            )
        api_path = f"{self.manifest['source']['apiRoot']}/{relative_path}"
        return self._ensure_source_file(
            relative_path,
            expected,
            api_path=api_path,
            label=f"Spider2-Lite gold file {relative_path}",
        )

    def _gold_for_task(self, task_id: str) -> tuple[str, list[Path]]:
        spec = self.manifest["tasks"][task_id]
        gold_sql_spec = spec["goldSql"]
        gold_sql_path = self._gold_path(
            str(gold_sql_spec["path"]),
            str(gold_sql_spec["sha256"]),
        )
        gold_results = []
        for result in spec["goldResults"]:
            gold_results.append(
                self._gold_path(
                    str(result["path"]),
                    str(result["sha256"]),
                )
            )
        return gold_sql_path.read_text(encoding="utf-8"), gold_results

    @staticmethod
    def _read_jsonl(path: Path) -> list[dict[str, Any]]:
        rows = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if not isinstance(row, dict):
                raise TypeError(f"Spider2-Lite row must be an object: {path}")
            rows.append(row)
        return rows

    def tasks(self, split: str) -> list[BenchmarkTask]:
        if split not in {"official-subset", "official-full"}:
            raise ValueError(f"unsupported Spider2-Lite split: {split}")
        self._active_split = split
        if split in self._tasks:
            return list(self._tasks[split])

        rows = self._read_jsonl(self.ensure_dataset())
        by_id = {str(row.get("instance_id")): row for row in rows}
        task_ids = (
            [str(value) for value in self.manifest["taskIds"]]
            if split == "official-subset"
            else [str(row.get("instance_id")) for row in rows]
        )
        if split == "official-full":
            missing = [task_id for task_id in task_ids if task_id not in self.manifest["tasks"]]
            if missing:
                preview = ", ".join(missing[:8])
                raise ValueError(
                    "Spider2 official-full is blocked because complete official "
                    f"assets are not pinned for {len(missing)} of {len(task_ids)} "
                    f"tasks (examples: {preview})"
                )
        gold_config_path = self._gold_path(
            "evaluation_suite/gold/spider2lite_eval.jsonl",
            str(
                self.manifest["source"]["files"][
                    "evaluation_suite/gold/spider2lite_eval.jsonl"
                ]["sha256"]
            ),
        )
        standards = {
            str(row.get("instance_id")): row
            for row in self._read_jsonl(gold_config_path)
        }
        tasks: list[BenchmarkTask] = []
        for task_id in task_ids:
            row = by_id.get(str(task_id))
            if row is None:
                raise ValueError(f"Spider2-Lite task not found: {task_id}")
            pin = self.manifest["tasks"][str(task_id)]
            db_name = str(row.get("db") or "")
            question = str(row.get("question") or "")
            if not db_name or not question:
                raise ValueError(f"invalid Spider2-Lite task row: {task_id}")
            if db_name != str(pin["database"]):
                raise ValueError(
                    f"Spider2-Lite database changed for {task_id}: "
                    f"expected {pin['database']}, got {db_name}"
                )
            if db_name not in self.manifest["databases"]:
                raise ValueError(
                    f"Spider2-Lite task database is not pinned: {db_name}"
                )
            standard = standards.get(str(task_id))
            if standard is None:
                raise ValueError(
                    "Spider2-Lite gold standard not found in "
                    f"{gold_config_path}: {task_id}"
                )
            gold_sql, gold_results = self._gold_for_task(str(task_id))
            tasks.append(
                BenchmarkTask(
                    id=str(task_id),
                    category=db_name,
                    question=question,
                    functions=list(self.manifest["tools"]),
                    ground_truth=[
                        {
                            "db": db_name,
                            "sql": gold_sql,
                            "resultFiles": [
                                path.name for path in gold_results
                            ],
                        }
                    ],
                    raw=row,
                    metadata={
                        "db_name": db_name,
                        "gold_sql": gold_sql,
                        "gold_results": [str(path) for path in gold_results],
                        "condition_cols": standard.get("condition_cols"),
                        "ignore_order": bool(standard.get("ignore_order", False)),
                        "external_knowledge": row.get("external_knowledge"),
                        "split": split,
                    },
                )
            )
        self._tasks[split] = tasks
        return list(tasks)

    def _materialize_external_knowledge(
        self,
        task: BenchmarkTask,
        workspace: Path,
    ) -> dict[str, Any] | None:
        name = task.metadata.get("external_knowledge")
        if not name:
            return None
        safe_name = Path(str(name)).name
        source = self.source_root / "resource" / "documents" / safe_name
        if not source.exists():
            return {
                "fileName": safe_name,
                "available": False,
                "reason": "official external knowledge document is not available",
            }
        target = workspace / safe_name
        shutil.copy2(source, target)
        return {
            "fileName": safe_name,
            "available": True,
            "path": str(target),
            "sha256": _sha256_file(target),
        }

    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask:
        workspace.mkdir(parents=True, exist_ok=True)
        db_name = str(task.metadata["db_name"])
        database = self.ensure_database(db_name)
        database_path = workspace / "database.sqlite"
        shutil.copy2(database, database_path)
        external_knowledge = self._materialize_external_knowledge(task, workspace)
        budgets = self.manifest["budgets"]
        server = SqliteTaskServer(
            database_path,
            query_timeout_seconds=float(budgets["queryTimeoutSeconds"]),
            max_result_rows=int(budgets["maxResultRows"]),
            tool_definitions=list(self.manifest["tools"]),
            runtime_name="Spider2-Lite",
        ).start()
        return PreparedTask(
            task=replace(task, functions=list(self.manifest["tools"])),
            workspace=workspace,
            metadata={
                "databasePath": str(database_path),
                "databaseSha256": _sha256_file(database_path),
                "dbName": db_name,
                "externalKnowledge": external_knowledge,
                "taskServer": server,
            },
        )

    def fixture_script(self, task: BenchmarkTask) -> dict[str, Any]:
        return {
            "id": task.id,
            "query": task.question,
            "script": [
                {
                    "type": "tool",
                    "tool": "submit_sql",
                    "arguments": {"sql": task.metadata["gold_sql"]},
                    "result": "SPIDER2_OFFICIAL_SUBSET_OK",
                },
                {
                    "type": "final",
                    "content": "Spider2-Lite fixture smoke completed.",
                },
            ],
            "tools": list(self.manifest["tools"]),
        }

    def case_for_task(self, task: BenchmarkTask) -> EvalCase:
        budgets = self.manifest["budgets"]
        split = str(task.metadata.get("split") or "official-subset")
        return EvalCase(
            id=task.id,
            suite=f"spider2-{split}",
            version=1,
            hard_gate=True,
            query=task.question,
            expected=ExpectedSpec(max_steps=int(budgets["maxSteps"])),
            budgets=BudgetSpec(
                max_total_tokens=int(budgets["maxTokens"]),
                max_wall_ms=int(budgets["maxWallMs"]),
                max_tool_calls=int(budgets["maxToolCalls"]),
            ),
            script=[
                {
                    "type": "tool",
                    "tool": "submit_sql",
                    "arguments": {"sql": task.metadata["gold_sql"]},
                    "result": "SPIDER2_OFFICIAL_SUBSET_OK",
                },
                {
                    "type": "final",
                    "content": "Spider2-Lite fixture smoke completed.",
                },
            ],
            source_path=str(self.manifest_path),
            tools=task.functions,
            answer_contract=self.manifest["answerContract"],
        )

    def collect(
        self,
        task: BenchmarkTask,
        trace: RunTrace,
        trace_path: str,
        *,
        prepared: PreparedTask | None = None,
    ) -> BenchmarkRun:
        calls = list(trace.tool_invocations)
        sql_calls = [item for item in calls if item.get("toolName") == "sql_query"]
        submit_calls = [
            item for item in calls if item.get("toolName") == "submit_sql"
        ]
        selected = submit_calls[-1] if submit_calls else None
        last_sql = (
            str(_invocation_arguments(selected).get("sql") or "").strip()
            if selected is not None
            else ""
        )
        metadata = dict(prepared.metadata) if prepared is not None else {}
        metadata["actualSql"] = last_sql or None
        metadata["sqlCallCount"] = len(sql_calls)
        metadata["submitCallCount"] = len(submit_calls)
        metadata["lastSqlStatus"] = (
            str(selected.get("status") or "UNKNOWN")
            if selected is not None
            else None
        )
        return BenchmarkRun(
            task=task,
            trace=trace,
            trace_path=trace_path,
            calls=calls,
            conversation=trace.conversation,
            environment_events=trace.environment_events,
            environment_snapshots=trace.environment_snapshots,
            metadata=metadata,
        )

    def _official_evaluator(self) -> Spider2OfficialEvaluator:
        if self._official is not None:
            return self._official
        from .official_evaluator import Spider2OfficialEvaluator

        self.ensure_source_files()
        evaluator_path = self.source_root / str(self.manifest["evaluator"]["entrypoint"])
        expected = str(
            self.manifest["source"]["files"][
                str(self.manifest["evaluator"]["entrypoint"])
            ]["sha256"]
        )
        self._official = Spider2OfficialEvaluator(evaluator_path, expected)
        return self._official

    def _execute_sql(
        self,
        database_path: Path,
        sql: str,
    ) -> tuple[Any, dict[str, Any], float]:
        import pandas as pd

        started = time.perf_counter()
        payload = read_only_query(
            database_path,
            sql,
            timeout_seconds=float(self.manifest["budgets"]["queryTimeoutSeconds"]),
            max_rows=None,
        )
        frame = pd.DataFrame(payload["rows"], columns=payload["columns"])
        return frame, payload, (time.perf_counter() - started) * 1000

    def _official_execution(
        self,
        *,
        task_id: str,
        actual_sql: str,
        gold_sql: str,
        gold_results: list[Path],
        condition_cols: Any,
        ignore_order: bool,
        external_available: bool,
        prediction: Any,
    ) -> OfficialExecution:
        if not external_available:
            return OfficialExecution(
                passed=False,
                verified=False,
                error="official external knowledge is unavailable",
            )
        try:
            evaluator = self._official_evaluator()
            actual_statement = evaluator.extract_sql(actual_sql)
            validate_read_only_sql(actual_statement)
            validate_read_only_sql(gold_sql)
            result_specs = self.manifest["tasks"][task_id]["goldResults"]
            if len(result_specs) != len(gold_results):
                raise ValueError("official gold result file count mismatch")
            for spec, path in zip(result_specs, gold_results, strict=True):
                _verify_file(
                    path,
                    str(spec["sha256"]),
                    f"Spider2-Lite gold result {path.name}",
                )
            score = evaluator.compare(
                prediction,
                gold_results,
                condition_cols=condition_cols,
                ignore_order=ignore_order,
            )
            return OfficialExecution(passed=bool(score), verified=True)
        except Exception as exc:  # noqa: BLE001 - Oracle errors are evaluation facts
            return OfficialExecution(
                passed=False,
                verified=False,
                error=f"{type(exc).__name__}: {exc}",
            )

    def score(self, run: BenchmarkRun) -> CaseResult:
        database_value = run.metadata.get("databasePath")
        database_path = Path(str(database_value)) if database_value else None
        external = run.metadata.get("externalKnowledge")
        external_available = not run.task.metadata.get(
            "external_knowledge"
        ) or bool(external and external.get("available"))

        actual_sql = run.metadata.get("actualSql")
        actual_statement = str(actual_sql or "").strip()
        evaluator_error: str | None = None
        try:
            actual_statement = self._official_evaluator().extract_sql(
                actual_statement
            )
        except Exception as exc:  # noqa: BLE001 - official status reports evaluator errors
            evaluator_error = f"{type(exc).__name__}: {exc}"
        sql_error: str | None = None
        rows_payload: dict[str, Any] | None = None
        execution_ms: float | None = None
        prediction: Any = None
        if database_path is not None:
            try:
                _verify_file(
                    database_path,
                    str(run.metadata["databaseSha256"]),
                    "Spider2-Lite workspace database",
                )
            except Exception as exc:  # noqa: BLE001 - tampering is unverified
                sql_error = f"{type(exc).__name__}: {exc}"

        if actual_statement and database_path is not None and sql_error is None:
            try:
                validate_read_only_sql(actual_statement)
                prediction, rows_payload, execution_ms = self._execute_sql(
                    database_path,
                    actual_statement,
                )
            except Exception as exc:  # noqa: BLE001 - SQL errors are evaluation facts
                sql_error = f"{type(exc).__name__}: {exc}"

        if not actual_statement:
            official_error = "no submitted SQL"
        elif sql_error or evaluator_error:
            official_error = (
                "submitted SQL could not be evaluated: "
                f"{sql_error or evaluator_error}"
            )
        else:
            official_error = "workspace database unavailable"
        official = OfficialExecution(
            passed=False,
            verified=(
                not actual_statement
                or (
                    sql_error is None
                    and evaluator_error is None
                    and database_path is not None
                )
            ),
            error=official_error,
        )
        if actual_statement and database_path is not None and sql_error is None:
            official = self._official_execution(
                task_id=run.task.id,
                actual_sql=actual_statement,
                gold_sql=str(run.task.metadata["gold_sql"]),
                gold_results=[
                    Path(path) for path in run.task.metadata["gold_results"]
                ],
                condition_cols=run.task.metadata.get("condition_cols"),
                ignore_order=bool(run.task.metadata.get("ignore_order", False)),
                external_available=external_available,
                prediction=prediction,
            )
        elif not external_available:
            official = OfficialExecution(
                passed=False,
                verified=False,
                error="official external knowledge is unavailable",
            )

        run_ok = run.trace.status == "SUCCESS"
        scores = [
            ScoreRecord(
                case_id=run.task.id,
                scorer="spider2.official",
                name="official_execution_accuracy",
                value=official.passed,
                unit="boolean",
                passed=official.passed,
                hard_gate=True,
                status="verified" if official.verified else "unverified",
                evidence={
                    "actualSql": actual_statement or None,
                    "goldSql": run.task.metadata["gold_sql"],
                    "dbName": run.task.metadata["db_name"],
                    "evaluator": "evaluation_suite/evaluate.py:compare_pandas_table",
                    "conditionCols": run.task.metadata.get("condition_cols"),
                    "ignoreOrder": run.task.metadata.get("ignore_order"),
                    "goldResultFiles": [
                        Path(path).name
                        for path in run.task.metadata["gold_results"]
                    ],
                    "externalKnowledgeAvailable": external_available,
                    "error": official.error,
                    "databaseSha256": run.metadata.get("databaseSha256"),
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="spider2.runtime",
                name="agent_run_success",
                value=run_ok,
                unit="boolean",
                passed=run_ok,
                hard_gate=True,
                status="verified",
                evidence={"runStatus": run.trace.status},
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="spider2.submission",
                name="submit_sql_contract",
                value=bool(run.metadata.get("submitCallCount")),
                unit="boolean",
                passed=bool(run.metadata.get("submitCallCount")),
                hard_gate=True,
                status="verified",
                evidence={
                    "answerContract": self.manifest["answerContract"]["version"],
                    "submitCallCount": int(
                        run.metadata.get("submitCallCount") or 0
                    ),
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="spider2.sql",
                name="sql_validity",
                value=bool(actual_statement) and sql_error is None,
                unit="boolean",
                passed=bool(actual_statement) and sql_error is None,
                hard_gate=False,
                status="verified",
                evidence={
                    "error": sql_error,
                    "officialEvaluatorError": evaluator_error,
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="spider2.sql",
                name="sql_call_count",
                value=int(run.metadata.get("sqlCallCount") or 0),
                unit="count",
                passed=None,
                hard_gate=False,
                status="verified",
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="spider2.sql",
                name="sql_latency",
                value=execution_ms,
                unit="ms",
                passed=None,
                hard_gate=False,
                status="verified" if execution_ms is not None else "unverified",
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="spider2.sql",
                name="sql_result_hash",
                value=result_hash(rows_payload) if rows_payload is not None else None,
                unit="hash",
                passed=None,
                hard_gate=False,
                status="verified" if rows_payload is not None else "unverified",
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="spider2.metrics",
                name="tool_success_rate",
                value=run.trace.tool_success_rate(),
                unit="ratio",
                passed=None,
                hard_gate=False,
                status=(
                    "verified"
                    if run.trace.tool_success_rate() is not None
                    else "not_applicable"
                ),
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="spider2.metrics",
                name="total_tokens",
                value=run.trace.total_tokens,
                unit="tokens",
                passed=None,
                hard_gate=False,
                status=(
                    "verified" if run.trace.total_tokens is not None else "unverified"
                ),
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="spider2.metrics",
                name="wall_time",
                value=run.trace.duration_ms,
                unit="ms",
                passed=None,
                hard_gate=False,
                status=(
                    "verified"
                    if run.trace.duration_ms is not None
                    else "unverified"
                ),
            ),
        ]
        return CaseResult(
            case=self.case_for_task(run.task),
            trace=run.trace,
            scores=scores,
            trace_path=run.trace_path,
        )

    def dataset_hash(self) -> str:
        payload = {
            "name": self.name,
            "version": self.version,
            "dataset": self.manifest["dataset"],
            "source": self.manifest["source"],
            "evaluator": self.manifest["evaluator"],
            "split": self._active_split,
            "taskIds": [task.id for task in self.tasks(self._active_split)],
            "tasks": self.manifest["tasks"],
            "databases": self.manifest["databases"],
            "tools": self.manifest["tools"],
            "answerContract": self.manifest["answerContract"],
        }
        encoded = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        return f"sha256:{hashlib.sha256(encoded).hexdigest()}"

    def report_metadata(self) -> dict[str, Any]:
        split = self._active_split
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "license": self.manifest["license"],
            "split": split,
            "scoreLabel": split,
            "officialEvaluator": True,
            "evaluator": self.manifest["evaluator"],
            "runnerMode": self.manifest["runnerMode"],
            "answerContract": self.manifest["answerContract"],
            "sourceCommit": self.manifest["dataset"]["commit"],
            "taskIds": [task.id for task in self.tasks(split)],
            "databaseSnapshots": self.manifest["databases"],
            "datasetHash": self.dataset_hash(),
        }
