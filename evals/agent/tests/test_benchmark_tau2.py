from __future__ import annotations

import io
import json
import threading
import time
import urllib.error
from pathlib import Path

import pytest

from cute_evals.benchmarks.base import BenchmarkTask, PreparedTask
from cute_evals.benchmarks.tau2 import adapter as tau2_adapter
from cute_evals.benchmarks.tau2.adapter import Tau2Adapter, Tau2UserSimulator
from cute_evals.benchmarks.tau2.adapter import Tau2TaskServer
from cute_evals.benchmarks.tau2 import mcp_server as tau2_mcp
from cute_evals.models import RunTrace


class FakeTaskServer:
    def __init__(self, policy: str = "Test policy") -> None:
        self.reset_ids: list[str] = []
        self.policy = policy

    def reset(self, task_id: str) -> dict:
        self.reset_ids.append(task_id)
        return {
            "taskId": task_id,
            "domain": "airline",
            "dbHash": "db-hash",
            "policy": self.policy,
        }

    def tools(self) -> list[dict]:
        return [{"name": "get_user_details", "parameters": {"type": "object"}}]


class FakeUserServer:
    def __init__(self) -> None:
        self.start_args: dict | None = None
        self.assistant_messages: list[str] = []

    def start_user(self, **kwargs) -> dict:
        self.start_args = kwargs
        return {
            "message": "I want to cancel reservation EHGLP3.",
            "done": False,
            "reason": "in_progress",
        }

    def next_user(self, assistant_message: str) -> dict:
        self.assistant_messages.append(assistant_message)
        return {"message": "", "done": True, "reason": "user_stop"}


class BlockingStdout:
    def __init__(self) -> None:
        self.release = threading.Event()

    def readline(self) -> str:
        self.release.wait()
        return ""

    def close(self) -> None:
        self.release.set()


class EmptyStderr:
    def read(self, size: int) -> str:
        del size
        return ""

    def close(self) -> None:
        return None


class FakeProcess:
    def __init__(self, *, line: str | None = None) -> None:
        self.stdout = BlockingStdout() if line is None else io.StringIO(line)
        self.stderr = EmptyStderr()
        self.terminated = False

    def poll(self) -> int | None:
        return 0 if self.terminated else None

    def terminate(self) -> None:
        self.terminated = True
        self.stdout.close()

    def wait(self, timeout: int) -> int:
        del timeout
        return 0

    def kill(self) -> None:
        self.terminate()


class FakeOwnedServer:
    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        self.closed = True


def test_tau2_loads_official_task_instructions_without_mutation(tmp_path: Path) -> None:
    source = tmp_path / "source"
    (source / "src" / "tau2").mkdir(parents=True)
    domain = source / "data" / "tau2" / "domains" / "airline"
    domain.mkdir(parents=True)
    raw_task = {
        "id": "0",
        "user_scenario": {
            "persona": None,
            "instructions": {
                "domain": "airline",
                "reason_for_call": "Cancel reservation ABC123.",
                "known_info": "You are Test User.",
                "unknown_info": None,
                "task_instructions": "Do not accept the cancellation without a refund.",
            },
        },
        "evaluation_criteria": {"reward_basis": ["DB", "COMMUNICATE"]},
    }
    (domain / "tasks.json").write_text(json.dumps([raw_task]), encoding="utf-8")
    manifest = tmp_path / "manifest.yaml"
    manifest.write_text(
        (Path(__file__).parents[1] / "src" / "cute_evals" / "benchmarks" / "tau2" / "manifest.yaml")
        .read_text(encoding="utf-8")
        .replace('  - "1"\n  - "3"\n', ""),
        encoding="utf-8",
    )
    adapter = Tau2Adapter(
        manifest,
        source_root=source,
        data_home=tmp_path / "cache",
    )

    task = adapter.tasks("official-subset")[0]

    assert task.id == "0"
    assert "Cancel reservation ABC123." in task.question
    assert task.raw == raw_task
    metadata = adapter.report_metadata()
    assert metadata["scoreLabel"] == "official-subset"
    assert metadata["userSimulator"] == "tau2.user.UserSimulator"


