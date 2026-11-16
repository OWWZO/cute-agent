# -*- coding: utf-8 -*-
"""有状态沙箱路由：bash + code_execution（应跑在 CUTE_TOOL_ROLE=sandbox 单进程上）。"""

from __future__ import annotations

from fastapi import APIRouter
from fastapi.responses import JSONResponse
from loguru import logger

from cute_tool.model.context import RequestIdCtx
from cute_tool.model.protocal import (
    BashSandboxRequest,
    CodeExecutionRequest,
    DesktopSessionCloseRequest,
    DesktopSessionOpenRequest,
)
from cute_tool.util.middleware_util import RequestHandlerRoute

router = APIRouter(route_class=RequestHandlerRoute)


def _error_response(
    status_code: int,
    message: str,
    *,
    error_code: str | None = None,
    request_id: str | None = None,
) -> JSONResponse:
    content = {"code": status_code, "message": message}
    if error_code:
        content["errorCode"] = error_code
    if request_id:
        content["requestId"] = request_id
    return JSONResponse(status_code=status_code, content=content)


@router.post("/code_execution")
async def post_code_execution(body: CodeExecutionRequest):
    """Run caller-supplied Python and return stdout, errors, and fileInfo."""
    from cute_tool.tool.direct_code_execution import (
        CodeExecutionRequestError,
        execute_code,
    )

    RequestIdCtx.request_id = body.request_id
    try:
        return await execute_code(body)
    except CodeExecutionRequestError as exc:
        logger.warning(
            "[code_execution] request_id={} error_code={} failed: {}",
            body.request_id,
            exc.error_code,
            exc,
        )
        return _error_response(
            exc.status_code,
            str(exc),
            error_code=exc.error_code,
            request_id=body.request_id,
        )
    except ValueError as exc:
        logger.warning(
            "[code_execution] request_id={} invalid request: {}",
            body.request_id,
            exc,
        )
        return _error_response(
            400,
            str(exc),
            error_code="INVALID_CODE_EXECUTION_REQUEST",
            request_id=body.request_id,
        )
    except Exception:
        logger.exception("[code_execution] request_id={} failed", body.request_id)
        return _error_response(
            500,
            "code_execution failed",
            error_code="CODE_EXECUTION_FAILED",
            request_id=body.request_id,
        )


@router.post("/bash")
async def post_bash(body: BashSandboxRequest):
    """会话沙箱 bash：runtime/skills 增量推送 → 执行 → skills 增量回写。"""
    from cute_tool.tool.bash_sandbox import run_bash_sandbox

    try:
        result = await run_bash_sandbox(body)
        return result.model_dump(by_alias=True)
    except Exception:
        logger.exception("[bash] request_id={} failed", body.request_id)
        return _error_response(500, "bash sandbox failed")


@router.post("/desktop_session/open")
async def post_desktop_session_open(body: DesktopSessionOpenRequest):
    from cute_tool.tool.desktop_session import (
        DesktopSessionError,
        open_desktop_session,
    )

    try:
        result = open_desktop_session(body)
        return result.model_dump(by_alias=True)
    except DesktopSessionError as exc:
        return _error_response(exc.status_code, exc.message)
    except Exception:
        logger.exception("[desktop_session] open request_id={} failed", body.request_id)
        return _error_response(500, "desktop_session open failed")


@router.post("/desktop_session/close")
async def post_desktop_session_close(body: DesktopSessionCloseRequest):
    from cute_tool.tool.desktop_session import (
        DesktopSessionError,
        close_desktop_session,
    )

    try:
        return close_desktop_session(body)
    except DesktopSessionError as exc:
        return _error_response(exc.status_code, exc.message)
    except Exception:
        logger.exception(
            "[desktop_session] close request_id={} failed", body.request_id
        )
        return _error_response(500, "desktop_session close failed")
