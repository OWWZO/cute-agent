from __future__ import annotations

from dataclasses import replace
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import Any

from .facts import build_fast_report
from .java_client import JavaTaskReviewClient
from .judge import run_task_review_judge
from .models import TaskReviewSettings
from .storage import ReviewStore
from ..semantic_judge.config import SemanticJudgeConfig


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


class TaskReviewJobManager:
    def __init__(
        self,
        settings: TaskReviewSettings,
        *,
        client: JavaTaskReviewClient | None = None,
    ) -> None:
        self.settings = settings
        self.settings.ensure_home()
        self.client = client or JavaTaskReviewClient(settings)
        self.store = ReviewStore(settings.review_home)
        self.store.purge_expired(settings.retention_days)
        self._lock = threading.RLock()
        self._job_judge: dict[str, SemanticJudgeConfig] = {}
        self._deep_pool = ThreadPoolExecutor(
            max_workers=max(1, settings.deep_workers),
            thread_name_prefix="task-review",
        )
        self._recover()

    def create(
        self,
        headers: Any,
        *,
        session_id: str,
        request_ids: list[str],
        mode: str = "standard",
        include_llm_judge: bool = True,
        criteria: str | None = None,
        client_request_id: str | None = None,
    ) -> dict[str, Any]:
        if not session_id.strip():
            raise ValueError("sessionId 不能为空")
        if not request_ids:
            raise ValueError("requestIds 不能为空")
        if len(request_ids) > self.settings.max_request_ids:
            raise ValueError(
                f"一次最多复盘 {self.settings.max_request_ids} 个 Run"
            )
        ordered_ids = list(dict.fromkeys(item.strip() for item in request_ids if item.strip()))
        if not ordered_ids:
            raise ValueError("requestIds 不能为空")
        preflight = self.client.preflight(
            headers,
            session_id=session_id,
            request_ids=ordered_ids,
        )
        if not preflight.get("canReview"):
            blockers = preflight.get("blockers") or ["Run 尚未结束"]
            raise ValueError("; ".join(str(item) for item in blockers))
        bundle = self.client.export_bundle(
            headers,
            session_id=session_id,
            request_ids=ordered_ids,
        )
        fast = build_fast_report(bundle, criteria=criteria)
        judge_config = self._resolve_judge_config(
            headers,
            session_id,
            requested=include_llm_judge and mode != "fast",
        )

        job_id = _job_id()
        job_dir = self.store.create_job_dir(job_id)
        self.store.write(job_dir / "preflight.json", preflight)
        self.store.write(job_dir / "trace" / "bundle.json", bundle)
        self.store.write(
            job_dir / "request.json",
            {
                "sessionId": session_id,
                "requestIds": ordered_ids,
                "mode": mode,
                "includeLlmJudge": include_llm_judge,
                "criteria": criteria,
                "clientRequestId": client_request_id,
            },
        )
        quality = self._pending_quality(
            include_llm_judge and mode != "fast",
            judge_config,
        )
        job = {
            "schemaVersion": "1.0",
            "jobId": job_id,
            "status": "fast_ready",
            "mode": mode,
            "includeLlmJudge": include_llm_judge,
            "sessionId": session_id,
            "requestIds": ordered_ids,
            "title": preflight.get("title"),
            "model": preflight.get("model"),
            "createdAt": _now(),
            "updatedAt": _now(),
            "progress": _progress("fast_ready", quality["status"]),
            "fast": fast,
            "quality": quality,
            "report": None,
        }
        if quality["status"] == "running" and judge_config is not None:
            with self._lock:
                self._job_judge[job_id] = judge_config
        self._save(job_id, job)
        if quality["status"] == "running":
            self._deep_pool.submit(self._run_deep, job_id)
        else:
            job["status"] = "completed"
            job["report"] = _compose_report(job)
            self._save(job_id, job)
        return self.get(job_id)

    def get(self, job_id: str) -> dict[str, Any]:
        path = self.store.job_dir(job_id) / "job.json"
        if not path.is_file():
            raise KeyError(job_id)
        with self._lock:
            return self.store.read(path)

    def list(self, *, limit: int = 20) -> list[dict[str, Any]]:
        summaries = []
        for job_id in self.store.list_job_ids()[: max(1, min(limit, 100))]:
            try:
                job = self.get(job_id)
            except Exception:  # noqa: BLE001 - skip corrupt job files
                continue
            summaries.append(
                {
                    "jobId": job.get("jobId"),
                    "status": job.get("status"),
                    "sessionId": job.get("sessionId"),
                    "requestIds": job.get("requestIds") or [],
                    "title": job.get("title"),
                    "completion": (job.get("fast") or {}).get("completion"),
                    "qualityStatus": (job.get("quality") or {}).get("status"),
                    "createdAt": job.get("createdAt"),
                    "updatedAt": job.get("updatedAt"),
                }
            )
        return summaries

    def cancel(self, job_id: str) -> dict[str, Any]:
        with self._lock:
            job = self.get(job_id)
            if job.get("status") in {"completed", "partial", "failed", "cancelled"}:
                return job
            job["status"] = "cancelled"
            job["cancelRequested"] = True
            job["updatedAt"] = _now()
            if job.get("quality", {}).get("status") == "running":
                job["quality"]["status"] = "unverified"
                job["quality"]["error"] = {
                    "kind": "cancelled",
                    "message": "用户取消了深度复盘",
                }
            job["report"] = _compose_report(job)
            self._save(job_id, job)
            self._job_judge.pop(job_id, None)
            return job

    def close(self) -> None:
        self._deep_pool.shutdown(wait=False, cancel_futures=True)

    def _run_deep(self, job_id: str) -> None:
        try:
            job = self.get(job_id)
            if job.get("cancelRequested"):
                return
            self._update_quality(job_id, status="running")
            bundle = self.store.read(
                self.store.job_dir(job_id) / "trace" / "bundle.json"
            )
            with self._lock:
                judge_config = self._job_judge.get(job_id)
            result = run_task_review_judge(
                bundle,
                job.get("fast") or {},
                settings=(
                    replace(self.settings, judge=judge_config)
                    if judge_config is not None
                    else self.settings
                ),
                job_id=job_id,
            )
            job = self.get(job_id)
            if job.get("cancelRequested"):
                return
            job["quality"] = result
            job["status"] = "completed" if result.get("status") == "verified" else "partial"
            job["updatedAt"] = _now()
            if result.get("completion"):
                job["fast"]["completion"] = result["completion"]
            job["report"] = _compose_report(job)
            job["progress"] = _progress(job["status"], result.get("status"))
            self._save(job_id, job)
            with self._lock:
                self._job_judge.pop(job_id, None)
        except Exception as exc:  # noqa: BLE001 - judge failure is advisory
            try:
                job = self.get(job_id)
            except Exception:  # noqa: BLE001
                return
            if job.get("cancelRequested"):
                return
            job["quality"] = {
                "status": "failed",
                "summary": "深度复盘没有完成。",
                "dimensions": [],
                "findings": [],
                "suggestions": [],
                "limitations": [],
                "error": {
                    "kind": type(exc).__name__,
                    "message": str(exc),
                },
                "advisory": True,
            }
            job["status"] = "partial"
            job["updatedAt"] = _now()
            job["report"] = _compose_report(job)
            job["progress"] = _progress("partial", "failed")
            self._save(job_id, job)
            with self._lock:
                self._job_judge.pop(job_id, None)

    def _update_quality(self, job_id: str, *, status: str) -> None:
        with self._lock:
            job = self.get(job_id)
            if job.get("cancelRequested"):
                return
            job["quality"] = {
                **(job.get("quality") or {}),
                "status": status,
            }
            job["status"] = "deep_running"
            job["updatedAt"] = _now()
            job["progress"] = _progress("deep_running", status)
            self._save(job_id, job)

    def _save(self, job_id: str, job: dict[str, Any]) -> None:
        with self._lock:
            self.store.write(self.store.job_dir(job_id) / "job.json", job)

    def _pending_quality(
        self,
        requested: bool,
        judge_config: SemanticJudgeConfig | None = None,
    ) -> dict[str, Any]:
        if not requested:
            return {
                "status": "unverified",
                "summary": "本次只执行快速体检。",
                "dimensions": [],
                "findings": [],
                "suggestions": [],
                "limitations": [],
                "advisory": True,
            }
        active_judge = judge_config or self.settings.judge
        if not active_judge.openai_compatible:
            return {
                "status": "unverified",
                "summary": "Judge 模型未配置，未执行 AI 深度复盘。",
                "dimensions": [],
                "findings": [],
                "suggestions": [],
                "limitations": ["judge model is not configured"],
                "error": {
                    "kind": "not_configured",
                    "message": "当前会话的 Java Agent 模型配置不可用",
                },
                "advisory": True,
            }
        return {
            "status": "running",
            "summary": "AI 深度复盘进行中。",
            "judgeModel": active_judge.model,
            "dimensions": [],
            "findings": [],
            "suggestions": [],
            "limitations": [],
            "advisory": True,
        }

    def _resolve_judge_config(
        self,
        headers: Any,
        session_id: str,
        *,
        requested: bool,
    ) -> SemanticJudgeConfig | None:
        if not requested:
            return None
        try:
            payload = self.client.model_config(headers, session_id=session_id)
        except Exception:  # noqa: BLE001 - fall back to explicit eval config
            return None
        model = str(payload.get("model") or "").strip()
        base_url = str(payload.get("baseUrl") or "").strip()
        api_key = str(payload.get("apiKey") or "").strip()
        if not model or not base_url or not api_key:
            return None
        return SemanticJudgeConfig(
            model=model,
            base_url=base_url,
            api_key=api_key,
            timeout_seconds=self.settings.judge.timeout_seconds,
            max_evidence_chars=self.settings.judge.max_evidence_chars,
            max_tool_result_chars=self.settings.judge.max_tool_result_chars,
            max_file_chars=self.settings.judge.max_file_chars,
            require_evidence=self.settings.judge.require_evidence,
        )

    def _recover(self) -> None:
        for job_id in self.store.list_job_ids():
            try:
                job = self.get(job_id)
            except Exception:  # noqa: BLE001
                continue
            if job.get("status") not in {"fast_ready", "deep_running"}:
                continue
            if not job.get("includeLlmJudge") or job.get("mode") == "fast":
                job["status"] = "completed"
                job["report"] = _compose_report(job)
                self._save(job_id, job)
                continue
            if (job.get("quality") or {}).get("status") != "running":
                job["status"] = "completed"
                job["report"] = _compose_report(job)
                self._save(job_id, job)
                continue
            bundle = self.store.job_dir(job_id) / "trace" / "bundle.json"
            if not bundle.is_file():
                job["status"] = "partial"
                job["quality"] = {
                    **(job.get("quality") or {}),
                    "status": "failed",
                    "error": {
                        "kind": "missing_trace",
                        "message": "服务重启后找不到已保存的 Trace",
                    },
                }
                self._save(job_id, job)
                continue
            self._deep_pool.submit(self._run_deep, job_id)