def test_tau2_official_full_selects_all_domains(tmp_path: Path) -> None:
    source = tmp_path / "source"
    (source / "src" / "tau2").mkdir(parents=True)
    for domain, task_id in (("airline", "0"), ("retail", "10")):
        target = source / "data" / "tau2" / "domains" / domain
        target.mkdir(parents=True)
        (target / "tasks.json").write_text(
            json.dumps(
                [
                    {
                        "id": task_id,
                        "user_scenario": {
                            "instructions": {"reason_for_call": f"{domain} task"}
                        },
                    }
                ]
            ),
            encoding="utf-8",
        )
    manifest = tmp_path / "manifest.yaml"
    manifest.write_text(
        (Path(__file__).parents[1] / "src" / "cute_evals" / "benchmarks" / "tau2" / "manifest.yaml")
        .read_text(encoding="utf-8")
        .replace(
            "domains:\n  - airline\n  - banking_knowledge\n  - retail\n  - telecom\n",
            "domains:\n  - airline\n  - retail\n",
        ),
        encoding="utf-8",
    )
    adapter = Tau2Adapter(manifest, source_root=source)

    tasks = adapter.tasks("official-full")

    assert [task.id for task in tasks] == ["airline:0", "retail:10"]
    assert [task.metadata["domain"] for task in tasks] == ["airline", "retail"]
    assert adapter.report_metadata()["scoreLabel"] == "official-full"

    adapter.manifest["taskIds"] = ["0"]
    adapter.tasks("official-subset")
    fresh = Tau2Adapter(manifest, source_root=source)
    fresh.manifest["taskIds"] = ["0"]
    fresh.tasks("official-subset")
    assert adapter.dataset_hash() == fresh.dataset_hash()


def test_tau2_prepare_resets_official_environment_before_every_task(
    tmp_path: Path,
) -> None:
    source = tmp_path / "source"
    (source / "src" / "tau2").mkdir(parents=True)
    domain = source / "data" / "tau2" / "domains" / "airline"
    domain.mkdir(parents=True)
    (domain / "tasks.json").write_text(
        json.dumps(
            [
                {
                    "id": "0",
                    "user_scenario": {"instructions": {"reason_for_call": "Test"}},
                }
            ]
        ),
        encoding="utf-8",
    )
    manifest = tmp_path / "manifest.yaml"
    manifest.write_text(
        (Path(__file__).parents[1] / "src" / "cute_evals" / "benchmarks" / "tau2" / "manifest.yaml")
        .read_text(encoding="utf-8")
        .replace('  - "1"\n  - "3"\n', ""),
        encoding="utf-8",
    )
    adapter = Tau2Adapter(manifest, source_root=source)
    task = adapter.tasks("official-subset")[0]
    server = FakeTaskServer()

    prepared = adapter.prepare(task, tmp_path / "workspace", server=server)

    assert server.reset_ids == ["0"]
    assert prepared.task.functions[0]["name"] == "get_user_details"


def test_tau2_prepare_carries_environment_policy_into_case_prompt(
    tmp_path: Path,
) -> None:
    adapter = Tau2Adapter()
    task = BenchmarkTask(
        id="airline:0",
        category="airline",
        question="Cancel reservation EHGLP3.",
        functions=[],
        ground_truth=None,
        raw={},
        metadata={
            "domain": "airline",
            "officialTaskId": "0",
            "split": "official-subset",
        },
    )
    policy = "Never reveal another passenger's reservation."
    server = FakeTaskServer(policy=policy)

    prepared = adapter.prepare(task, tmp_path / "workspace", server=server)
    case = adapter.case_for_task(prepared.task)

    assert prepared.metadata["taskEnvironment"]["dbHash"] == "db-hash"
    assert case.answer_contract == {"instruction": policy}


def test_tau2_rejects_cached_archive_with_wrong_hash(tmp_path: Path) -> None:
    data_home = tmp_path / "cache"
    cache_dir = data_home / "tau2" / "v1.0.1"
    cache_dir.mkdir(parents=True)
    (cache_dir / "source.tar.gz").write_bytes(b"not-the-official-archive")
    adapter = Tau2Adapter(
        Path(__file__).parents[1]
        / "src"
        / "cute_evals"
        / "benchmarks"
        / "tau2"
        / "manifest.yaml",
        data_home=data_home,
    )

    with pytest.raises(ValueError, match="cached tau2 archive hash mismatch"):
        adapter.ensure_source()


