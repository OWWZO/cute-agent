from __future__ import annotations

import json
from typing import Any

import httpx

from .models import TaskReviewSettings


class JavaTaskReviewError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        status_code: int = 502,
        code: str = "JAVA_EXPORT_FAILED",
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code


def forwarded_headers(headers: Any) -> dict[str, str]:
    allowed = ("authorization", "cookie", "x-device-id")
    result: dict[str, str] = {}
    for name in allowed:
        value = headers.get(name) if hasattr(headers, "get") else None
        if value:
            result[name] = str(value)
    return result


class JavaTaskReviewClient:
    def __init__(self, settings: TaskReviewSettings) -> None:
        self.settings = settings

    def preflight(
        self,
        headers: Any,
        *,
        session_id: str,
        request_ids: list[str],
    ) -> dict[str, Any]:
        return self._post(
            self.settings.preflight_path,
            headers,
            {"sessionId": session_id, "requestIds": request_ids},
        )

    def export_bundle(
        self,
        headers: Any,
        *,
        session_id: str,
        request_ids: list[str],
    ) -> dict[str, Any]:
        payload = self._post(
            self.settings.trace_path,
            headers,
            {"sessionId": session_id, "requestIds": request_ids},
        )
        if not isinstance(payload, dict) or not isinstance(payload.get("runs"), list):
            raise JavaTaskReviewError(
                "Java Trace 导出返回结构无效",
                code="TRACE_INVALID",
            )
        size = len(
            json.dumps(payload, ensure_ascii=False).encode("utf-8")
        )
        if size > self.settings.max_trace_bytes:
            raise JavaTaskReviewError(
                "Trace Bundle 超过大小限制",
                status_code=413,
                code="TRACE_TOO_LARGE",
            )
        return payload

    def model_config(
        self,
        headers: Any,
        *,
        session_id: str,
    ) -> dict[str, Any]:
        payload = self._post(
            self.settings.model_config_path,
            headers,
            {"sessionId": session_id},
        )
        if not isinstance(payload, dict):
            raise JavaTaskReviewError(
                "Java 模型配置返回结构无效",
                code="MODEL_CONFIG_INVALID",
            )
        return payload

    def _post(
        self,
        path: str,
        headers: Any,
        payload: dict[str, Any],
    ) -> Any:
        url = f"{self.settings.java_base_url}{path}"
        try:
            response = httpx.post(
                url,
                json=payload,
                headers=forwarded_headers(headers),
                timeout=self.settings.java_timeout_seconds,
            )
        except httpx.HTTPError as exc:
            raise JavaTaskReviewError(
                f"无法连接 Java 导出接口: {type(exc).__name__}",
                code="JAVA_EXPORT_FAILED",
            ) from exc
        try:
            body = response.json()
        except ValueError as exc:
            raise JavaTaskReviewError(
                "Java 导出接口返回了非 JSON 响应",
                status_code=response.status_code,
                code="JAVA_EXPORT_FAILED",
            ) from exc
        code = str(body.get("code") if isinstance(body, dict) else "")
        if response.status_code >= 400:
            raise JavaTaskReviewError(
                str(body.get("info") or body.get("message") or "Java 导出失败"),
                status_code=response.status_code,
                code=_java_error_code(code, response.status_code),
            )
        if code not in {"0000", "200"}:
            raise JavaTaskReviewError(
                str(body.get("info") or "Java 导出失败"),
                status_code=403 if code == "0003" else 400,
                code=_java_error_code(code, response.status_code),
            )
        return body.get("data")


def _java_error_code(code: str, status: int) -> str:
    if code == "0003" or status == 403:
        return "RUN_OWNERSHIP_DENIED"
    if status == 413:
        return "TRACE_TOO_LARGE"
    return "JAVA_EXPORT_FAILED"