def _job_id() -> str:
    return f"review-{datetime.now().strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:8]}"


def _progress(status: str, quality_status: str) -> list[dict[str, str]]:
    stages = [
        ("exporting", "completed"),
        ("projecting", "completed"),
        ("deterministic_audit", "completed"),
        (
            "deep_review",
            "running"
            if quality_status == "running"
            else "completed"
            if quality_status == "verified"
            else "unverified",
        ),
    ]
    if status in {"failed", "cancelled"}:
        stages[-1] = ("deep_review", "failed" if status == "failed" else "cancelled")
    return [{"stage": stage, "status": stage_status} for stage, stage_status in stages]


def _compose_report(job: dict[str, Any]) -> dict[str, Any]:
    fast = job.get("fast") or {}
    return {
        "schemaVersion": "1.0",
        "jobId": job.get("jobId"),
        "subject": {
            **(fast.get("subject") or {}),
            "title": job.get("title"),
            "model": job.get("model"),
        },
        "execution": fast.get("execution") or {},
        "completion": fast.get("completion") or {},
        "findings": fast.get("findings") or [],
        "metrics": fast.get("metrics") or {},
        "runs": fast.get("runs") or [],
        "artifacts": fast.get("artifacts") or [],
        "limitations": fast.get("limitations") or [],
        "quality": job.get("quality") or {},
        "usagePolicy": {
            "officialScore": False,
            "gateEligible": False,
            "publicationAllowed": False,
            "advisory": True,
        },
    }
