from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from cute_evals.benchmarks.compactbench.adapter import CompactBenchAdapter


def test_manifest_pins_compactbench_v3_protocol() -> None:
    adapter = CompactBenchAdapter()

    assert adapter.version == "0.3.0"
    assert adapter.suite_version == "2.0.1"
    assert adapter.score_label == "official-public"
    assert adapter.method_spec.endswith("java_method.py:CuteAgentCompactor")


def test_command_uses_official_runner_without_mutating_suite(
    tmp_path: Path,
    monkeypatch,
) -> None:
    adapter = CompactBenchAdapter()
    monkeypatch.setattr(adapter, "require_official_package", lambda: None)

    command = adapter.command(
        suite="practice",
        output_path=tmp_path / "results.jsonl",
        traces_path=tmp_path / "traces.jsonl",
        budget=2048,
        cases=4,
        drift_cycles=2,
        controls=True,
        reader_provider="openai",
        reader_model="group/deepseek",
    )

    assert command[:4] == [
        command[0],
        "-m",
        "compactbench",
        "run",
    ]
    assert "--suite" in command
    assert command[command.index("--suite") + 1] == "practice"
    assert command[command.index("--method") + 1].endswith(
        ":CuteAgentCompactor"
    )
    assert "--controls" in command
    assert command[command.index("--reader-provider") + 1] == "openai"
    assert command[command.index("--reader-model") + 1] == "group/deepseek"


def test_command_can_use_pinned_source_without_installing_package(
    tmp_path: Path,
) -> None:
    adapter = CompactBenchAdapter()

    command = adapter.command(
        source=tmp_path / "compactbench-source",
        suite="starter",
        output_path=tmp_path / "results.jsonl",
    )

    assert command[:2] == ["uv", "run"]
    assert command[command.index("--with") + 1] == str(
        (tmp_path / "compactbench-source").resolve()
    )
    assert command[command.index("--output") + 1] == str(
        tmp_path / "results.jsonl"
    )


def test_java_method_bridges_turns_and_previous_artifact(monkeypatch) -> None:
    compactbench = pytest.importorskip("compactbench")
    contracts = pytest.importorskip("compactbench.contracts")
    java_method = pytest.importorskip(
        "cute_evals.benchmarks.compactbench.java_method"
    )

    request = contracts.CompactionRequest(
        cycle=1,
        turns=[
            contracts.Turn(
                id=10,
                role=contracts.TurnRole.TOOL,
                name="read_file",
                content="status=green",
            ),
            contracts.Turn(
                id=11,
                role=contracts.TurnRole.USER,
                content="continue",
            ),
        ],
        previous_artifact=contracts.CompactionArtifact(
            summaryText="## Goal\nship it",
        ),
        token_budget=512,
    )
    payload = java_method.build_payload(request)

    assert payload["force"] is True
    assert payload["messages"][0]["content"].startswith(
        "[CONTEXT COMPACTION"
    )
    assert "ship it" in payload["messages"][0]["content"]
    assert payload["messages"][1]["content"].startswith("[tool:read_file]")
    assert payload["messages"][2] == {
        "role": "user",
        "content": "continue",
    }

    monkeypatch.setattr(
        java_method,
        "run_java_compaction",
        lambda _payload, **_kwargs: {
            "compactionTriggered": True,
            "status": "success",
            "strategy": "full-llm",
            "summaryText": "## Goal\nship it\n## Constraints & Preferences\nnone"
            "\n## Active State\ncontinue",
            "summaryHash": "sha256:test",
            "eventId": "compaction:1",
            "beforeTokens": 1000,
            "afterTokens": 100,
        },
    )
    artifact = asyncio.run(java_method.CuteAgentCompactor(None, "unused").compact(request))

    assert artifact.summary_text.startswith("## Goal")
    assert artifact.method_metadata["strategy"] == "full-llm"
    assert artifact.method_metadata["beforeTokens"] == 1000
    assert compactbench.__version__ == "0.3.0"
