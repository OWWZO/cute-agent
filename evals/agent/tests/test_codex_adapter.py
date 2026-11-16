from __future__ import annotations

import json
import subprocess
from pathlib import Path

import cute_evals.compare.codex_adapter as codex_adapter
from cute_evals.compare.codex_app_server import AppServerRun
from cute_evals.compare.codex_adapter import (
    CodexOptions,
    parse_codex_events,
    run_codex_case,
)
from cute_evals.models import BudgetSpec, EvalCase, ExpectedSpec


def _case() -> EvalCase:
    return EvalCase(
        id="compare.codex.fixture",
        suite="compare-pilot",
        version=1,
        hard_gate=True,
        query="Say DONE",
        expected=ExpectedSpec(final_contains=["DONE"]),
        budgets=BudgetSpec(max_wall_ms=10000, max_total_tokens=1000),
        script=[],
        source_path="pilot.json",
    )


def test_parse_codex_events_normalizes_messages_tools_and_usage() -> None:
    lines = [
        json.dumps({"type": "thread.started", "thread_id": "thread-1"}),
        json.dumps(
            {
                "type": "item.completed",
                "item": {
                    "id": "item-1",
                    "type": "command_execution",
                    "command": "Get-ChildItem",
                    "aggregated_output": "ok",
                    "exit_code": 0,
                    "status": "completed",
                },
            }
        ),
        json.dumps(
            {
                "type": "item.completed",
                "item": {
                    "id": "item-2",
                    "type": "file_change",
                    "changes": [{"path": "output/result.md"}],
                    "status": "completed",
                },
            }
        ),
        json.dumps(
            {
                "type": "item.completed",
                "item": {
                    "id": "item-3",
                    "type": "agent_message",
                    "text": "DONE",
                },
            }
        ),
        json.dumps(
            {
                "type": "turn.completed",
                "usage": {
                    "input_tokens": 12,
                    "cached_input_tokens": 4,
                    "output_tokens": 3,
                },
            }
        ),
    ]

    parsed = parse_codex_events(lines)

    assert parsed["threadId"] == "thread-1"
    assert parsed["finalText"] == "DONE"
    assert parsed["usage"]["input_tokens"] == 12
    assert [item["toolName"] for item in parsed["tools"]] == [
        "shell",
        "file_edit",
    ]
    assert parsed["artifacts"][0]["path"] == "output/result.md"
    assert parsed["conversation"][0]["content"] == "DONE"
    assert parsed["parseErrors"] == []


def test_parse_codex_events_preserves_mcp_tool_identity_and_arguments() -> None:
    parsed = parse_codex_events(
        [
            json.dumps(
                {
                    "type": "item.completed",
                    "item": {
                        "id": "item-mcp",
                        "type": "mcp_tool_call",
                        "server": "tau2",
                        "tool": "get_user_details",
                        "arguments": {"user_id": "user-1"},
                        "result": {
                            "content": [{"type": "text", "text": "Ada"}],
                            "isError": False,
                        },
                        "status": "completed",
                    },
                }
            )
        ]
    )

    assert parsed["tools"] == [
        {
            "toolCallId": "item-mcp",
            "toolName": "get_user_details",
            "inputJson": '{"user_id": "user-1"}',
            "toolResult": "Ada",
            "status": "SUCCESS",
            "errorMsg": None,
            "durationMs": None,
            "artifactCount": None,
        }
    ]


def test_run_codex_case_uses_jsonl_and_last_message(
    tmp_path: Path,
    monkeypatch,
) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()

    def fake_run(command, **kwargs):
        output_path = Path(command[command.index("-o") + 1])
        output_path.write_text("DONE\n", encoding="utf-8")
        stdout = "\n".join(
            [
                json.dumps({"type": "thread.started", "thread_id": "thread-1"}),
                json.dumps(
                    {
                        "type": "item.completed",
                        "item": {
                            "id": "item-1",
                            "type": "agent_message",
                            "text": "DONE",
                        },
                    }
                ),
                json.dumps(
                    {
                        "type": "turn.completed",
                        "usage": {"input_tokens": 10, "output_tokens": 2},
                    }
                ),
            ]
        )
        return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr="")

    monkeypatch.setattr(
        "cute_evals.compare.codex_adapter.subprocess.run",
        fake_run,
    )
    trace = run_codex_case(
        _case(),
        workspace,
        tmp_path / "case",
        options=CodexOptions(command="codex"),
        timeout_seconds=30,
        environment={
            "agent": "codex-cli",
            "agentVersion": "0.166.0",
            "workspace": str(workspace),
            "workspaceHash": "sha256:test",
            "datasetHash": "sha256:dataset",
            "mode": "codex-cli",
        },
    )

    assert trace.status == "SUCCESS"
    assert trace.final_text == "DONE"
    assert trace.total_tokens == 12
    assert trace.tool_names() == []
    assert (tmp_path / "case" / "codex-events.jsonl").exists()


