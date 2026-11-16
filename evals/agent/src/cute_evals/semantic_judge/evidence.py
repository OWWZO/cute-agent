from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from ..models import RunTrace
from .contract import EvidenceReference, JudgePacket
from .rubrics import Rubric

TEXT_SUFFIXES = frozenset(
    {
        ".csv",
        ".html",
        ".json",
        ".log",
        ".md",
        ".py",
        ".sql",
        ".txt",
        ".xml",
        ".yaml",
        ".yml",
    }
)
IGNORED_PARTS = frozenset(
    {".git", ".cute", ".venv", "__pycache__", "node_modules"}
)
PROMPT_INJECTION_MARKERS = (
    "ignore previous instructions",
    "ignore all previous instructions",
    "system prompt",
    "developer message",
    "give me 5 points",
    "忽略以上",
    "忽略之前",
    "系统提示",
)


def _truncate(value: str, limit: int) -> tuple[str, bool]:
    if len(value) <= limit:
        return value, False
    if limit <= 80:
        return value[:limit], True
    head = limit * 2 // 3
    tail = limit - head
    return value[:head] + "\n...<truncated>...\n" + value[-tail:], True


def _redact(value: str) -> str:
    patterns = (
        (r"(?i)(authorization\s*:\s*bearer\s+)[^\s,;]+", r"\1[REDACTED]"),
        (r"(?i)(api[_-]?key\s*[=:]\s*)[^\s,;]+", r"\1[REDACTED]"),
        (r"(?i)(password\s*[=:]\s*)[^\s,;]+", r"\1[REDACTED]"),
        (r"\bsk-[A-Za-z0-9_-]{12,}\b", "[REDACTED]"),
        (r"\bgh[pousr]_[A-Za-z0-9]{20,}\b", "[REDACTED]"),
    )
    redacted = value
    for pattern, replacement in patterns:
        redacted = re.sub(pattern, replacement, redacted)
    return redacted


