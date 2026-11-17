from __future__ import annotations

import json
import re
from typing import Any

from .evidence_index import (
    EvidenceRefError,
    find_evidence_candidates,
    parse_evidence_ref,
)
from .failure_rules import classify_failure
from .recovery import Recovery, detect_recovery
from ..trajectory_quality import _tool_failure_signal

MAX_INPUT_CHARS = 24_000
MAX_OUTPUT_CHARS = 24_000
MAX_TOTAL_CHARS = 48_000

_SECRET_ASSIGNMENT = re.compile(
    r"(?i)((?:api[_-]?key|authorization|cookie|password|passwd|secret|"
    r"access[_-]?token|refresh[_-]?token|id[_-]?token)\s*[:=]\s*)"
    r"(?!\[REDACTED_)(?:\"[^\"]*\"|'[^']*'|[^\s,;}]+)"
)
_BEARER = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+")
_API_KEY = re.compile(
    r"\b(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16})\b"
)
_INTERNAL_URL = re.compile(
    r"(?i)https?://(?:localhost|127\.0\.0\.1|10(?:\.\d{1,3}){3}|"
    r"192\.168(?:\.\d{1,3}){2}|172\.(?:1[6-9]|2\d|3[01])(?:\.\d{1,3}){2})"
    r"(?::\d+)?(?:/[^\s\"']*)?"
)
_WINDOWS_PATH = re.compile(
    r"(?i)(?<![A-Za-z0-9])[A-Z]:(?:\\\\|\\)"
    r"(?:[^\\\s\"']+(?:\\\\|\\))*[^\\\s\"']*"
)
_WINDOWS_FORWARD_PATH = re.compile(
    r"(?i)(?<![A-Za-z0-9])[A-Z]:/(?:[^/\s\"']+/)*[^/\s\"']*"
)
_UNIX_PATH = re.compile(
    r"(?<![A-Za-z0-9])/(?:Users|home|var|tmp|opt|srv|mnt|root|private|workspace)/"
    r"[^\s\"'<>]+"
)
_EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
_CN_MOBILE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
_SENSITIVE_QUERY = re.compile(
    r"(?i)([?&](?:token|access_token|api_key|key|signature|x-amz-signature)=)"
    r"(?!\[REDACTED)[^&#\s\"']+"
)


def _sanitize_text(value: str) -> str:
    result = _SECRET_ASSIGNMENT.sub(r"\1[REDACTED_SECRET]", value)
    result = _BEARER.sub("Bearer [REDACTED_SECRET]", result)
    result = _API_KEY.sub("[REDACTED_API_KEY]", result)
    result = _SENSITIVE_QUERY.sub(r"\1[REDACTED]", result)
    result = _EMAIL.sub("[REDACTED_EMAIL]", result)
    result = _CN_MOBILE.sub("[REDACTED_PHONE]", result)
    result = _INTERNAL_URL.sub("[REDACTED_INTERNAL_URL]", result)
    result = _WINDOWS_PATH.sub("[REDACTED_PATH]", result)
    result = _WINDOWS_FORWARD_PATH.sub("[REDACTED_PATH]", result)
    result = _UNIX_PATH.sub("[REDACTED_PATH]", result)
    return result


def _sanitize_value(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): (
                "[REDACTED_SECRET]"
                if _sensitive_key(str(key))
                else _sanitize_value(item)
            )
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_sanitize_value(item) for item in value]
    if isinstance(value, str):
        return _sanitize_text(value)
    return value


def _sensitive_key(key: str) -> bool:
    normalized = re.sub(r"[_-]", "", key).lower()
    return normalized in {
        "password",
        "passwd",
        "secret",
        "authorization",
        "cookie",
        "apikey",
        "accesstoken",
        "refreshtoken",
        "idtoken",
        "address",
        "homeaddress",
        "downloadurl",
        "previewurl",
        "storagekey",
        "localpath",
        "filepath",
    }


def _truncate(value: str, limit: int) -> tuple[str, bool, int]:
    if len(value) <= limit:
        return value, False, len(value)
    marker = "\n...<truncated>...\n"
    available = max(0, limit - len(marker))
    head = available // 2
    tail = available - head
    return value[:head] + marker + value[-tail:], True, len(value)


