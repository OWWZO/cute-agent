from __future__ import annotations

import json
import os
from pathlib import Path
from types import SimpleNamespace

import jsonschema
import pytest
from fastapi.testclient import TestClient

from cute_evals.semantic_judge.config import SemanticJudgeConfig
from cute_evals.task_review.facts import build_fast_report
import cute_evals.task_review.judge as task_review_judge
from cute_evals.task_review.judge import run_task_review_judge
from cute_evals.task_review.jobs import TaskReviewJobManager
from cute_evals.task_review.models import TaskReviewSettings
from cute_evals.task_review.storage import ReviewStore
from cute_evals.trajectory_quality import _tool_failure_signal
from cute_evals.service.app import create_app


def _trace() -> dict:
    return {
        "schemaVersion": "1.1",
        "producer": {"agent": "cute-agent"},
        "run": {
            "requestId": "req-1",
            "sessionId": "session-1",
            "status": "SUCCESS",
            "queryText": "删除临时文件",
            "finalSummaryText": "已处理",
            "totalTokens": 120,
            "durationMs": 3400,
            "startedAt": "2026-11-15T10:00:00",
            "finishedAt": "2026-11-15T10:00:03",
        },
        "conversation": [
            {
                "messageId": "m-user",
                "role": "user",
                "content": "删除临时文件",
            },
            {
                "messageId": "m-assistant",
                "role": "assistant",
                "content": "我先检查一下。",
            },
        ],
        "llmInvocations": [
            {
                "id": 1,
                "stepNo": 1,
                "status": "SUCCESS",
                "totalTokens": 120,
                "promptTokens": 80,
                "completionTokens": 40,
                "cachedPromptTokens": 0,
                "startedAt": "2026-11-15T10:00:00",
                "firstTokenAt": "2026-11-15T10:00:00.500",
                "finishedAt": "2026-11-15T10:00:01",
            }
        ],
        "toolInvocations": [
            {
                "toolCallId": "tool-1",
                "toolName": "shell",
                "inputJson": "{\"command\":\"rm tmp/a\",\"path\":\"tmp/a\"}",
                "toolResult": "permission denied",
                "status": "FAILED",
                "errorMsg": "permission denied",
                "durationMs": 100,
            },
            {
                "toolCallId": "tool-2",
                "toolName": "shell",
                "inputJson": "{\"path\":\"tmp/a\",\"command\":\"rm tmp/a\"}",
                "toolResult": "permission denied",
                "status": "FAILED",
                "errorMsg": "permission denied",
                "durationMs": 100,
            },
        ],
        "artifacts": [],
        "contextEvents": [],
        "memoryEvents": [],
        "environmentEvents": [],
        "environmentSnapshots": [],
        "environment": {},
    }


def _tool_only_trace() -> dict:
    trace = _trace()
    trace["conversation"] = [
        {
            "messageId": "m-user",
            "role": "user",
            "content": "删除临时文件",
        },
        {
            "messageId": "m-tool-call",
            "role": "assistant",
            "content": "",
            "toolCalls": [
                {
                    "id": "tool-1",
                    "type": "function",
                    "function": {
                        "name": "shell",
                        "arguments": '{"command":"rm tmp/a","path":"tmp/a"}',
                    },
                }
            ],
        },
        {
            "messageId": "m-tool-result",
            "role": "tool",
            "toolCallId": "tool-1",
            "content": "permission denied",
        },
    ]
    trace["toolInvocations"] = trace["toolInvocations"][:1]
    return trace


class _FakeClient:
    def preflight(self, headers, *, session_id, request_ids):
        return {
            "sessionId": session_id,
            "requestIds": request_ids,
            "title": "测试任务",
            "model": "test-model",
            "canReview": True,
            "blockers": [],
            "runs": [],
            "aggregate": {
                "durationMs": 3400,
                "totalTokens": 120,
                "toolCount": 2,
                "failedToolCount": 2,
                "artifactCount": 0,
                "subagentCount": 0,
            },
        }

    def export_bundle(self, headers, *, session_id, request_ids):
        return {
            "schemaVersion": "1.0",
            "runs": [
                {
                    "requestId": "req-1",
                    "sessionId": session_id,
                    "trace": _trace(),
                }
            ],
            "artifacts": [],
        }

    def model_config(self, headers, *, session_id):
        return {
            "model": "group/deepseek",
            "baseUrl": "http://127.0.0.1:3425/v1",
            "apiKey": "test-key",
        }


def test_fast_report_detects_failed_and_repeated_tools() -> None:
    report = build_fast_report({"runs": [{"trace": _trace()}]})
    codes = {item["code"] for item in report["findings"]}
    assert "tool_failure" in codes
    assert "repeated_identical_call" in codes
    assert report["execution"]["failedToolCount"] == 2
    assert report["metrics"]["repeatedCallExcess"] == 1
    assert report["runs"][0]["timeline"]["steps"][0]["content"] == "我先检查一下。"


