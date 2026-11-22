from __future__ import annotations

from dataclasses import replace
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import Any

from .diagnostics import build_diagnostics
from .action_compiler import build_developer_actions
from .evidence import resolve_evidence
from .evidence_index import build_evidence_index
from .facts import build_fast_report
from .java_client import JavaTaskReviewClient
from .judge import run_task_review_judge
from .models import TaskReviewSettings
from .narrative import generate_narrative, render_markdown
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
        scope: str = "run",
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
        if scope not in {"run", "tree"}:
            raise ValueError("scope 只支持 run 或 tree")
        preflight = (
            {"sessionId": session_id, "requestIds": ordered_ids, "canReview": True, "blockers": [], "title": None, "model": None}
            if scope == "tree"
            else self.client.preflight(
                headers,
                session_id=session_id,
                request_ids=ordered_ids,
            )
        )
        if scope == "run" and not preflight.get("canReview"):
            blockers = preflight.get("blockers") or ["Run 尚未结束"]
            raise ValueError("; ".join(str(item) for item in blockers))
        bundle = self.client.export_bundle(
            headers,
            session_id=session_id,
            request_ids=ordered_ids,
            scope=scope,
        )
        evidence_index = build_evidence_index(bundle)
        fast = build_fast_report(bundle, criteria=criteria)
        failure_attributions, diagnostic_summary, contracts = build_diagnostics(
            bundle
        )
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
                "scope": scope,
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
        diagnostics = {
            "failureAttributions": failure_attributions,
            "diagnosticSummary": diagnostic_summary,
            "toolContracts": contracts,
        }
        quality.update(
            {
                "failureAttributions": failure_attributions,
                "diagnosticSummary": diagnostic_summary,
                "diagnostics": diagnostics,
            }
        )
        job = {
            "schemaVersion": "1.0",
            "jobId": job_id,
            "status": "fast_ready",
            "mode": mode,
            "includeLlmJudge": include_llm_judge,
            "sessionId": session_id,
            "requestIds": ordered_ids,
            "scope": scope,
            "title": preflight.get("title"),
            "model": preflight.get("model"),
            "createdAt": _now(),
            "updatedAt": _now(),
            "progress": _progress("fast_ready", quality["status"]),
            "fast": fast,
            "quality": quality,
            "diagnostics": diagnostics,
            "evidenceIndex": evidence_index,
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
            job["quality"]["narrative"] = generate_narrative(
                fast,
                query=_query_text(bundle),
                dimensions=[],
                failure_attributions=failure_attributions,
                diagnostic_summary=diagnostic_summary,
                config=SemanticJudgeConfig(),
            )
            job["quality"]["narrativeStatus"] = job["quality"]["narrative"].get(
                "status",
                "fact_only",
            )
            self._write_narrative(job_id, job["quality"]["narrative"])
            job["report"] = _compose_report(job)
            self._save(job_id, job)
        return self.get(job_id)

    def get(self, job_id: str) -> dict[str, Any]:
        path = self.store.job_dir(job_id) / "job.json"
        if not path.is_file():
            raise KeyError(job_id)
        with self._lock:
            job = self.store.read(path)
            if self._upgrade_saved_artifacts(job_id, job):
                self._save(job_id, job)
            return job

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

    def report_markdown(self, job_id: str) -> str:
        job = self.get(job_id)
        path = self.store.job_dir(job_id) / "quality" / "narrative.md"
        if path.is_file():
            return path.read_text(encoding="utf-8")
        narrative = (job.get("quality") or {}).get("narrative") or {}
        markdown = str(narrative.get("markdown") or "").strip()
        if not markdown:
            raise KeyError(job_id)
        return markdown

    def evidence(self, job_id: str, ref: str) -> dict[str, Any]:
        self.get(job_id)
        bundle_path = self.store.job_dir(job_id) / "trace" / "bundle.json"
        if not bundle_path.is_file():
            raise KeyError(job_id)
        bundle = self.store.read(bundle_path)
        return resolve_evidence(bundle, ref)

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
            if result.get("diagnostics"):
                job["diagnostics"] = result["diagnostics"]
            narrative = result.get("narrative") or {}
            if narrative:
                self._write_narrative(job_id, narrative)
            job["quality"]["narrativeStatus"] = narrative.get(
                "status",
                "fact_only",
            )
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
            previous_quality = dict(job.get("quality") or {})
            job["quality"] = {
                **previous_quality,
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
            try:
                bundle = self.store.read(
                    self.store.job_dir(job_id) / "trace" / "bundle.json"
                )
                diagnostics = job.get("diagnostics") or {}
                job["quality"]["narrative"] = generate_narrative(
                    job.get("fast") or {},
                    query=_query_text(bundle),
                    dimensions=[],
                    failure_attributions=(
                        diagnostics.get("failureAttributions") or []
                    ),
                    diagnostic_summary=(
                        diagnostics.get("diagnosticSummary") or {}
                    ),
                    config=SemanticJudgeConfig(),
                )
                job["quality"]["narrativeStatus"] = job["quality"][
                    "narrative"
                ].get("status", "fact_only")
                self._write_narrative(
                    job_id,
                    job["quality"]["narrative"],
                )
            except Exception:  # noqa: BLE001 - keep the diagnostic ledger
                pass
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

    def _upgrade_saved_artifacts(
        self,
        job_id: str,
        job: dict[str, Any],
    ) -> bool:
        """Backfill new evidence locators and detailed actions for old jobs."""
        quality = job.get("quality") or {}
        narrative = quality.get("narrative")
        actions = (
            narrative.get("developerActions")
            if isinstance(narrative, dict)
            else []
        )
        legacy_actions = any(
            isinstance(item, dict) and not item.get("actionId")
            for item in actions
        )
        has_failures = bool(
            (job.get("diagnostics") or {}).get("failureAttributions")
            or quality.get("failureAttributions")
        )
        needs_actions = isinstance(narrative, dict) and (
            legacy_actions or (has_failures and not actions)
        )
        if job.get("evidenceIndex") and not needs_actions:
            return False
        bundle_path = self.store.job_dir(job_id) / "trace" / "bundle.json"
        if not bundle_path.is_file():
            return False
        bundle = self.store.read(bundle_path)
        if not job.get("evidenceIndex"):
            job["evidenceIndex"] = build_evidence_index(bundle)
        if needs_actions:
            attributions = (
                (job.get("diagnostics") or {}).get("failureAttributions")
                or quality.get("failureAttributions")
                or []
            )
            upgraded = dict(narrative)
            upgraded["developerActions"] = build_developer_actions(
                attributions,
                fast=job.get("fast") or {},
            )
            upgraded["markdown"] = render_markdown(upgraded)
            quality["narrative"] = upgraded
            job["quality"] = quality
            self._write_narrative(job_id, upgraded)
        job["report"] = _compose_report(job)
        return True

    def _write_narrative(self, job_id: str, narrative: dict[str, Any]) -> None:
        quality_dir = self.store.job_dir(job_id) / "quality"
        self.store.write(quality_dir / "narrative.json", narrative)
        self.store.write_text(
            quality_dir / "narrative.md",
            str(narrative.get("markdown") or ""),
        )

    def _pending_quality(
        self,
        requested: bool,
        judge_config: SemanticJudgeConfig | None = None,
    ) -> dict[str, Any]:
        if not requested:
            return {
                "status": "unverified",
                "summary": "本次只执行快速体检。",
                "narrativeStatus": "pending",
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
                "narrativeStatus": "pending",
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
            "narrativeStatus": "pending",
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


def _query_text(bundle: dict[str, Any]) -> str:
    return "\n\n".join(
        str((run.get("trace") or {}).get("run", {}).get("queryText") or "")
        for run in bundle.get("runs") or []
        if isinstance(run, dict)
        and str(
            (run.get("trace") or {}).get("run", {}).get("queryText") or ""
        ).strip()
    )


def _progress(status: str, quality_status: str) -> list[dict[str, str]]:
    stages = [
        ("exporting", "completed"),
        ("projecting", "completed"),
        ("deterministic_audit", "completed"),
        ("diagnostics", "completed"),
        (
            "deep_review",
            "running"
            if quality_status == "running"
            else "completed"
            if quality_status == "verified"
            else "unverified",
        ),
        (
            "narrative",
            "completed"
            if quality_status == "verified"
            else "failed"
            if quality_status == "failed"
            else "unverified",
        ),
    ]
    if status in {"failed", "cancelled"}:
        stages[4] = (
            "deep_review",
            "failed" if status == "failed" else "cancelled",
        )
        stages[5] = (
            "narrative",
            "failed" if status == "failed" else "cancelled",
        )
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
        "evidenceIndex": job.get("evidenceIndex") or {},
        "diagnostics": (job.get("quality") or {}).get("diagnostics")
        or job.get("diagnostics")
        or {
            "failureAttributions": (
                (job.get("quality") or {}).get("failureAttributions") or []
            ),
            "diagnosticSummary": (
                (job.get("quality") or {}).get("diagnosticSummary") or {}
            ),
            "toolContracts": [],
        },
        "narrative": (job.get("quality") or {}).get("narrative"),
        "limitations": fast.get("limitations") or [],
        "quality": job.get("quality") or {},
        "usagePolicy": {
            "officialScore": False,
            "gateEligible": False,
            "publicationAllowed": False,
            "advisory": True,
        },
    }