def _as_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _parse_json(value: str) -> tuple[Any, str | None]:
    if not value.strip():
        return None, None
    try:
        return json.loads(value), None
    except (TypeError, ValueError) as exc:
        return None, str(exc)


def _input_payload(
    tool: dict[str, Any],
) -> tuple[dict[str, Any], bool]:
    raw = _as_text(tool.get("inputJson"))
    present = bool(raw.strip())
    original_length = len(raw)
    if not present:
        return {
            "present": False,
            "raw": "",
            "parsed": None,
            "parseError": None,
            "truncated": False,
            "originalLength": 0,
        }, False
    sanitized = _sanitize_text(raw)
    parsed, parse_error = _parse_json(sanitized)
    if parse_error:
        parse_error = _sanitize_text(parse_error)
    if tool.get("inputParseError"):
        parsed = None
        parse_error = _sanitize_text(str(tool["inputParseError"]))
    limited, truncated, length = _truncate(sanitized, MAX_INPUT_CHARS)
    return {
        "present": True,
        "raw": limited,
        "parsed": None if truncated else _sanitize_value(parsed),
        "parseError": parse_error,
        "truncated": truncated,
        "originalLength": length or original_length,
    }, truncated


def _output_payload(
    tool: dict[str, Any],
) -> tuple[dict[str, Any], bool]:
    tool_result = _as_text(tool.get("toolResult"))
    observation = _as_text(tool.get("llmObservation"))
    source = (
        "toolResult"
        if tool_result.strip()
        else "llmObservation"
        if observation.strip()
        else "missing"
    )
    raw = tool_result if source == "toolResult" else observation
    if source == "missing":
        return {
            "present": False,
            "source": "missing",
            "raw": "",
            "parsed": None,
            "parseError": None,
            "truncated": False,
            "originalLength": 0,
        }, False
    sanitized = _sanitize_text(raw)
    parsed, parse_error = _parse_json(sanitized)
    if parse_error:
        parse_error = _sanitize_text(parse_error)
    limited, truncated, length = _truncate(sanitized, MAX_OUTPUT_CHARS)
    return {
        "present": True,
        "source": source,
        "raw": limited,
        "parsed": None if truncated else _sanitize_value(parsed),
        "parseError": parse_error,
        "truncated": truncated,
        "originalLength": length,
    }, truncated


def _artifacts_for_tool(
    trace: dict[str, Any],
    tool: dict[str, Any],
    tool_call_id: str,
) -> list[dict[str, Any]]:
    invocation_id = str(tool.get("id") or "")
    result = []
    for artifact in trace.get("artifacts") or []:
        if not isinstance(artifact, dict):
            continue
        if str(artifact.get("toolCallId") or "") != tool_call_id and (
            not invocation_id
            or str(artifact.get("toolInvocationId") or "") != invocation_id
        ):
            continue
        result.append(
            {
                "artifactId": str(
                    artifact.get("artifactId")
                    or artifact.get("id")
                    or "artifact"
                ),
                "relativeRef": artifact.get("relativeRef"),
                "mimeType": artifact.get("mimeType"),
                "fileSize": artifact.get("fileSize"),
                "fileHash": artifact.get("fileHash"),
                "previewAvailable": artifact.get("previewAvailable"),
            }
        )
    return result


def _ambiguity(
    ref: str,
    candidates: list[dict[str, Any]],
) -> EvidenceRefError:
    summaries = [
        {
            "requestId": item["requestId"],
            "runId": item["runId"],
            "toolCallId": item["toolCallId"],
            "toolName": str(item["tool"].get("toolName") or "unknown"),
        }
        for item in candidates
    ]
    return EvidenceRefError(
        "AMBIGUOUS_EVIDENCE_REF",
        f"证据引用 {ref} 命中多个 Run，请改用 tool:<requestId>:<toolCallId>。",
        status_code=409,
        candidates=summaries,
    )


