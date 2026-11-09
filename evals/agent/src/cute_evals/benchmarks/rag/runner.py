from __future__ import annotations

import json
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


@dataclass(slots=True)
class MragRun:
    task_id: str
    request_id: str
    status: str
    answer: str
    events: list[dict[str, Any]]
    retrieval_trace: dict[str, Any] | None
    duration_ms: int
    started_at: str
    finished_at: str
    question: str
    base_url: str
    kb_id: str
    context_text: str
    retrieved_contexts: list[str] = field(default_factory=list)
    total_tokens: int | None = None
    error: str | None = None
    http_status: int | None = None

    @property
    def event_counts(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for event in self.events:
            name = str(
                event.get("stage")
                or event.get("messageType")
                or ("chunk" if event.get("choices") else "unknown")
            )
            counts[name] = counts.get(name, 0) + 1
        return dict(sorted(counts.items()))

    def save_artifacts(self, workspace: Path) -> None:
        workspace.mkdir(parents=True, exist_ok=True)
        (workspace / "mrag-answer.md").write_text(
            self.answer,
            encoding="utf-8",
        )
        with (workspace / "mrag-events.jsonl").open(
            "w",
            encoding="utf-8",
            newline="\n",
        ) as stream:
            for event in self.events:
                stream.write(json.dumps(event, ensure_ascii=False) + "\n")
        (workspace / "mrag-retrieval-trace.json").write_text(
            json.dumps(
                {
                    "available": self.retrieval_trace is not None,
                    "trace": self.retrieval_trace,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

    def trace_payload(self, *, trace_path: str) -> dict[str, Any]:
        conversation_id = f"mrag-{self.task_id}-{uuid.uuid4().hex[:8]}"
        tool_result = self.context_text or self.answer
        return {
            "schemaVersion": "1.0",
            "producer": {
                "agent": "python-mrag",
                "runtimeVersion": "mrag-python-bench-adapter-v1",
                "exportedAt": self.finished_at,
            },
            "run": {
                "caseId": self.task_id,
                "requestId": self.request_id,
                "sessionId": conversation_id,
                "entryAgent": "mrag",
                "status": self.status,
                "queryText": self.question,
                "finalSummaryText": self.answer,
                "startedAt": self.started_at,
                "finishedAt": self.finished_at,
                "durationMs": self.duration_ms,
                "totalTokens": self.total_tokens,
                "llmCallCount": None,
                "toolCallCount": 1,
                "artifactCount": 3,
            },
            "llmInvocations": [],
            "toolInvocations": [
                {
                    "toolCallId": self.request_id,
                    "toolName": "mrag_query",
                    "inputJson": json.dumps(
                        {
                            "question": self.question,
                            "kbId": self.kb_id,
                        },
                        ensure_ascii=False,
                    ),
                    "toolResult": tool_result,
                    "retrievedContexts": self.retrieved_contexts,
                    "status": self.status,
                    "errorMsg": self.error,
                    "durationMs": self.duration_ms,
                    "artifactCount": 3,
                }
            ],
            "conversation": [
                {
                    "messageId": f"{self.request_id}-user",
                    "conversationId": conversation_id,
                    "turnId": "turn-001",
                    "turnIndex": 1,
                    "role": "user",
                    "content": self.question,
                },
                {
                    "messageId": f"{self.request_id}-assistant",
                    "conversationId": conversation_id,
                    "turnId": "turn-001",
                    "turnIndex": 1,
                    "role": "assistant",
                    "content": self.answer,
                },
            ],
            "conversationSource": "mrag-direct",
            "environmentEvents": [
                {
                    "eventId": f"{self.request_id}-started",
                    "conversationId": conversation_id,
                    "turnId": "turn-001",
                    "type": "mrag_started",
                    "payload": {
                        "baseUrl": self.base_url,
                        "question": self.question,
                        "kbId": self.kb_id,
                    },
                },
                {
                    "eventId": f"{self.request_id}-finished",
                    "conversationId": conversation_id,
                    "turnId": "turn-001",
                    "type": "mrag_finished",
                    "payload": {
                        "status": self.status,
                        "eventCounts": self.event_counts,
                        "retrievalTraceAvailable": self.retrieval_trace is not None,
                        "contextCount": len(self.retrieved_contexts),
                        "durationMs": self.duration_ms,
                        "httpStatus": self.http_status,
                        "error": self.error,
                    },
                },
            ],
            "environmentSource": "mrag-sse",
            "environmentSnapshots": [],
            "snapshotSource": None,
            "artifacts": [
                {"kind": "mrag_answer", "path": "mrag-answer.md"},
                {"kind": "mrag_events", "path": "mrag-events.jsonl"},
                {
                    "kind": "mrag_retrieval_trace",
                    "path": "mrag-retrieval-trace.json",
                },
            ],
            "environment": {
                "mode": "python-http",
                "mragUrl": self.base_url,
                "kbId": self.kb_id,
                "tracePath": trace_path,
                "eventCounts": self.event_counts,
                "retrievalTraceAvailable": self.retrieval_trace is not None,
                "retrievalTrace": self.retrieval_trace,
                "contextCount": len(self.retrieved_contexts),
                "totalTokens": self.total_tokens,
                "httpStatus": self.http_status,
            },
        }


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def _parse_sse_data(line: str) -> str | None:
    value = line.strip()
    if value.startswith("data:"):
        return value[5:].strip()
    if value.startswith("{"):
        return value
    return None


def _extract_retrieval_trace(event: dict[str, Any]) -> dict[str, Any] | None:
    meta = event.get("meta")
    if isinstance(meta, dict):
        candidate = meta.get("retrievalTrace")
        if isinstance(candidate, dict):
            return candidate
    candidate = event.get("retrievalTrace")
    return candidate if isinstance(candidate, dict) else None


def _extract_total_tokens(event: dict[str, Any]) -> int | None:
    candidates = [event.get("usage")]
    meta = event.get("meta")
    if isinstance(meta, dict):
        candidates.append(meta.get("usage"))
    for usage in candidates:
        if not isinstance(usage, dict):
            continue
        for key in ("total_tokens", "totalTokens", "total"):
            value = usage.get(key)
            if isinstance(value, int):
                return value
    return None


def _trace_contexts(trace: dict[str, Any] | None) -> list[str]:
    if not isinstance(trace, dict):
        return []
    contexts: list[str] = []
    seen: set[tuple[str, str]] = set()
    for stage_name in (
        "merged_text",
        "merged_image",
        "merged_page",
        "rerank_text",
    ):
        stage = trace.get(stage_name)
        if not isinstance(stage, dict):
            continue
        for hit in stage.get("hits") or []:
            if not isinstance(hit, dict):
                continue
            payload = hit.get("payload")
            if not isinstance(payload, dict):
                continue
            text = (
                payload.get("text")
                or payload.get("page_path")
                or payload.get("image_path")
                or payload.get("image_url")
            )
            if not text:
                continue
            title = (
                payload.get("filename")
                or payload.get("title")
                or payload.get("chunk_type")
                or stage_name
            )
            key = (
                str(hit.get("canonical_key") or payload.get("filename") or title),
                str(text),
            )
            if key in seen:
                continue
            seen.add(key)
            contexts.append(f"[{title}] {text}")
    return contexts


def run_mrag(
    *,
    base_url: str,
    question: str,
    task_id: str,
    kb_id: str = "",
    timeout_seconds: int = 600,
    connect_timeout_seconds: int = 10,
) -> MragRun:
    try:
        import requests
    except ImportError as exc:
        raise RuntimeError(
            "Direct MRAG evaluation requires the 'rag' extra (requests)"
        ) from exc

    request_id = f"eval-mrag-{task_id}-{uuid.uuid4().hex[:10]}"
    started_at = _now()
    started = time.monotonic()
    endpoint = base_url.rstrip("/") + "/v1/tool/mragQuery"
    payload = {
        "requestId": request_id,
        "question": question,
        "image_urls": [],
        "kb_id": kb_id,
        "include_retrieval_trace": True,
    }
    events: list[dict[str, Any]] = []
    answer_parts: list[str] = []
    retrieval_trace: dict[str, Any] | None = None
    final_seen = False
    total_tokens: int | None = None
    status = "FAILED"
    error: str | None = None
    http_status: int | None = None

    try:
        with requests.post(
            endpoint,
            json=payload,
            headers={
                "Accept": "text/event-stream",
                "Cache-Control": "no-cache",
                "Content-Type": "application/json",
            },
            stream=True,
            timeout=(connect_timeout_seconds, timeout_seconds),
        ) as response:
            http_status = response.status_code
            if response.status_code != 200:
                error = response.text[:2000]
            else:
                for raw_line in response.iter_lines(decode_unicode=True):
                    if isinstance(raw_line, bytes):
                        raw_line = raw_line.decode("utf-8", errors="replace")
                    data = _parse_sse_data(str(raw_line or ""))
                    if not data or data in {"[DONE]", "heartbeat"}:
                        continue
                    try:
                        event = json.loads(data)
                    except json.JSONDecodeError as exc:
                        error = f"invalid MRAG SSE event: {exc}; data={data[:500]}"
                        break
                    if not isinstance(event, dict):
                        error = f"MRAG SSE event is not an object: {event!r}"
                        break
                    events.append(event)
                    event_tokens = _extract_total_tokens(event)
                    if event_tokens is not None:
                        total_tokens = event_tokens

                    stage = str(event.get("stage") or "")
                    if stage == "retrieval_trace":
                        retrieval_trace = _extract_retrieval_trace(event)
                    elif stage == "error":
                        error = str(event.get("data") or "MRAG returned an error")
                        break
                    elif stage == "answer":
                        answer = str(event.get("data") or "")
                        if answer and not answer.strip().startswith("# 生成答案"):
                            answer_parts.append(answer)
                    elif stage == "final" and bool(event.get("isFinal")):
                        final_seen = True
                        break
                    elif not stage:
                        choices = event.get("choices") or []
                        if choices:
                            delta = (choices[0] or {}).get("delta") or {}
                            content = str(delta.get("content") or "")
                            if content:
                                answer_parts.append(content)
                if error is None and not final_seen:
                    error = "MRAG stream closed before stage=final isFinal=true"
                elif error is None:
                    status = "SUCCESS"
    except requests.exceptions.Timeout as exc:
        status = "TIMEOUT"
        error = str(exc)
    except Exception as exc:  # noqa: BLE001 - network boundary is a score status
        status = "FAILED"
        error = str(exc)

    answer = "".join(answer_parts).strip()
    if status == "SUCCESS" and not answer:
        status = "FAILED"
        error = error or "MRAG returned an empty final answer"
    contexts = _trace_contexts(retrieval_trace)
    finished_at = _now()
    duration_ms = int((time.monotonic() - started) * 1000)
    return MragRun(
        task_id=task_id,
        request_id=request_id,
        status=status,
        answer=answer,
        events=events,
        retrieval_trace=retrieval_trace,
        duration_ms=duration_ms,
        started_at=started_at,
        finished_at=finished_at,
        question=question,
        base_url=endpoint,
        kb_id=kb_id,
        context_text="\n\n".join(contexts),
        retrieved_contexts=contexts,
        total_tokens=total_tokens,
        error=error,
        http_status=http_status,
    )