def test_fast_report_explains_tool_only_steps() -> None:
    report = build_fast_report({"runs": [{"trace": _tool_only_trace()}]})
    run = report["runs"][0]
    step = run["timeline"]["steps"][0]
    tool = run["timeline"]["tools"][0]

    assert step["content"] == ""
    assert step["summary"] == "调用工具：shell"
    assert step["status"] == "FAILED"
    assert step["kind"] == "tool"
    assert step["visible"] is False
    assert tool["toolName"] == "shell"
    assert tool["argumentBrief"] == "rm tmp/a"
    assert tool["failed"] is True
    assert tool["resultPreview"] == "permission denied"
    assert run["finalText"] == "已处理"


def test_assistant_text_is_completed_even_when_its_tool_fails() -> None:
    trace = _tool_only_trace()
    trace["conversation"][1]["content"] = "我先检查失败原因。"

    report = build_fast_report({"runs": [{"trace": trace}]})
    run = report["runs"][0]
    step = run["timeline"]["steps"][0]
    tool = run["timeline"]["tools"][0]

    assert step["status"] == "COMPLETED"
    assert step["kind"] == "model"
    assert tool["failed"] is True


def test_tool_failure_signal_handles_sanitized_invalid_json() -> None:
    assert (
        _tool_failure_signal(
            {
                "status": "SUCCESS",
                "toolResult": '{"ok":false,"stderr":"bad \\Users\\path"}',
            }
        )
        == "result:ok=false"
    )


def test_requested_tool_is_pending_not_failed() -> None:
    assert _tool_failure_signal({"status": "REQUESTED"}) is None
    assert _tool_failure_signal({"status": "RUNNING"}) is None


def test_job_manager_writes_fast_report_and_cache(tmp_path: Path) -> None:
    settings = TaskReviewSettings(
        review_home=tmp_path,
        judge=SemanticJudgeConfig(),
    )
    manager = TaskReviewJobManager(settings, client=_FakeClient())
    try:
        job = manager.create(
            {},
            session_id="session-1",
            request_ids=["req-1"],
            include_llm_judge=False,
        )
        assert job["status"] == "completed"
        assert job["quality"]["status"] == "unverified"
        assert job["quality"]["narrative"]["status"] == "fact_only"
        assert job["report"]["execution"]["toolCount"] == 2
        assert job["evidenceIndex"]
        assert "tool:req-1:tool-1" in job["evidenceIndex"]
        assert job["report"]["diagnostics"]["diagnosticSummary"]["totalFailures"] == 2
        assert manager.get(job["jobId"])["jobId"] == job["jobId"]
        assert (
            tmp_path
            / job["jobId"]
            / "quality"
            / "narrative.md"
        ).is_file()
        schema = json.loads(
            (
                Path(__file__).parents[1]
                / "schemas"
                / "task-review.schema.json"
            ).read_text(encoding="utf-8")
        )
        jsonschema.validate(job["report"], schema)
        assert manager.list()[0]["jobId"] == job["jobId"]

        job_path = tmp_path / job["jobId"] / "job.json"
        stored = json.loads(job_path.read_text(encoding="utf-8"))
        stored["evidenceIndex"] = {}
        stored["quality"]["narrative"]["developerActions"] = [
            {
                "target": "MODEL_ARGUMENT",
                "priority": "P1",
                "action": "优化 Prompt",
                "expectedEffect": "提升稳定性",
                "evidenceRefs": ["tool:req-1:tool-1"],
            }
        ]
        manager.store.write(job_path, stored)
        upgraded = manager.get(job["jobId"])
        assert upgraded["evidenceIndex"]
        assert all(
            item.get("actionId")
            for item in upgraded["quality"]["narrative"]["developerActions"]
        )
        assert "可复制给 Coding Agent 的修复任务" in (
            upgraded["quality"]["narrative"]["markdown"]
        )
    finally:
        manager.close()


def test_service_returns_job_and_errors(tmp_path: Path) -> None:
    class _Manager:
        def create(self, headers, **kwargs):
            return {
                "jobId": "review-test",
                "status": "completed",
                "fast": {"completion": {"conclusion": "unknown"}},
            }

        def get(self, job_id):
            if job_id != "review-test":
                raise KeyError(job_id)
            return {"jobId": job_id, "status": "completed"}

        def list(self, *, limit=20):
            return []

        def cancel(self, job_id):
            return {"jobId": job_id, "status": "cancelled"}

        def report_markdown(self, job_id):
            if job_id != "review-test":
                raise KeyError(job_id)
            return "# 复盘报告"

        def close(self):
            return None

    app = create_app(manager=_Manager())
    with TestClient(app) as client:
        response = client.post(
            "/task-review/jobs",
            json={
                "sessionId": "session-1",
                "requestIds": ["req-1"],
                "includeLlmJudge": False,
            },
        )
        assert response.status_code == 202
        assert response.json()["jobId"] == "review-test"
        assert client.get("/task-review/jobs/review-test").status_code == 200
        assert client.get("/task-review/jobs/missing").status_code == 404
        report = client.get("/task-review/jobs/review-test/report.md")
        assert report.status_code == 200
        assert report.text == "# 复盘报告"