def test_tau2_user_simulator_uses_official_task_server_protocol() -> None:
    server = FakeUserServer()
    simulator = Tau2UserSimulator(
        server=server,
        model="group/deepseek",
        base_url="http://127.0.0.1:3425/v1",
        api_key="local-key",
    )
    task = BenchmarkTask(
        id="0",
        category="airline",
        question="Cancel reservation EHGLP3.",
        functions=[],
        ground_truth=None,
        raw={},
    )

    initial = simulator.start(task)
    next_message = simulator.respond(
        task=task,
        history=[],
        assistant_message="I cannot process this cancellation.",
    )

    assert initial == "I want to cancel reservation EHGLP3."
    assert next_message is None
    assert simulator.error() is None
    assert simulator.termination_reason() == "user_stop"
    assert server.start_args == {
        "model": "group/deepseek",
        "llm_args": {
            "api_base": "http://127.0.0.1:3425/v1",
            "api_key": "local-key",
            "temperature": 0.0,
            "custom_llm_provider": "openai",
        },
    }
    assert server.assistant_messages == ["I cannot process this cancellation."]


def test_tau2_request_surfaces_http_error_payload(monkeypatch) -> None:
    def fake_urlopen(request, timeout):
        del request, timeout
        raise urllib.error.HTTPError(
            "http://127.0.0.1:1/reset",
            500,
            "Internal Server Error",
            None,
            io.BytesIO(b'{"ok": false, "error": "tau2 exploded"}'),
        )

    monkeypatch.setattr(tau2_adapter.urllib.request, "urlopen", fake_urlopen)

    with pytest.raises(RuntimeError, match="tau2 exploded"):
        tau2_adapter._request("http://127.0.0.1:1/reset", {})


def test_tau2_startup_timeout_closes_silent_process(
    tmp_path: Path,
    monkeypatch,
) -> None:
    process = FakeProcess()
    monkeypatch.setattr(
        tau2_adapter.subprocess,
        "Popen",
        lambda *args, **kwargs: process,
    )
    server = Tau2TaskServer(
        tmp_path,
        domain="airline",
        startup_timeout=0.1,
    )

    started = time.monotonic()
    with pytest.raises(RuntimeError, match="did not start"):
        server.start()

    assert time.monotonic() - started < 3
    assert process.terminated is True
    assert server.process is None


def test_tau2_invalid_startup_line_closes_process(
    tmp_path: Path,
    monkeypatch,
) -> None:
    process = FakeProcess(line="library warning\n")
    monkeypatch.setattr(
        tau2_adapter.subprocess,
        "Popen",
        lambda *args, **kwargs: process,
    )
    server = Tau2TaskServer(tmp_path, domain="airline")

    with pytest.raises(RuntimeError, match="invalid startup line"):
        server.start()

    assert process.terminated is True
    assert server.process is None


def test_tau2_collect_closes_owned_server(tmp_path: Path) -> None:
    adapter = Tau2Adapter()
    task = BenchmarkTask(
        id="0",
        category="airline",
        question="Cancel reservation EHGLP3.",
        functions=[],
        ground_truth=None,
        raw={},
    )
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": "0",
                "requestId": "request-0",
                "status": "SUCCESS",
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
        }
    )
    server = FakeOwnedServer()
    prepared = PreparedTask(
        task=task,
        workspace=tmp_path,
        metadata={"taskServer": server, "ownedServer": True},
    )

    adapter.collect(task, trace, "trace.json", prepared=prepared)

    assert server.closed is True


def test_tau2_mcp_server_exposes_official_tools_and_forwards_calls(
    monkeypatch,
) -> None:
    calls: list[tuple[str, str, dict]] = []

    def fake_request(endpoint: str, path: str, payload: dict):
        calls.append((endpoint, path, payload))
        if path == "/tools":
            return [
                {
                    "name": "get_user_details",
                    "description": "Look up a user",
                    "parameters": {
                        "type": "object",
                        "properties": {"user_id": {"type": "string"}},
                    },
                }
            ]
        return {"user_id": payload["arguments"]["user_id"]}

    monkeypatch.setattr(tau2_mcp, "_request", fake_request)
    server = tau2_mcp.Tau2McpServer("http://127.0.0.1:1234")

    listed = server.handle(
        {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
    )
    called = server.handle(
        {
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": {
                "name": "get_user_details",
                "arguments": {"user_id": "user-1"},
            },
        }
    )

    assert listed["result"]["tools"] == [
        {
            "name": "get_user_details",
            "description": "Look up a user",
            "inputSchema": {
                "type": "object",
                "properties": {"user_id": {"type": "string"}},
            },
        }
    ]
    assert called["result"]["isError"] is False
    assert json.loads(called["result"]["content"][0]["text"]) == {
        "user_id": "user-1"
    }
    assert calls[-1] == (
        "http://127.0.0.1:1234",
        "/call",
        {
            "toolName": "get_user_details",
            "arguments": {"user_id": "user-1"},
        },
    )
