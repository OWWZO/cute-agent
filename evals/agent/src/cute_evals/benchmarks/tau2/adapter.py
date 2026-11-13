from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeoutError
from dataclasses import dataclass, field, replace
from pathlib import Path
from threading import Thread
from typing import Any, Self

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

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _json_hash(payload: Any) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return f"sha256:{_sha256_bytes(encoded)}"


def _request(url: str, payload: dict[str, Any] | None = None) -> Any:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST" if payload is not None else "GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")
        try:
            payload = json.loads(detail)
            message = (
                str(payload.get("error") or "")
                if isinstance(payload, dict)
                else ""
            )
        except json.JSONDecodeError:
            message = detail
        raise RuntimeError(
            message or f"tau2 task server returned HTTP {exc.code}"
        ) from exc
    if not body.get("ok"):
        raise RuntimeError(body.get("error") or "tau2 task server failed")
    return body.get("result")


class Tau2TaskServer:
    def __init__(
        self,
        source_root: Path,
        *,
        domain: str,
        python: str | None = None,
        startup_timeout: int = 60,
    ) -> None:
        self.source_root = source_root
        self.domain = domain
        self.python = python or os.environ.get("CUTE_EVAL_TAU2_PYTHON") or sys.executable
        self.startup_timeout = startup_timeout
        self.process: subprocess.Popen[str] | None = None
        self._stderr_thread: Thread | None = None
        self._stderr_tail: list[str] = []
        self.port: int | None = None

    @property
    def endpoint(self) -> str:
        if self.port is None:
            raise RuntimeError("tau2 task server is not started")
        return f"http://127.0.0.1:{self.port}"

    def start(self) -> Tau2TaskServer:
        command = [
            self.python,
            str(PACKAGE_ROOT / "server.py"),
            "--source",
            str(self.source_root),
            "--domain",
            self.domain,
        ]
        self.process = subprocess.Popen(
            command,
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self._stderr_thread = Thread(
            target=self._drain_stderr,
            name="cute-evals-tau2-stderr",
            daemon=True,
        )
        self._stderr_thread.start()
        assert self.process.stdout is not None
        pool = ThreadPoolExecutor(max_workers=1)
        try:
            future = pool.submit(self.process.stdout.readline)
            try:
                line = future.result(timeout=self.startup_timeout)
            except FutureTimeoutError:
                line = ""
            if line:
                try:
                    payload = json.loads(line)
                    self.port = int(payload["port"])
                except (TypeError, KeyError, ValueError) as exc:
                    raise RuntimeError(
                        "tau2 task server returned an invalid startup line: "
                        f"{line!r}: {self._stderr_text()}"
                    ) from exc
                return self
            raise RuntimeError(
                f"tau2 task server did not start: {self._stderr_text()}"
            )
        except BaseException:
            self.close()
            raise
        finally:
            # Never join a worker that may still be blocked in readline().
            pool.shutdown(wait=False, cancel_futures=True)

    def reset(self, task_id: str) -> dict[str, Any]:
        return _request(self.endpoint + "/reset", {"taskId": task_id})

    def tools(self) -> list[dict[str, Any]]:
        return _request(self.endpoint + "/tools", {})

    def call(self, tool_name: str, arguments: dict[str, Any]) -> Any:
        return _request(
            self.endpoint + "/call",
            {"toolName": tool_name, "arguments": arguments},
        )

    def snapshot(self) -> dict[str, Any]:
        return _request(self.endpoint + "/snapshot", {})

    def evaluate(self, payload: dict[str, Any]) -> dict[str, Any]:
        return _request(self.endpoint + "/evaluate", payload)

    def start_user(
        self,
        *,
        model: str,
        llm_args: dict[str, Any],
    ) -> dict[str, Any]:
        return _request(
            self.endpoint + "/user/start",
            {"model": model, "llmArgs": llm_args},
        )

    def next_user(self, assistant_message: str) -> dict[str, Any]:
        return _request(
            self.endpoint + "/user/next",
            {"assistantMessage": assistant_message},
        )

    def close(self) -> None:
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
        if self.process is not None and self.process.stdout is not None:
            self.process.stdout.close()
        if self._stderr_thread is not None:
            self._stderr_thread.join(timeout=2)
        if self.process is not None and self.process.stderr is not None:
            self.process.stderr.close()
        self.process = None
        self._stderr_thread = None
        self.port = None

    def _drain_stderr(self) -> None:
        if self.process is None or self.process.stderr is None:
            return
        stream = self.process.stderr
        while True:
            chunk = stream.read(4096)
            if not chunk:
                return
            self._stderr_tail.append(chunk)
            if sum(len(item) for item in self._stderr_tail) > 64_000:
                self._stderr_tail = self._stderr_tail[-8:]

    def _stderr_text(self) -> str:
        return "".join(self._stderr_tail)[-4000:]

    def __enter__(self) -> Self:
        return self.start()

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()


@dataclass(slots=True)
class Tau2UserSimulator:
    """Use tau²'s official user simulator through the task-server process."""

    server: Tau2TaskServer
    model: str
    base_url: str
    api_key: str
    temperature: float = 0.0
    errors: list[str] = field(default_factory=list)
    _termination_reason: str = "in_progress"
    _done: bool = False

    def start(
        self,
        task: BenchmarkTask,
        environment: Any | None = None,
    ) -> str | None:
        del task, environment
        self.errors.clear()
        self._done = False
        try:
            return self._consume(
                self.server.start_user(
                    model=self.model,
                    llm_args={
                        "api_base": self.base_url,
                        "api_key": self.api_key,
                        "temperature": self.temperature,
                        "custom_llm_provider": "openai",
                    },
                )
            )
        except Exception as exc:  # noqa: BLE001 - external simulator boundary
            return self._fail(exc)

    def respond(
        self,
        *,
        task: BenchmarkTask,
        history: list[dict[str, Any]],
        assistant_message: str,
        environment: Any | None = None,
    ) -> str | None:
        del task, history, environment
        if self._done:
            return None
        try:
            return self._consume(self.server.next_user(assistant_message))
        except Exception as exc:  # noqa: BLE001 - external simulator boundary
            return self._fail(exc)

    def error(self) -> str | None:
        return self.errors[-1] if self.errors else None

    def termination_reason(self) -> str:
        return self._termination_reason

    def _consume(self, payload: dict[str, Any]) -> str | None:
        message = str(payload.get("message") or "").strip()
        self._done = bool(payload.get("done"))
        self._termination_reason = str(payload.get("reason") or "in_progress")
        if self._done:
            return None
        if not message:
            raise RuntimeError("tau2 user simulator returned an empty message")
        return message

    def _fail(self, exc: Exception) -> None:
        self.errors.append(str(exc))
        self._termination_reason = "user_simulator_error"
        self._done = True


class Tau2Adapter:
    name = "tau2"

    def __init__(
        self,
        manifest_path: Path = DEFAULT_MANIFEST,
        *,
        data_home: Path | None = None,
        source_root: Path | None = None,
        python: str | None = None,
    ) -> None:
        self.manifest_path = manifest_path.resolve()
        self.manifest = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(self.manifest, dict):
            raise TypeError(f"invalid tau2 manifest: {self.manifest_path}")
        self.version = str(self.manifest["version"])
        self.upstream = str(self.manifest["upstream"])
        self.score_label = str(self.manifest["scoreLabel"])
        self.domain = str(self.manifest["domain"])
        self.domains = list(self.manifest.get("domains") or [self.domain])
        self.data_home = (
            data_home
            or Path(
                os.environ.get("EVAL_DATA_HOME", Path.home() / ".cache" / "cute-evals")
            )
        ).resolve()
        self.source_root = (
            source_root.resolve()
            if source_root is not None
            else self.data_home / "tau2" / self.version / "source"
        )
        self.python = python
        self._tasks: dict[str, list[BenchmarkTask]] = {}
        self._rows_by_split: dict[str, list[dict[str, Any]]] = {}
        self._active_split = "official-subset"
        self._active_rows: list[dict[str, Any]] = []

    def ensure_source(self) -> Path:
        if (self.source_root / "src" / "tau2").is_dir():
            return self.source_root
        source = self.manifest["source"]
        expected = str(source["sha256"])
        if expected == "pending":
            raise RuntimeError(
                "tau2 source SHA-256 is not pinned; provide an extracted "
                f"official source at {self.source_root}"
            )
        archive = self.source_root.parent / "source.tar.gz"
        archive.parent.mkdir(parents=True, exist_ok=True)
        if not archive.exists():
            data = download_official_archive(source["url"])
            actual = _sha256_bytes(data)
            if actual != expected:
                raise ValueError(
                    f"tau2 archive hash mismatch: expected {expected}, got {actual}"
                )
            archive.write_bytes(data)
        else:
            actual = hashlib.sha256(archive.read_bytes()).hexdigest()
            if actual != expected:
                raise ValueError(
                    f"cached tau2 archive hash mismatch: "
                    f"expected {expected}, got {actual}"
                )
        with tarfile.open(archive, "r:gz") as bundle:
            root = bundle.getmembers()[0].name.split("/", 1)[0]
            with tempfile.TemporaryDirectory(dir=archive.parent) as tmp:
                bundle.extractall(tmp, filter="data")
                extracted = Path(tmp) / root
                if not (extracted / "src" / "tau2").is_dir():
                    raise ValueError("tau2 archive has no src/tau2 directory")
                self.source_root.parent.mkdir(parents=True, exist_ok=True)
                self._copy_tree(extracted, self.source_root)
        return self.source_root

    @staticmethod
    def _copy_tree(source: Path, target: Path) -> None:
        import shutil

        shutil.copytree(source, target, dirs_exist_ok=True)

    def _official_tasks(self, domain: str) -> list[dict[str, Any]]:
        source = self.ensure_source()
        path = source / "data" / "tau2" / "domains" / domain / "tasks.json"
        return json.loads(path.read_text(encoding="utf-8"))

    def _selected_tasks(
        self,
        split: str,
    ) -> list[tuple[str, dict[str, Any]]]:
        selected: list[tuple[str, dict[str, Any]]] = []
        domains = [self.domain] if split == "official-subset" else self.domains
        for domain in domains:
            rows = self._official_tasks(domain)
            by_id = {str(row.get("id")): row for row in rows}
            task_ids = (
                [str(value) for value in self.manifest["taskIds"]]
                if split == "official-subset"
                else sorted(by_id, key=lambda value: int(value) if value.isdigit() else value)
            )
            for task_id in task_ids:
                row = by_id.get(str(task_id))
                if row is None:
                    raise ValueError(f"tau2 task not found: {domain}:{task_id}")
                selected.append((domain, row))
        return selected

    @staticmethod
    def _task_identity(domain: str, task_id: str, split: str) -> str:
        return task_id if split == "official-subset" else f"{domain}:{task_id}"

    @staticmethod
    def _instructions(row: dict[str, Any]) -> str:
        scenario = row.get("user_scenario") or {}
        persona = scenario.get("persona")
        instructions = scenario.get("instructions") or {}
        if isinstance(instructions, str):
            body = instructions
        else:
            lines = []
            for key in ("reason_for_call", "known_info", "unknown_info", "task_instructions"):
                value = instructions.get(key)
                if value:
                    lines.append(f"{key.replace('_', ' ').title()}:\n{value}")
            body = "\n\n".join(lines)
        return f"Persona:\n{persona}\n\n{body}" if persona else body

    def tasks(self, split: str) -> list[BenchmarkTask]:
        if split not in {"official-subset", "official-full"}:
            raise ValueError(f"unsupported tau2 split: {split}")
        self._active_split = split
        if split in self._tasks:
            self._active_rows = list(self._rows_by_split[split])
            return list(self._tasks[split])
        tasks = []
        active_rows = []
        for domain, row in self._selected_tasks(split):
            instructions = self._instructions(row)
            official_task_id = str(row["id"])
            task_id = self._task_identity(domain, official_task_id, split)
            active_rows.append(
                {
                    "domain": domain,
                    "taskId": official_task_id,
                    "task": row,
                }
            )
            tasks.append(
                BenchmarkTask(
                    id=task_id,
                    category=domain,
                    question=instructions,
                    functions=[],
                    ground_truth=None,
                    raw=row,
                    turns=[],
                    metadata={
                        "instructions": instructions,
                        "domain": domain,
                        "officialTaskId": official_task_id,
                        "split": split,
                    },
                )
            )
        self._tasks[split] = tasks
        self._rows_by_split[split] = active_rows
        self._active_rows = list(active_rows)
        return list(tasks)

    def task_server(
        self,
        task: BenchmarkTask | None = None,
        *,
        domain: str | None = None,
    ) -> Tau2TaskServer:
        selected_domain = domain or (
            str(task.metadata["domain"]) if task is not None else self.domain
        )
        return Tau2TaskServer(
            self.ensure_source(),
            domain=selected_domain,
            python=self.python,
        )

    def prepare(
        self,
        task: BenchmarkTask,
        workspace: Path,
        *,
        server: Tau2TaskServer | None = None,
    ) -> PreparedTask:
        workspace.mkdir(parents=True, exist_ok=True)
        owned = server is None
        server = server or self.task_server(task).start()
        environment = server.reset(str(task.metadata["officialTaskId"]))
        tools = server.tools()
        prepared_task = replace(
            task,
            functions=tools,
            metadata={**task.metadata, "taskEnvironment": environment},
        )
        return PreparedTask(
            task=prepared_task,
            workspace=workspace,
            metadata={
                "taskServer": server,
                "ownedServer": owned,
                "taskEnvironment": environment,
            },
        )

    def fixture_script(self, task: BenchmarkTask) -> dict[str, Any]:
        return {
            "id": task.id,
            "query": task.question,
            "script": [{"type": "final", "content": "TAU2_FIXTURE_SMOKE"}],
            "tools": task.functions,
        }

    def case_for_task(self, task: BenchmarkTask) -> EvalCase:
        budgets = self.manifest["budgets"]
        split = str(task.metadata.get("split") or "official-subset")
        policy = (task.metadata.get("taskEnvironment") or {}).get("policy")
        return EvalCase(
            id=task.id,
            suite=f"tau2-{split}",
            version=1,
            hard_gate=True,
            query=task.question,
            expected=ExpectedSpec(max_steps=int(budgets["maxSteps"])),
            budgets=BudgetSpec(
                max_total_tokens=int(budgets["maxTokens"]),
                max_wall_ms=int(budgets["maxWallMs"]),
            ),
            script=[{"type": "final", "content": "TAU2_FIXTURE_SMOKE"}],
            source_path=str(self.manifest_path),
            tools=task.functions,
            answer_contract=(
                {
                    "instruction": (
                        policy
                        if isinstance(policy, str)
                        else json.dumps(policy, ensure_ascii=False, sort_keys=True)
                    )
                }
                if policy
                else None
            ),
        )

    def evaluate(self, server: Tau2TaskServer, trace: RunTrace) -> dict[str, Any]:
        return server.evaluate(trace.raw)

    def collect(
        self,
        task: BenchmarkTask,
        trace: RunTrace,
        trace_path: str,
        *,
        oracle: dict[str, Any] | None = None,
        prepared: PreparedTask | None = None,
    ) -> BenchmarkRun:
        prepared_metadata = prepared.metadata if prepared else {}
        server = prepared_metadata.get("taskServer")
        try:
            return BenchmarkRun(
                task=task,
                trace=trace,
                trace_path=trace_path,
                calls=trace.tool_invocations,
                conversation=trace.conversation,
                environment_events=trace.environment_events,
                environment_snapshots=trace.environment_snapshots,
                oracle=oracle or {},
                metadata={"taskServer": server},
            )
        finally:
            if prepared_metadata.get("ownedServer") and server is not None:
                server.close()

    def score(self, run: BenchmarkRun) -> CaseResult:
        reward = float(run.oracle.get("reward") or 0.0)
        verified = bool(run.oracle) and not run.oracle.get("error")
        termination_reason = str(
            (run.trace.raw.get("run") or {}).get("terminationReason") or ""
        )
        simulator_ok = termination_reason != "user_simulator_error"
        scores = [
            ScoreRecord(
                case_id=run.task.id,
                scorer="tau2.official",
                name="official_reward",
                value=reward,
                unit="ratio",
                passed=reward == 1.0,
                hard_gate=True,
                status="verified" if verified and simulator_ok else "unverified",
                evidence=run.oracle,
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="tau2.user_simulator",
                name="user_simulator_status",
                value=simulator_ok,
                unit="boolean",
                passed=simulator_ok,
                hard_gate=False,
                status="verified",
                evidence={"terminationReason": termination_reason},
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="tau2.metrics",
                name="turn_count",
                value=run.trace.turn_count(),
                unit="turns",
                passed=None,
                hard_gate=False,
                status="verified",
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="tau2.metrics",
                name="db_terminal_state_hash",
                value=run.oracle.get("db_check"),
                unit="object",
                passed=None,
                hard_gate=False,
                status="verified" if verified else "unverified",
            ),
        ]
        return CaseResult(
            case=self.case_for_task(run.task),
            trace=run.trace,
            scores=scores,
            trace_path=run.trace_path,
        )

    def dataset_hash(self) -> str:
        return _json_hash(
            {
                "name": self.name,
                "version": self.version,
                "split": self._active_split,
                "domains": (
                    [self.domain]
                    if self._active_split == "official-subset"
                    else self.domains
                ),
                "tasks": self._active_rows,
            }
        )

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
            "userSimulator": "tau2.user.UserSimulator",
            "runnerMode": self.manifest["runnerMode"],
            "domains": (
                [self.domain] if split == "official-subset" else self.domains
            ),
            "taskIds": [task.id for task in self.tasks(split)],
            "sourceSha256": self.manifest["source"]["sha256"],
            "datasetHash": self.dataset_hash(),
        }