def _stringify(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _workspace_files(
    workspace: Path | None,
    *,
    max_file_chars: int,
    remaining_chars: int,
) -> tuple[list[dict[str, Any]], int]:
    if workspace is None or not workspace.is_dir() or remaining_chars <= 0:
        return [], remaining_chars
    files: list[dict[str, Any]] = []
    used = 0
    for path in sorted(workspace.rglob("*")):
        if not path.is_file() or any(
            part in IGNORED_PARTS for part in path.relative_to(workspace).parts
        ):
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        remaining = remaining_chars - used
        if remaining <= 0:
            break
        limit = min(max_file_chars, remaining)
        try:
            content = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        content, truncated = _truncate(_redact(content), limit)
        relative = path.relative_to(workspace).as_posix()
        files.append(
            {
                "path": relative,
                "content": content,
                "truncated": truncated,
            }
        )
        used += len(content)
    return files, remaining_chars - used


def build_packet(
    trace: RunTrace,
    *,
    case_id: str,
    agent: str,
    attempt: int,
    query: str,
    expected_contract: str | None,
    risk: str | None,
    deterministic_scores: list[dict[str, Any]],
    workspace: Path | None,
    metadata: dict[str, Any] | None = None,
    max_evidence_chars: int = 120_000,
    max_tool_result_chars: int = 12_000,
    max_file_chars: int = 24_000,
) -> JudgePacket:
    conversation = []
    for index, item in enumerate(trace.conversation, start=1):
        content, truncated = _truncate(
            _redact(str(item.get("content") or "")),
            max_tool_result_chars,
        )
        conversation.append(
            {
                "id": str(item.get("messageId") or f"message-{index}"),
                "role": str(item.get("role") or ""),
                "turnId": item.get("turnId"),
                "content": content,
                "truncated": truncated,
            }
        )

    tool_calls = []
    for index, item in enumerate(trace.tool_invocations, start=1):
        result = _stringify(
            item.get("toolResult")
            if item.get("toolResult") is not None
            else item.get("llmObservation")
        )
        result, truncated = _truncate(
            _redact(result),
            max_tool_result_chars,
        )
        tool_calls.append(
            {
                "id": str(item.get("toolCallId") or item.get("id") or f"tool-{index}"),
                "toolName": str(item.get("toolName") or "unknown"),
                "input": _redact(_stringify(item.get("inputJson"))),
                "result": result,
                "status": str(item.get("status") or "UNKNOWN"),
                "truncated": truncated,
            }
        )

    final_answer = _redact(trace.final_text)
    fixed_chars = (
        len(query)
        + len(final_answer)
        + sum(len(_stringify(item)) for item in deterministic_scores)
        + sum(len(item["content"]) for item in conversation)
        + sum(len(item["input"]) + len(item["result"]) for item in tool_calls)
    )
    remaining = max(0, max_evidence_chars - fixed_chars)
    workspace_files, _ = _workspace_files(
        workspace,
        max_file_chars=max_file_chars,
        remaining_chars=remaining,
    )
    evidence_text = "\n".join(
        [
            query,
            final_answer,
            *(item["content"] for item in conversation),
            *(item["input"] for item in tool_calls),
            *(item["result"] for item in tool_calls),
            *(item["content"] for item in workspace_files),
        ]
    ).lower()
    suspected = any(
        marker in evidence_text for marker in PROMPT_INJECTION_MARKERS
    )
    return JudgePacket(
        schema_version="1.0",
        case_id=case_id,
        agent=agent,
        attempt=attempt,
        query=query,
        expected_contract=expected_contract,
        risk=risk,
        final_answer=final_answer,
        deterministic_scores=deterministic_scores,
        conversation=conversation,
        tool_calls=tool_calls,
        workspace_files=workspace_files,
        metadata=metadata or {},
        prompt_injection_suspected=suspected,
    )


def _normalize_quote(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip().lower()


def _quote_in(quote: str, source: str) -> bool:
    return _normalize_quote(quote) in _normalize_quote(source)


def validate_evidence(
    evidence: EvidenceReference,
    packet: JudgePacket,
) -> str | None:
    if not evidence.quote.strip():
        return "evidence quote is empty"
    if evidence.kind == "final_text":
        if not _quote_in(evidence.quote, packet.final_answer):
            return "final_text evidence quote was not found"
        return None
    if evidence.kind == "conversation":
        item = next(
            (
                message
                for message in packet.conversation
                if message["id"] == evidence.evidence_id
            ),
            None,
        )
        if item is None:
            return f"unknown conversation evidence id: {evidence.evidence_id}"
        if not _quote_in(evidence.quote, item["content"]):
            return "conversation evidence quote was not found"
        return None
    if evidence.kind == "tool_call":
        item = next(
            (
                call
                for call in packet.tool_calls
                if call["id"] == evidence.evidence_id
            ),
            None,
        )
        if item is None:
            return f"unknown tool call evidence id: {evidence.evidence_id}"
        source = "\n".join(
            [
                item["toolName"],
                item["input"],
                item["result"],
                item["status"],
            ]
        )
        if not _quote_in(evidence.quote, source):
            return "tool call evidence quote was not found"
        return None
    if evidence.kind == "workspace_file":
        item = next(
            (
                file
                for file in packet.workspace_files
                if file["path"] == evidence.path
            ),
            None,
        )
        if item is None:
            return f"unknown workspace evidence path: {evidence.path}"
        if not _quote_in(evidence.quote, item["content"]):
            return "workspace evidence quote was not found"
        return None
    if evidence.kind == "deterministic_score":
        item = next(
            (
                score
                for score in packet.deterministic_scores
                if str(score.get("name") or "") == evidence.evidence_id
            ),
            None,
        )
        if item is None:
            return f"unknown deterministic score: {evidence.evidence_id}"
        if not _quote_in(evidence.quote, _stringify(item)):
            return "deterministic score evidence quote was not found"
        return None
    return f"unsupported evidence kind: {evidence.kind}"


def validate_required_evidence(
    packet: JudgePacket,
    rubric: Rubric,
    dimension_ids: list[str],
) -> list[str]:
    if not (
        packet.final_answer.strip()
        or packet.conversation
        or packet.tool_calls
        or packet.workspace_files
    ):
        return ["no judge evidence is available"]
    unknown = sorted(set(dimension_ids) - {item.id for item in rubric.dimensions})
    return [f"unknown rubric dimension: {value}" for value in unknown]
