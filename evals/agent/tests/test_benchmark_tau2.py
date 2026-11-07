from __future__ import annotations

import json
from pathlib import Path

import pytest

from cute_evals.benchmarks.base import BenchmarkTask
from cute_evals.benchmarks.tau2.adapter import Tau2Adapter, Tau2UserSimulator


class FakeTaskServer:
    def __init__(self) -> None:
        self.reset_ids: list[str] = []

    def reset(self, task_id: str) -> dict:
        self.reset_ids.append(task_id)
        return {"taskId": task_id}

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
