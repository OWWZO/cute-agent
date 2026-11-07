from __future__ import annotations

import hashlib
import importlib
import io
import json
import os
import shutil
import sys
import tarfile
import tempfile
import time
import urllib.request
from dataclasses import dataclass, replace
from pathlib import Path
from types import ModuleType
from typing import Any

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
from ..source import download_official_archive
from .server import (
    SqliteTaskServer,
    read_only_query,
    result_hash,
    validate_read_only_sql,
)

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_file(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _invocation_arguments(item: dict[str, Any]) -> dict[str, Any]:
    raw = item.get("inputJson")
    if isinstance(raw, dict):
        return raw
    try:
        decoded = json.loads(str(raw or "{}"))
    except json.JSONDecodeError:
        return {}
    return decoded if isinstance(decoded, dict) else {}


def _download(
    url: str,
    *,
    headers: dict[str, str] | None = None,
    timeout: int = 180,
    attempts: int = 5,
) -> bytes:
    last_error: Exception | None = None
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(
                url,
                headers={
                    "User-Agent": "cute-evals",
                    "Accept": "*/*",
                    **(headers or {}),
                },
            )
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read()
        except Exception as exc:  # noqa: BLE001 - retry transient registry failures
            last_error = exc
            time.sleep(2 + attempt * 2)
    assert last_error is not None
    raise last_error


@dataclass(frozen=True, slots=True)
class OfficialExecution:
    passed: bool
    verified: bool
    error: str | None = None


class BirdAdapter:
    name = "bird"

    def __init__(
        self,
        manifest_path: Path = DEFAULT_MANIFEST,
        *,
        data_home: Path | None = None,
        dataset_path: Path | None = None,
        source_root: Path | None = None,
        database_root: Path | None = None,
    ) -> None:
        self.manifest_path = manifest_path.resolve()
        self.manifest = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(self.manifest, dict):
            raise TypeError(f"invalid BIRD manifest: {self.manifest_path}")
        self.version = str(self.manifest["version"])
        self.upstream = str(self.manifest["upstream"])
        self.score_label = str(self.manifest["scoreLabel"])
        revision = str(self.manifest["dataset"]["revision"])
        self.data_home = (
            data_home
            or Path(
                os.environ.get("EVAL_DATA_HOME", Path.home() / ".cache" / "cute-evals")
            )
        ).resolve()
        self.cache_dir = self.data_home / "bird" / revision
        self.dataset_path = dataset_path
        self.source_root = source_root
        self.database_root = (
            database_root
            or (
                Path(os.environ["CUTE_EVAL_BIRD_DB_ROOT"])
                if os.environ.get("CUTE_EVAL_BIRD_DB_ROOT")
                else None
            )
        )
        self._tasks: dict[str, list[BenchmarkTask]] = {}
        self._active_split = "official-subset"
        self._official: tuple[ModuleType, ModuleType] | None = None

    def ensure_dataset(self) -> Path:
        path = self.dataset_path or (
            self.cache_dir / Path(self.manifest["dataset"]["file"]).name
        )
        expected = str(self.manifest["dataset"]["sha256"]).lower()
        if path.exists():
            actual = _sha256_file(path)
            if actual != expected:
                raise ValueError(
                    f"BIRD dataset hash mismatch: expected {expected}, got {actual}"
                )
            return path
        if self.dataset_path is not None:
            raise FileNotFoundError(f"BIRD dataset not found: {path}")
        path.parent.mkdir(parents=True, exist_ok=True)
        data = _download(str(self.manifest["dataset"]["url"]))
        actual = _sha256_bytes(data)
        if actual != expected:
            raise ValueError(
                f"BIRD dataset hash mismatch: expected {expected}, got {actual}"
            )
        partial = path.with_suffix(path.suffix + ".part")
        partial.write_bytes(data)
        partial.replace(path)
        return path

    def ensure_source(self) -> Path:
        source = (
            self.source_root.resolve()
            if self.source_root is not None
            else self.cache_dir / "source"
        )
        if source.exists():
            self._verify_evaluator_files(source)
            return source
        if self.source_root is not None:
            raise FileNotFoundError(f"BIRD source not found: {source}")

        archive = self.cache_dir / "source.tar.gz"
        expected = str(self.manifest["source"]["sha256"]).lower()
        if archive.exists():
            actual = _sha256_file(archive)
            if actual != expected:
                raise ValueError(
                    f"cached BIRD source hash mismatch: expected {expected}, got {actual}"
                )
        else:
            data = download_official_archive(str(self.manifest["source"]["url"]))
            actual = _sha256_bytes(data)
            if actual != expected:
                raise ValueError(
                    f"BIRD source hash mismatch: expected {expected}, got {actual}"
                )
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            archive.write_bytes(data)

        with tempfile.TemporaryDirectory(dir=archive.parent) as tmp:
            with tarfile.open(archive, "r:gz") as bundle:
                bundle.extractall(tmp, filter="data")
            roots = [item for item in Path(tmp).iterdir() if item.is_dir()]
            if len(roots) != 1:
                raise ValueError("BIRD source archive must contain exactly one root")
            shutil.copytree(roots[0], source)
        self._verify_evaluator_files(source)
        return source

    def _verify_evaluator_files(self, source: Path) -> None:
        for relative, expected in self.manifest["evaluator"]["files"].items():
            path = source / relative
            if not path.exists():
                raise FileNotFoundError(f"BIRD evaluator file not found: {path}")
            actual = _sha256_file(path)
            if actual != str(expected).lower():
                raise ValueError(
                    f"BIRD evaluator hash mismatch: {relative}; "
                    f"expected {expected}, got {actual}"
                )

    def ensure_seed(self, db_id: str) -> Path:
        database = self.manifest["databases"].get(db_id)
        path = (
            self.database_root / f"{db_id}.sqlite"
            if self.database_root is not None
            else self.cache_dir / "databases" / f"{db_id}.sqlite"
        )
        expected = (
            str(database["seedSha256"]).lower()
            if database is not None
            else None
        )
        if path.exists():
            actual = _sha256_file(path)
            if expected is not None and actual != expected:
                raise ValueError(
                    f"BIRD database snapshot hash mismatch: {db_id}; "
                    f"expected {expected}, got {actual}"
                )
            if expected is None:
                if self.database_root is None:
                    raise ValueError(
                        f"BIRD database is not pinned in manifest: {db_id}"
                    )
                self._record_local_database_hash(db_id, actual)
            return path
        if self.database_root is not None:
            raise FileNotFoundError(f"BIRD database snapshot not found: {path}")
        if database is None:
            raise ValueError(f"BIRD database is not pinned in manifest: {db_id}")

        registry = self.manifest["registry"]
        repo = str(registry["repo"])
        token = json.loads(
            _download(
                "https://auth.docker.io/token"
                f"?service=registry.docker.io&scope=repository:{repo}:pull",
                timeout=60,
            )
        )["token"]
        layer_digest = str(database["seedLayer"])
        blob = _download(
            f"https://registry-1.docker.io/v2/{repo}/blobs/{layer_digest}",
            headers={"Authorization": f"Bearer {token}"},
        )
        actual_layer = "sha256:" + _sha256_bytes(blob)
        if actual_layer != layer_digest:
            raise ValueError(
                f"BIRD image layer hash mismatch: expected {layer_digest}, "
                f"got {actual_layer}"
            )
        with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as bundle:
            member = next(
                (
                    item
                    for item in bundle.getmembers()
                    if item.isfile() and item.name.endswith("seed.sqlite")
                ),
                None,
            )
            if member is None:
                raise ValueError(f"BIRD seed.sqlite missing from layer: {db_id}")
            stream = bundle.extractfile(member)
            if stream is None:
                raise ValueError(f"cannot read BIRD seed.sqlite: {db_id}")
            data = stream.read()
        actual_seed = _sha256_bytes(data)
        if actual_seed != expected:
            raise ValueError(
                f"BIRD seed.sqlite hash mismatch: {db_id}; "
                f"expected {expected}, got {actual_seed}"
            )
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def _record_local_database_hash(self, db_id: str, sha256: str) -> None:
        if self.database_root is None:
            return
        lock_path = self.database_root / "database-lock.json"
        lock = {}
        if lock_path.exists():
            lock = json.loads(lock_path.read_text(encoding="utf-8"))
            if not isinstance(lock, dict):
                raise TypeError(f"invalid BIRD database lock: {lock_path}")
        current = lock.get(db_id)
        if current is not None and current != sha256:
            raise ValueError(
                f"BIRD local database lock mismatch for {db_id}: "
                f"expected {current}, got {sha256}"
            )
        if current is None:
            lock[db_id] = sha256
            lock_path.write_text(
                json.dumps(lock, indent=2, sort_keys=True),
                encoding="utf-8",
            )

    def _dataset_rows(self) -> list[dict[str, Any]]:
        rows = []
        for line in self.ensure_dataset().read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError("BIRD dataset row must be an object")
                rows.append(row)
        return rows

    def tasks(self, split: str) -> list[BenchmarkTask]:
        if split not in {"official-subset", "official-full"}:
            raise ValueError(f"unsupported BIRD split: {split}")
        self._active_split = split
        if split in self._tasks:
            return list(self._tasks[split])
        rows = self._dataset_rows()
        by_id = {str(row.get("question_id")): row for row in rows}
        tasks = []
        task_ids = (
            [str(value) for value in self.manifest["taskIds"]]
            if split == "official-subset"
            else sorted(by_id, key=lambda value: int(value) if value.isdigit() else value)
        )
        for task_id in task_ids:
            row = by_id.get(str(task_id))
            if row is None:
                raise ValueError(f"BIRD task not found: {task_id}")
            question = str(row.get("question") or "")
            db_id = str(row.get("db_id") or "")
            gold_sql = str(row.get("SQL") or "")
            if not question or not db_id or not gold_sql:
                raise ValueError(f"invalid BIRD task row: {task_id}")
            tasks.append(
                BenchmarkTask(
                    id=str(task_id),
                    category=str(row.get("difficulty") or "unknown"),
                    question=question,
                    functions=list(self.manifest["tools"]),
                    ground_truth=[{"db_id": db_id, "sql": gold_sql}],
                    raw=row,
                    metadata={
                        "db_id": db_id,
                        "difficulty": str(row.get("difficulty") or "unknown"),
                        "evidence": str(row.get("evidence") or ""),
                        "gold_sql": gold_sql,
                        "split": split,
                    },
                )
            )
        self._tasks[split] = tasks
        return list(tasks)

    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask:
        workspace.mkdir(parents=True, exist_ok=True)
        db_id = str(task.metadata["db_id"])
        seed = self.ensure_seed(db_id)
        database_path = workspace / "database.sqlite"
        shutil.copy2(seed, database_path)
        budgets = self.manifest["budgets"]
        database_metadata = self.manifest["databases"].get(db_id) or {}
        server = SqliteTaskServer(
            database_path,
            query_timeout_seconds=float(budgets["queryTimeoutSeconds"]),
            max_result_rows=int(budgets["maxResultRows"]),
            tool_definitions=list(self.manifest["tools"]),
        ).start()
        return PreparedTask(
            task=replace(task, functions=list(self.manifest["tools"])),
            workspace=workspace,
            metadata={
                "databasePath": str(database_path),
                "databaseSha256": _sha256_file(database_path),
                "dbId": db_id,
                "imageDigest": database_metadata.get("imageDigest"),
                "seedLayer": database_metadata.get("seedLayer"),
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
                    "result": "BIRD_OFFICIAL_SUBSET_OK",
                },
                {"type": "final", "content": "BIRD fixture smoke completed."},
            ],
            "tools": list(self.manifest["tools"]),
        }

    def case_for_task(self, task: BenchmarkTask) -> EvalCase:
        budgets = self.manifest["budgets"]
        split = str(task.metadata.get("split") or "official-subset")
        return EvalCase(
            id=task.id,
            suite=f"bird-{split}",
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
                    "result": "BIRD_OFFICIAL_SUBSET_OK",
                },
                {"type": "final", "content": "BIRD fixture smoke completed."},
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
        submit_calls = [item for item in calls if item.get("toolName") == "submit_sql"]
        submitted = bool(submit_calls)
        selected = submit_calls[-1] if submit_calls else (sql_calls[-1] if sql_calls else None)
        last_sql = (
            str(_invocation_arguments(selected).get("sql") or "").strip()
            if selected is not None
            else ""
        )
        metadata = dict(prepared.metadata) if prepared is not None else {}
        metadata["actualSql"] = last_sql or None
        metadata["sqlCallCount"] = len(sql_calls)
        metadata["submitCallCount"] = len(submit_calls)
        metadata["submissionSource"] = (
            "submit_sql" if submitted else ("sql_query_fallback" if sql_calls else None)
        )
        metadata["lastSqlStatus"] = (
            str(selected.get("status") or "UNKNOWN") if selected is not None else None
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

    def _official_evaluator(self) -> tuple[ModuleType, ModuleType]:
        if self._official is not None:
            return self._official
        source = self.ensure_source()
        evaluation_dir = source / "evaluation"
        sys.path.insert(0, str(evaluation_dir))
        importlib.invalidate_caches()
        try:
            utils = importlib.import_module("evaluation_utils")
            evaluator = importlib.import_module("evaluation_ex")
        finally:
            if sys.path and sys.path[0] == str(evaluation_dir):
                sys.path.pop(0)
        self._official = (evaluator, utils)
        return self._official

    def _official_execution(
        self,
        *,
        actual_sql: str,
        gold_sql: str,
        database_path: Path,
    ) -> OfficialExecution:
        try:
            evaluator, utils = self._official_evaluator()
        except Exception as exc:  # noqa: BLE001 - optional evaluator dependency boundary
            return OfficialExecution(
                passed=False,
                verified=False,
                error=f"{type(exc).__name__}: {exc}",
            )
        try:
            value = evaluator.func_timeout(
                float(self.manifest["budgets"]["queryTimeoutSeconds"]),
                utils.execute_sql,
                args=(
                    actual_sql,
                    gold_sql,
                    str(database_path),
                    "SQLite",
                    evaluator.calculate_ex,
                ),
            )
            return OfficialExecution(passed=bool(value), verified=True)
        except evaluator.FunctionTimedOut:
            return OfficialExecution(
                passed=False,
                verified=True,
                error="official BIRD evaluator timed out",
            )
        except Exception as exc:  # noqa: BLE001 - official EX treats SQL errors as zero
            return OfficialExecution(
                passed=False,
                verified=True,
                error=f"{type(exc).__name__}: {exc}",
            )

    def score(self, run: BenchmarkRun) -> CaseResult:
        actual_sql = run.metadata.get("actualSql")
        gold_sql = str(run.task.metadata["gold_sql"])
        database_value = run.metadata.get("databasePath")
        database_path = Path(str(database_value)) if database_value else None
        sql_error: str | None = None
        database_error: str | None = None
        rows_payload: dict[str, Any] | None = None
        execution_ms: float | None = None
        expected_database_sha = run.metadata.get("databaseSha256")
        if database_path is not None:
            try:
                actual_database_sha = _sha256_file(database_path)
            except OSError:
                actual_database_sha = None
            if (
                not expected_database_sha
                or actual_database_sha != str(expected_database_sha)
            ):
                database_error = "workspace database hash mismatch"
                sql_error = database_error
        if actual_sql and database_path is not None and sql_error is None:
            try:
                validate_read_only_sql(str(actual_sql))
                started = time.perf_counter()
                rows_payload = read_only_query(
                    database_path,
                    str(actual_sql),
                    timeout_seconds=float(
                        self.manifest["budgets"]["queryTimeoutSeconds"]
                    ),
                    max_rows=None,
                )
                execution_ms = (time.perf_counter() - started) * 1000
            except Exception as exc:  # noqa: BLE001 - SQL errors are evaluation facts
                sql_error = f"{type(exc).__name__}: {exc}"

        official = OfficialExecution(
            passed=False,
            verified=database_error is None,
            error=database_error or "no valid sql_query execution",
        )
        if actual_sql and database_path is not None and sql_error is None:
            try:
                validate_read_only_sql(gold_sql)
            except Exception as exc:  # noqa: BLE001 - official data boundary
                official = OfficialExecution(
                    passed=False,
                    verified=False,
                    error=f"official gold SQL is not read-only: {exc}",
                )
            else:
                official = self._official_execution(
                    actual_sql=str(actual_sql),
                    gold_sql=gold_sql,
                    database_path=database_path,
                )

        run_ok = run.trace.status == "SUCCESS"
        scores = [
            ScoreRecord(
                case_id=run.task.id,
                scorer="bird.official",
                name="official_execution_accuracy",
                value=official.passed,
                unit="boolean",
                passed=official.passed,
                hard_gate=True,
                status="verified" if official.verified else "unverified",
                evidence={
                    "actualSql": actual_sql,
                    "goldSql": gold_sql,
                    "dbId": run.task.metadata["db_id"],
                    "evaluator": "evaluation/evaluation_ex.py:calculate_ex",
                    "submissionSource": run.metadata.get("submissionSource"),
                    "error": official.error,
                    "databaseSha256": run.metadata.get("databaseSha256"),
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="bird.runtime",
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
                scorer="bird.submission",
                name="submit_sql_contract",
                value=bool(run.metadata.get("submitCallCount")),
                unit="boolean",
                passed=bool(run.metadata.get("submitCallCount")),
                hard_gate=True,
                status="verified",
                evidence={
                    "answerContract": self.manifest["answerContract"]["version"],
                    "submitCallCount": int(run.metadata.get("submitCallCount") or 0),
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="bird.sql",
                name="sql_validity",
                value=bool(actual_sql) and sql_error is None,
                unit="boolean",
                passed=bool(actual_sql) and sql_error is None,
                hard_gate=False,
                status="verified",
                evidence={"error": sql_error},
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="bird.sql",
                name="sql_call_count",
                value=int(run.metadata.get("sqlCallCount") or 0),
                unit="count",
                passed=None,
                hard_gate=False,
                status="verified",
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="bird.sql",
                name="sql_latency",
                value=execution_ms,
                unit="ms",
                passed=None,
                hard_gate=False,
                status="verified" if execution_ms is not None else "unverified",
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="bird.sql",
                name="sql_result_hash",
                value=result_hash(rows_payload) if rows_payload is not None else None,
                unit="hash",
                passed=None,
                hard_gate=False,
                status="verified" if rows_payload is not None else "unverified",
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="bird.metrics",
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
                scorer="bird.metrics",
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
                scorer="bird.metrics",
                name="wall_time",
                value=run.trace.duration_ms,
                unit="ms",
                passed=None,
                hard_gate=False,
                status=(
                    "verified" if run.trace.duration_ms is not None else "unverified"
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
            "datasetSha256": self.manifest["dataset"]["sha256"],
            "sourceSha256": self.manifest["source"]["sha256"],
            "split": self._active_split,
            "taskIds": [task.id for task in self.tasks(self._active_split)],
            "evaluatorFiles": self.manifest["evaluator"]["files"],
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
        return f"sha256:{_sha256_bytes(encoded)}"

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
            "datasetRevision": self.manifest["dataset"]["revision"],
            "sourceCommit": self.manifest["source"]["commit"],
            "taskIds": [task.id for task in self.tasks(split)],
            "databaseSnapshots": self.manifest["databases"],
            "datasetHash": self.dataset_hash(),
        }