def test_run_codex_case_enforces_case_budget_after_correct_answer(
    tmp_path: Path,
    monkeypatch,
) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()

    def fake_run(command, **kwargs):
        output_path = Path(command[command.index("-o") + 1])
        output_path.write_text("DONE\n", encoding="utf-8")
        stdout = "\n".join(
            [
                json.dumps(
                    {
                        "type": "turn.completed",
                        "usage": {"input_tokens": 1000, "output_tokens": 1},
                    }
                )
            ]
        )
        return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr="")

    monkeypatch.setattr(
        "cute_evals.compare.codex_adapter.subprocess.run",
        fake_run,
    )
    trace = run_codex_case(
        _case(),
        workspace,
        tmp_path / "case",
        options=CodexOptions(command="codex"),
        timeout_seconds=30,
        environment={"agent": "codex-cli"},
    )

    assert trace.final_text == "DONE"
    assert trace.status == "FAILED"
    assert trace.raw["run"]["errorCode"] == "EVAL_BUDGET_EXCEEDED"
    assert trace.raw["run"]["errorMsg"] == (
        "evaluation budget exceeded: total_tokens"
    )


def test_run_codex_case_resumes_with_mcp_config(
    tmp_path: Path,
    monkeypatch,
) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    commands: list[list[str]] = []

    def fake_run(command, **kwargs):
        commands.append(command)
        output_path = Path(command[command.index("-o") + 1])
        output_path.write_text("DONE\n", encoding="utf-8")
        return subprocess.CompletedProcess(
            command,
            0,
            stdout=json.dumps(
                {
                    "type": "turn.completed",
                    "usage": {"input_tokens": 10, "output_tokens": 2},
                }
            ),
            stderr="",
        )

    monkeypatch.setattr(
        "cute_evals.compare.codex_adapter.subprocess.run",
        fake_run,
    )
    run_codex_case(
        _case(),
        workspace,
        tmp_path / "case",
        options=CodexOptions(command="codex"),
        timeout_seconds=30,
        environment={"agent": "codex-cli"},
        config_overrides=(
            'mcp_servers.tau2.command="python"',
            'mcp_servers.tau2.args=["mcp_server.py"]',
        ),
        resume_session="thread-1",
        ephemeral=False,
    )

    command = commands[0]
    assert command[command.index("resume") + 1] == "thread-1"
    assert "--ephemeral" not in command
    assert "--color" not in command
    assert command[command.index("-c") + 1] == (
        'mcp_servers.tau2.command="python"'
    )


def test_run_codex_case_accepts_turn_completed_before_process_timeout(
    tmp_path: Path,
    monkeypatch,
) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()

    def fake_run(command, **kwargs):
        output_path = Path(command[command.index("-o") + 1])
        output_path.write_text("DONE\n", encoding="utf-8")
        stdout = "\n".join(
            [
                json.dumps({"type": "thread.started", "thread_id": "thread-1"}),
                json.dumps(
                    {
                        "type": "item.completed",
                        "item": {
                            "id": "item-1",
                            "type": "agent_message",
                            "text": "DONE",
                        },
                    }
                ),
                json.dumps(
                    {
                        "type": "turn.completed",
                        "usage": {"input_tokens": 10, "output_tokens": 2},
                    }
                ),
            ]
        )
        raise subprocess.TimeoutExpired(
            command,
            kwargs["timeout"],
            output=stdout,
            stderr="",
        )

    monkeypatch.setattr(
        "cute_evals.compare.codex_adapter.subprocess.run",
        fake_run,
    )
    trace = run_codex_case(
        _case(),
        workspace,
        tmp_path / "case",
        options=CodexOptions(command="codex"),
        timeout_seconds=30,
        environment={
            "agent": "codex-cli",
            "agentVersion": "0.166.0",
            "workspace": str(workspace),
            "workspaceHash": "sha256:test",
            "datasetHash": "sha256:dataset",
            "mode": "codex-cli",
        },
    )

    assert trace.status == "SUCCESS"
    assert trace.final_text == "DONE"


def test_run_codex_case_app_server_records_soft_finalization(
    tmp_path: Path,
    monkeypatch,
) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    captured: dict[str, object] = {}

    def fake_app_server(**kwargs):
        captured.update(kwargs)
        return AppServerRun(
            status="SUCCESS",
            duration_ms=1234,
            final_text="DONE",
            thread_id="thread-app",
            usage={
                "totalTokens": 321,
                "inputTokens": 300,
                "outputTokens": 21,
                "cachedInputTokens": 100,
            },
            cumulative_usage={
                "totalTokens": 321,
                "inputTokens": 300,
                "outputTokens": 21,
                "cachedInputTokens": 100,
            },
            events=[
                {
                    "method": "item/completed",
                    "params": {
                        "item": {
                            "id": "msg-1",
                            "type": "agentMessage",
                            "phase": "final_answer",
                            "text": "DONE",
                        }
                    },
                }
            ],
            stdout="",
            stderr="",
            return_code=0,
            error_message=None,
            soft_finalization={
                "enabled": True,
                "triggered": True,
                "reason": "token_reserve",
                "interruptSent": True,
                "finalizationStarted": True,
                "tokensAtTrigger": 250,
            },
        )

    monkeypatch.setattr(
        codex_adapter,
        "run_app_server_case",
        fake_app_server,
    )

    trace = run_codex_case(
        _case(),
        workspace,
        tmp_path / "case",
        options=CodexOptions(command="codex"),
        timeout_seconds=30,
        environment={"agent": "codex-cli"},
        app_server=True,
        soft_budget_finalize=True,
    )

    assert trace.status == "SUCCESS"
    assert trace.final_text == "DONE"
    assert trace.total_tokens == 321
    assert captured["soft_budget_finalize"] is True
    adapter = trace.raw["environment"]["adapter"]
    assert adapter["kind"] == "codex-app-server"
    assert adapter["softBudgetFinalization"]["reason"] == "token_reserve"
    assert trace.raw["raw"]["appServerEvents"]
