from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from ..trajectory_quality import _tool_failure_signal

_EVIDENCE_REF = re.compile(r"^tool:([^:\s]+)(?::([^:\s]+))?$")


class EvidenceRefError(ValueError):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int,
        candidates: list[dict[str, Any]] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.status_code = status_code
        self.candidates = list(candidates or [])


@dataclass(frozen=True, slots=True)
class ParsedEvidenceRef:
    raw: str
    request_id: str | None
    tool_call_id: str


def parse_evidence_ref(ref: str) -> ParsedEvidenceRef:
    value = str(ref or "").strip()
    match = _EVIDENCE_REF.fullmatch(value)
    if match is None:
        raise EvidenceRefError(
            "INVALID_EVIDENCE_REF",
            "证据引用格式非法，应为 tool:<requestId>:<toolCallId> 或 tool:<toolCallId>。",
            status_code=400,
        )
    first = match.group(1)
    second = match.group(2)
    return ParsedEvidenceRef(
        raw=value,
        request_id=first if second is not None else None,
        tool_call_id=second or first,
    )


def _request_id(run: dict[str, Any], trace: dict[str, Any]) -> str:
    return str(
        (trace.get("run") or {}).get("requestId")
        or run.get("requestId")
        or ""
    )


def _run_id(run: dict[str, Any], trace: dict[str, Any]) -> str:
    trace_run = trace.get("run") or {}
    return str(
        trace_run.get("runUid")
        or trace_run.get("runId")
        or run.get("runId")
        or trace_run.get("requestId")
        or run.get("requestId")
        or ""
    )


def _tool_call_id(tool: dict[str, Any], index: int) -> str:
    return str(
        tool.get("toolCallId")
        or tool.get("id")
        or f"tool-{index + 1}"
    )


def build_evidence_index(bundle: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """Build a locator-only index; raw tool I/O stays in the saved Trace."""
    index: dict[str, dict[str, Any]] = {}
    for run in bundle.get("runs") or []:
        if not isinstance(run, dict):
            continue
        trace = run.get("trace")
        if not isinstance(trace, dict):
            continue
        request_id = _request_id(run, trace)
        run_id = _run_id(run, trace)
        for tool_index, tool in enumerate(trace.get("toolInvocations") or []):
            if not isinstance(tool, dict):
                continue
            tool_call_id = _tool_call_id(tool, tool_index)
            ref = (
                f"tool:{request_id}:{tool_call_id}"
                if request_id
                else f"tool:{tool_call_id}"
            )
            index[ref] = {
                "requestId": request_id,
                "runId": run_id,
                "toolCallId": tool_call_id,
                "toolName": str(tool.get("toolName") or "unknown"),
                "step": tool.get("stepNo"),
                "status": str(tool.get("status") or "UNKNOWN").upper(),
                "failed": _tool_failure_signal(tool) is not None,
            }
    return index


def find_evidence_candidates(
    bundle: dict[str, Any],
    parsed: ParsedEvidenceRef,
) -> list[dict[str, Any]]:
    matches: list[dict[str, Any]] = []
    for run in bundle.get("runs") or []:
        if not isinstance(run, dict):
            continue
        trace = run.get("trace")
        if not isinstance(trace, dict):
            continue
        request_id = _request_id(run, trace)
        if parsed.request_id is not None and request_id != parsed.request_id:
            continue
        for tool_index, tool in enumerate(trace.get("toolInvocations") or []):
            if not isinstance(tool, dict):
                continue
            tool_call_id = _tool_call_id(tool, tool_index)
            if tool_call_id != parsed.tool_call_id:
                continue
            matches.append(
                {
                    "run": run,
                    "trace": trace,
                    "requestId": request_id,
                    "runId": _run_id(run, trace),
                    "tool": tool,
                    "toolIndex": tool_index,
                    "toolCallId": tool_call_id,
                }
            )
    return matches