def resolve_evidence(bundle: dict[str, Any], ref: str) -> dict[str, Any]:
    parsed = parse_evidence_ref(ref)
    candidates = find_evidence_candidates(bundle, parsed)
    if not candidates:
        raise EvidenceRefError(
            "EVIDENCE_NOT_FOUND",
            "当前复盘任务中找不到这条工具证据。",
            status_code=404,
        )
    if len(candidates) > 1:
        raise _ambiguity(parsed.raw, candidates)

    match = candidates[0]
    trace = match["trace"]
    tool = match["tool"]
    tool_call_id = match["toolCallId"]
    request_id = match["requestId"]
    tools = [
        item
        for item in trace.get("toolInvocations") or []
        if isinstance(item, dict)
    ]
    failed = _tool_failure_signal(tool) is not None or str(
        tool.get("status") or ""
    ).upper() in {"FAILED", "TIMEOUT", "STOPPED", "CANCELLED", "ERROR"}
    recovery = (
        detect_recovery(
            match["toolIndex"],
            tools,
            request_id=request_id,
        )
        if failed
        else Recovery(
            recovered=False,
            recovery_type="NOT_APPLICABLE",
            summary="当前调用本身成功，不需要恢复。",
            evidence_refs=[],
        )
    )
    input_payload, input_truncated = _input_payload(tool)
    output_payload, output_truncated = _output_payload(tool)

    # The two category limits normally keep the combined payload below 48K.
    # If both are at their cap, trim the output first so the request remains
    # inspectable while the response keeps a visible truncation marker.
    if len(input_payload["raw"]) + len(output_payload["raw"]) > MAX_TOTAL_CHARS:
        overflow = (
            len(input_payload["raw"])
            + len(output_payload["raw"])
            - MAX_TOTAL_CHARS
        )
        next_limit = max(0, len(output_payload["raw"]) - overflow)
        limited, truncated, length = _truncate(output_payload["raw"], next_limit)
        output_payload["raw"] = limited
        output_payload["parsed"] = None if truncated else output_payload["parsed"]
        output_payload["truncated"] = output_payload["truncated"] or truncated
        output_payload["originalLength"] = max(
            int(output_payload.get("originalLength") or 0),
            length,
        )
        output_truncated = output_truncated or truncated

    status = str(tool.get("status") or "UNKNOWN").upper()
    classification = classify_failure(tool)
    current_ref = (
        f"tool:{request_id}:{tool_call_id}"
        if request_id
        else f"tool:{tool_call_id}"
    )
    recovery_refs = [
        item for item in recovery.evidence_refs if item != current_ref
    ]
    limitations = []
    if input_payload["parseError"]:
        limitations.append("工具入参不是合法 JSON，原始文本仍保留在证据中。")
    if output_payload["source"] == "missing":
        limitations.append("Trace 中没有记录该工具调用的结果或观察文本。")
    if input_truncated:
        limitations.append("入参超过 24000 字符，已保留头尾并截断中间内容。")
    if output_truncated:
        limitations.append("出参超过展示上限，已保留头尾并截断中间内容。")

    return {
        "schemaVersion": "1.0",
        "ref": current_ref,
        "kind": "tool_call",
        "resolved": True,
        "requestId": request_id,
        "runId": match["runId"],
        "toolCallId": tool_call_id,
        "toolName": str(tool.get("toolName") or "unknown"),
        "step": tool.get("stepNo"),
        "status": status,
        "failed": failed,
        "input": input_payload,
        "output": output_payload,
        "error": {
            "message": _sanitize_text(str(tool.get("errorMsg") or "")),
            "code": tool.get("errorCode"),
            "class": classification.failure_kind,
        },
        "timing": {
            "startedAt": tool.get("startedAt"),
            "finishedAt": tool.get("finishedAt"),
            "durationMs": tool.get("durationMs"),
        },
        "context": {
            "subAgentId": tool.get("subAgentId"),
            "subAgentType": tool.get("subAgentType"),
            "dispatchIndex": tool.get("dispatchIndex"),
            "llmInvocationId": tool.get("llmInvocationId"),
        },
        "recovery": {
            "recovered": recovery.recovered,
            "type": recovery.recovery_type,
            "summary": recovery.summary,
            "refs": recovery_refs,
        },
        "artifacts": _artifacts_for_tool(trace, tool, tool_call_id),
        "limitations": limitations,
    }
