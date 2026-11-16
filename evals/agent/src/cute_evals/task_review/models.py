from __future__ import annotations

import os
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any

import yaml

from ..semantic_judge.config import SemanticJudgeConfig


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass(slots=True)
class TaskReviewSettings:
    java_base_url: str = "http://127.0.0.1:8100"
    review_home: Path = field(
        default_factory=lambda: Path.home() / ".cute-evals" / "task-reviews"
    )
    preflight_path: str = "/api/agent/task-review/preflight"
    trace_path: str = "/api/agent/task-review/traces"
    model_config_path: str = "/api/agent/task-review/model-config"
    java_timeout_seconds: int = 15
    max_request_ids: int = 20
    max_trace_bytes: int = 20 * 1024 * 1024
    retention_days: int = 30
    judge: SemanticJudgeConfig = field(default_factory=SemanticJudgeConfig.from_env)
    deep_workers: int = 2

    @classmethod
    def from_env(cls) -> "TaskReviewSettings":
        settings = cls()
        config_path = os.environ.get("CUTE_EVAL_TASK_REVIEW_CONFIG")
        if config_path:
            settings = settings.with_yaml(Path(config_path))
        return settings.with_env()

    def with_env(self) -> "TaskReviewSettings":
        self.java_base_url = (
            os.environ.get("CUTE_EVAL_JAVA_BASE_URL")
            or os.environ.get("SERVICE_BASE_URL")
            or self.java_base_url
        ).rstrip("/")
        review_home = os.environ.get("CUTE_EVAL_REVIEW_HOME")
        if review_home:
            self.review_home = Path(review_home).expanduser()
        self.java_timeout_seconds = _env_int(
            "CUTE_EVAL_TASK_REVIEW_JAVA_TIMEOUT",
            self.java_timeout_seconds,
        )
        self.max_request_ids = _env_int(
            "CUTE_EVAL_TASK_REVIEW_MAX_REQUEST_IDS",
            self.max_request_ids,
        )
        self.max_trace_bytes = _env_int(
            "CUTE_EVAL_TASK_REVIEW_MAX_TRACE_BYTES",
            self.max_trace_bytes,
        )
        self.retention_days = _env_int(
            "CUTE_EVAL_TASK_REVIEW_RETENTION_DAYS",
            self.retention_days,
        )
        self.deep_workers = _env_int(
            "CUTE_EVAL_TASK_REVIEW_DEEP_WORKERS",
            self.deep_workers,
        )
        env_judge = SemanticJudgeConfig.from_env()
        if any(
            value is not None
            for value in (
                env_judge.model,
                env_judge.base_url,
                env_judge.api_key,
            )
        ):
            # YAML 可以负责非敏感参数，环境变量则逐个覆盖。
            # 不能因为环境里只设置了 API Key 就整组丢弃它。
            self.judge = replace(
                self.judge,
                model=env_judge.model or self.judge.model,
                base_url=env_judge.base_url or self.judge.base_url,
                api_key=env_judge.api_key or self.judge.api_key,
            )
        return self

    def with_yaml(self, path: Path) -> "TaskReviewSettings":
        if not path.is_file():
            return self
        payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        if not isinstance(payload, dict):
            raise ValueError("task review config must be a YAML object")
        java = payload.get("java") or {}
        limits = payload.get("limits") or {}
        storage = payload.get("storage") or {}
        judge = payload.get("judge") or {}
        if java.get("baseUrl"):
            self.java_base_url = str(java["baseUrl"]).rstrip("/")
        if java.get("preflightPath"):
            self.preflight_path = str(java["preflightPath"])
        if java.get("tracePath"):
            self.trace_path = str(java["tracePath"])
        if java.get("modelConfigPath"):
            self.model_config_path = str(java["modelConfigPath"])
        if java.get("timeoutSeconds") is not None:
            self.java_timeout_seconds = int(java["timeoutSeconds"])
        if limits.get("maxRequestIds") is not None:
            self.max_request_ids = int(limits["maxRequestIds"])
        if limits.get("maxTraceBytes") is not None:
            self.max_trace_bytes = int(limits["maxTraceBytes"])
        if storage.get("home"):
            self.review_home = Path(str(storage["home"])).expanduser()
        if storage.get("retentionDays") is not None:
            self.retention_days = int(storage["retentionDays"])
        if judge.get("enabled") is False:
            self.judge = SemanticJudgeConfig(
                model=None,
                base_url=None,
                api_key=None,
                timeout_seconds=self.judge.timeout_seconds,
                max_evidence_chars=self.judge.max_evidence_chars,
            )
        elif judge.get("model"):
            self.judge = SemanticJudgeConfig(
                model=str(judge["model"]),
                base_url=(
                    str(judge["baseUrl"])
                    if judge.get("baseUrl")
                    else self.judge.base_url
                ),
                api_key=(
                    str(judge["apiKey"])
                    if judge.get("apiKey")
                    else self.judge.api_key
                ),
                timeout_seconds=int(
                    judge.get("timeoutSeconds", self.judge.timeout_seconds)
                ),
                max_evidence_chars=self.judge.max_evidence_chars,
                max_tool_result_chars=self.judge.max_tool_result_chars,
                max_file_chars=self.judge.max_file_chars,
            )
        return self

    def ensure_home(self) -> Path:
        self.review_home.mkdir(parents=True, exist_ok=True)
        return self.review_home


def json_preview(value: Any, limit: int = 600) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        import json

        value = json.dumps(value, ensure_ascii=False, sort_keys=True)
    if len(value) <= limit:
        return value
    return value[: limit - 24] + "\n...<truncated>...\n"