def test_judge_without_config_is_unverified(tmp_path: Path) -> None:
    settings = TaskReviewSettings(
        review_home=tmp_path,
        judge=SemanticJudgeConfig(),
    )
    bundle = {"runs": [{"trace": _trace()}]}
    fast = build_fast_report(bundle)
    result = run_task_review_judge(
        bundle,
        fast,
        settings=settings,
        job_id="smoke",
    )
    assert result["status"] == "unverified"
    assert result["error"]["kind"] == "not_configured"
    assert "not configured" in result["error"]["message"]
    assert result["summary"] == "AI 深度复盘没有生成可验证结论。"


def test_invalid_judge_output_preserves_validation_detail(
    tmp_path: Path,
    monkeypatch,
) -> None:
    def _invalid_packet(*args, **kwargs):
        return SimpleNamespace(
            status="unverified",
            summary="",
            confidence=0.0,
            weighted_score=None,
            judge_model="test-model",
            prompt_hash="sha256:test",
            prompt_injection_suspected=False,
            dimensions=[],
            limitations=[],
            error={
                "kind": "invalid_judge_output",
                "message": "ValueError: judge omitted dimensions: task_completion",
            },
        )

    monkeypatch.setattr(task_review_judge, "judge_packet", _invalid_packet)
    result = run_task_review_judge(
        {"runs": [{"trace": _trace()}]},
        build_fast_report({"runs": [{"trace": _trace()}]}),
        settings=TaskReviewSettings(
            review_home=tmp_path,
            judge=SemanticJudgeConfig(),
        ),
        job_id="smoke-invalid",
    )

    assert result["error"]["kind"] == "invalid_judge_output"
    assert (
        "judge omitted dimensions: task_completion"
        in result["error"]["message"]
    )


def test_resolves_judge_config_from_java_agent(tmp_path: Path) -> None:
    manager = TaskReviewJobManager(
        TaskReviewSettings(
            review_home=tmp_path,
            judge=SemanticJudgeConfig(),
        ),
        client=_FakeClient(),
    )
    try:
        judge = manager._resolve_judge_config(
            {},
            "session-1",
            requested=True,
        )
        assert judge is not None
        assert judge.model == "group/deepseek"
        assert judge.base_url == "http://127.0.0.1:3425/v1"
        assert judge.api_key == "test-key"
    finally:
        manager.close()


def test_yaml_judge_and_env_api_key_are_merged(
    tmp_path: Path,
    monkeypatch,
) -> None:
    config_path = tmp_path / "task-review.yaml"
    config_path.write_text(
        "\n".join(
            [
                "judge:",
                "  model: group/deepseek",
                "  baseUrl: http://127.0.0.1:3425/v1",
            ]
        ),
        encoding="utf-8",
    )
    monkeypatch.setenv("CUTE_EVAL_TASK_REVIEW_CONFIG", str(config_path))
    monkeypatch.setenv("CUTE_EVAL_JUDGE_API_KEY", "secret-from-env")
    monkeypatch.delenv("CUTE_EVAL_JUDGE_MODEL", raising=False)
    monkeypatch.delenv("CUTE_EVAL_JUDGE_BASE_URL", raising=False)

    settings = TaskReviewSettings.from_env()

    assert settings.judge.model == "group/deepseek"
    assert settings.judge.base_url == "http://127.0.0.1:3425/v1"
    assert settings.judge.api_key == "secret-from-env"


def test_review_store_purges_expired_jobs(tmp_path: Path) -> None:
    store = ReviewStore(tmp_path)
    expired = store.create_job_dir("review-expired")
    current = store.create_job_dir("review-current")
    store.write(expired / "job.json", {"status": "completed"})
    store.write(current / "job.json", {"status": "completed"})
    old = 1
    os.utime(expired / "job.json", (old, old))

    removed = store.purge_expired(30)

    assert removed == 1
    assert not expired.exists()
    assert current.exists()


def test_review_store_rejects_path_traversal(tmp_path: Path) -> None:
    store = ReviewStore(tmp_path)

    with pytest.raises(ValueError):
        store.job_dir("../outside")
