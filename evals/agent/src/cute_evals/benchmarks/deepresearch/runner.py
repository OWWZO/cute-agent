from __future__ import annotations

import json
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


@dataclass(slots=True)
class DeepSearchRun:
    task_id: str
    request_id: str
    status: str
    report: str
    events: list[dict[str, Any]]
    docs: list[dict[str, Any]]
    duration_ms: int
    started_at: str
    finished_at: str
    query: str
    base_url: str
    search_engines: list[str] = field(default_factory=list)
    error: str | None = None
    http_status: int | None = None

    @property
    def event_counts(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for event in self.events:
            message_type = str(event.get("messageType") or "unknown")
            counts[message_type] = counts.get(message_type, 0) + 1
        return dict(sorted(counts.items()))

    def save_artifacts(self, workspace: Path) -> None:
        workspace.mkdir(parents=True, exist_ok=True)
        (workspace / "deepsearch-report.md").write_text(
            self.report,
            encoding="utf-8",
        )
        with (workspace / "deepsearch-events.jsonl").open(
            "w",
            encoding="utf-8",
            newline="\n",
        ) as stream:
            for event in self.events:
                stream.write(json.dumps(event, ensure_ascii=False) + "\n")
        (workspace / "deepsearch-docs.json").write_text(
            json.dumps(self.docs, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def trace_payload(self, *, trace_path: str) -> dict[str, Any]:
        conversation_id = f"deepsearch-{self.task_id}-{uuid.uuid4().hex[:8]}"
        environment_events = [
            {
                "eventId": f"{self.request_id}-started",
                "conversationId": conversation_id,
                "turnId": "turn-001",
                "type": "deepsearch_started",
                "payload": {
                    "baseUrl": self.base_url,
                    "query": self.query,
                    "searchEngines": self.search_engines,
                },
            },
            {
                "eventId": f"{self.request_id}-finished",
                "conversationId": conversation_id,
                "turnId": "turn-001",
                "type": "deepsearch_finished",
                "payload": {
                    "status": self.status,
                    "eventCounts": self.event_counts,
                    "docCount": len(self.docs),
                    "reportLength": len(self.report),
                    "durationMs": self.duration_ms,
                    "httpStatus": self.http_status,
                    "error": self.error,
                },
            },
        ]
        return {
            "schemaVersion": "1.0",
            "producer": {
                "agent": "python-deepsearch",
                "runtimeVersion": "deepresearch-bench-adapter-v1",
                "exportedAt": self.finished_at,
            },
            "run": {
                "caseId": self.task_id,
                "requestId": self.request_id,
                "sessionId": conversation_id,
                "entryAgent": "deepsearch",
                "status": self.status,
                "queryText": self.query,
                "finalSummaryText": self.report,
                "startedAt": self.started_at,
                "finishedAt": self.finished_at,
                "durationMs": self.duration_ms,
                "totalTokens": None,
                "llmCallCount": None,
                "toolCallCount": 1,
                "artifactCount": 3,
            },
            "llmInvocations": [],
            "toolInvocations": [
                {
                    "toolCallId": self.request_id,
                    "toolName": "deepsearch",
                    "inputJson": json.dumps(
                        {
                            "query": self.query,
                            "searchEngines": self.search_engines,
                        },
                        ensure_ascii=False,
                    ),
                    "toolResult": self.report,
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
                    "content": self.query,
                },
                {
                    "messageId": f"{self.request_id}-assistant",
                    "conversationId": conversation_id,
                    "turnId": "turn-001",
                    "turnIndex": 1,
                    "role": "assistant",
                    "content": self.report,
                },
            ],
            "conversationSource": "deepsearch-direct",
            "environmentEvents": environment_events,
            "environmentSource": "deepsearch-sse",
            "environmentSnapshots": [],
            "snapshotSource": None,
            "artifacts": [
                {"kind": "research_report", "path": "deepsearch-report.md"},
                {"kind": "deepsearch_events", "path": "deepsearch-events.jsonl"},
                {"kind": "deepsearch_docs", "path": "deepsearch-docs.json"},
            ],
            "environment": {
                "mode": "python-http",
                "deepsearchUrl": self.base_url,
                "tracePath": trace_path,
                "eventCounts": self.event_counts,
                "docCount": len(self.docs),
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


def _iter_docs(value: Any):
    if isinstance(value, dict):
        yield value
        return
    if not isinstance(value, list):
        return
    for item in value:
        if isinstance(item, dict):
            yield item
        elif isinstance(item, list):
            yield from _iter_docs(item)


def run_deepsearch(
    *,
    base_url: str,
    query: str,
    task_id: str,
    timeout_seconds: int = 1800,
    connect_timeout_seconds: int = 10,
    search_engines: list[str] | None = None,
    max_loop: int = 1,
) -> DeepSearchRun:
    try:
        import requests
    except ImportError as exc:
        raise RuntimeError(
            "Direct DeepSearch evaluation requires the 'deepresearch' extra "
            "(requests and tqdm)"
        ) from exc

    request_id = f"eval-dr-{task_id}-{uuid.uuid4().hex[:10]}"
    started_at = _now()
    started = time.monotonic()
    endpoint = base_url.rstrip("/") + "/v1/tool/deepsearch"
    payload = {
        "request_id": request_id,
        "query": query,
        "report_file_name": f"deepresearch-{task_id}.md",
        "max_loop": max_loop,
        "search_engines": search_engines or [],
        "stream": True,
    }
    events: list[dict[str, Any]] = []
    docs_by_link: dict[str, dict[str, Any]] = {}
    report_parts: list[str] = []
    final_answer: str | None = None
    final_seen = False
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
                status = "FAILED"
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
                        error = f"invalid DeepSearch SSE event: {exc}; data={data[:500]}"
                        status = "FAILED"
                        break
                    if not isinstance(event, dict):
                        error = f"DeepSearch SSE event is not an object: {event!r}"
                        status = "FAILED"
                        break
                    events.append(event)
                    message_type = str(event.get("messageType") or "")
                    if message_type in {"search", "chapter_summary"}:
                        search_result = event.get("searchResult") or {}
                        for doc in _iter_docs(search_result.get("docs") or []):
                            link = str(doc.get("link") or "")
                            key = link or str(doc.get("title") or len(docs_by_link))
                            docs_by_link[key] = doc
                    if message_type == "report":
                        answer = str(event.get("answer") or "")
                        if answer:
                            report_parts.append(answer)
                        if bool(event.get("isFinal")):
                            final_seen = True
                            final_answer = answer or "".join(report_parts)
                            break
                if final_seen:
                    status = "SUCCESS"
                elif error is None:
                    error = "DeepSearch stream closed before isFinal=true"
                    status = "FAILED"
    except requests.exceptions.Timeout as exc:
        status = "TIMEOUT"
        error = str(exc)
    except Exception as exc:  # noqa: BLE001 - network boundary is a score status
        status = "FAILED"
        error = str(exc)

    report = (final_answer if final_answer is not None else "".join(report_parts)).strip()
    if status == "SUCCESS" and not report:
        status = "FAILED"
        error = error or "DeepSearch returned an empty final report"
    finished_at = _now()
    duration_ms = int((time.monotonic() - started) * 1000)
    return DeepSearchRun(
        task_id=task_id,
        request_id=request_id,
        status=status,
        report=report,
        events=events,
        docs=list(docs_by_link.values()),
        duration_ms=duration_ms,
        started_at=started_at,
        finished_at=finished_at,
        query=query,
        base_url=endpoint,
        search_engines=list(search_engines or []),
        error=error,
        http_status=http_status,
    )
