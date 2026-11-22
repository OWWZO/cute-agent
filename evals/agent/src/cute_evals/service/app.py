from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Literal

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse, PlainTextResponse
from pydantic import BaseModel, ConfigDict, Field

from ..task_review.java_client import JavaTaskReviewError
from ..task_review.jobs import TaskReviewJobManager
from ..task_review.evidence_index import EvidenceRefError
from ..task_review.models import TaskReviewSettings


class CreateTaskReviewRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    session_id: str = Field(alias="sessionId")
    request_ids: list[str] = Field(alias="requestIds")
    scope: Literal["run", "tree"] = "run"
    mode: Literal["fast", "standard"] = "standard"
    include_llm_judge: bool = Field(default=True, alias="includeLlmJudge")
    criteria: str | None = None
    client_request_id: str | None = Field(default=None, alias="clientRequestId")


def create_app(manager: TaskReviewJobManager | None = None) -> FastAPI:
    settings = TaskReviewSettings.from_env()
    job_manager = manager or TaskReviewJobManager(settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.manager = job_manager
        try:
            yield
        finally:
            job_manager.close()

    app = FastAPI(
        title="Cute Eval Task Review",
        version="0.1.0",
        lifespan=lifespan,
    )
    app.state.manager = job_manager

    @app.get("/healthz")
    async def healthz() -> dict[str, Any]:
        return {
            "ok": True,
            "judgeConfigured": True,
            "fallbackJudgeConfigured": settings.judge.openai_compatible,
            "judgeSource": "current-java-session-model",
        }

    @app.post("/task-review/jobs", status_code=202)
    async def create_job(
        body: CreateTaskReviewRequest,
        request: Request,
    ) -> JSONResponse:
        try:
            job = await run_in_threadpool(
                job_manager.create,
                request.headers,
                session_id=body.session_id,
                request_ids=body.request_ids,
                scope=body.scope,
                mode=body.mode,
                include_llm_judge=body.include_llm_judge,
                criteria=body.criteria,
                client_request_id=body.client_request_id,
            )
            return JSONResponse(status_code=202, content=job)
        except JavaTaskReviewError as exc:
            return _error(exc.status_code, exc.code, str(exc))
        except ValueError as exc:
            return _error(400, "INVALID_REQUEST", str(exc))
        except Exception as exc:  # noqa: BLE001 - API boundary
            return _error(500, "TASK_REVIEW_FAILED", str(exc))

    @app.get("/task-review/jobs/{job_id}")
    async def get_job(job_id: str) -> JSONResponse:
        try:
            return JSONResponse(content=await run_in_threadpool(job_manager.get, job_id))
        except (KeyError, ValueError):
            return _error(404, "JOB_NOT_FOUND", "复盘任务不存在")

    @app.get("/task-review/jobs/{job_id}/report.md")
    async def get_report_markdown(job_id: str) -> PlainTextResponse:
        try:
            markdown = await run_in_threadpool(
                job_manager.report_markdown,
                job_id,
            )
            return PlainTextResponse(
                markdown,
                media_type="text/markdown; charset=utf-8",
                headers={
                    "Content-Disposition": (
                        f'attachment; filename="{job_id}-review.md"'
                    )
                },
            )
        except (KeyError, ValueError):
            return _error(404, "REPORT_NOT_FOUND", "复盘报告不存在")

    @app.get("/task-review/jobs/{job_id}/evidence")
    async def get_evidence(job_id: str, ref: str = "") -> JSONResponse:
        try:
            evidence = await run_in_threadpool(
                job_manager.evidence,
                job_id,
                ref,
            )
            return JSONResponse(content=evidence)
        except EvidenceRefError as exc:
            return _error(
                exc.status_code,
                exc.code,
                str(exc),
                candidates=exc.candidates,
            )
        except (KeyError, ValueError):
            return _error(404, "JOB_NOT_FOUND", "复盘任务不存在")

    @app.get("/task-review/jobs")
    async def list_jobs(limit: int = 20) -> JSONResponse:
        jobs = await run_in_threadpool(job_manager.list, limit=limit)
        return JSONResponse(content={"jobs": jobs})

    @app.post("/task-review/jobs/{job_id}/cancel")
    async def cancel_job(job_id: str) -> JSONResponse:
        try:
            return JSONResponse(
                content=await run_in_threadpool(job_manager.cancel, job_id)
            )
        except (KeyError, ValueError):
            return _error(404, "JOB_NOT_FOUND", "复盘任务不存在")

    return app


def _error(
    status_code: int,
    code: str,
    message: str,
    *,
    candidates: list[dict[str, Any]] | None = None,
) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "error": {
                "code": code,
                "message": message,
                "retryable": status_code >= 500 or code == "JAVA_EXPORT_FAILED",
                **({"candidates": candidates} if candidates else {}),
            }
        },
    )


app = create_app()
