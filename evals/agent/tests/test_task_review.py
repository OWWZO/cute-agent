from __future__ import annotations

import json
import os
from pathlib import Path

import jsonschema
import pytest
from fastapi.testclient import TestClient

from cute_evals.semantic_judge.config import SemanticJudgeConfig
from cute_evals.task_review.facts import build_fast_report
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
        assert job["report"]["execution"]["toolCount"] == 2
        assert manager.get(job["jobId"])["jobId"] == job["jobId"]
        schema = json.loads(
            (
                Path(__file__).parents[1]
                / "schemas"
                / "task-review.schema.json"
            ).read_text(encoding="utf-8")
        )
        jsonschema.validate(job["report"], schema)
        assert manager.list()[0]["jobId"] == job["jobId"]
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
    assert "中文结论" in result["error"]["message"]
    assert result["summary"] == "AI 深度复盘没有生成可验证结论。"


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
