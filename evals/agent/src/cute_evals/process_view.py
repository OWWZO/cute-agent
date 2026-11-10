from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from typing import Any

from .models import RunTrace

PROCESS_VIEW_SCHEMA_VERSION = "1.0"


class ProcessViewError(ValueError):
    """Raised when a trace cannot be projected without inventing evidence."""


def _stable_id(prefix: str, value: str) -> str:
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]
    return f"{prefix}-{digest}"


def _json_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, (dict, list)):
        return value
    return str(value)


@dataclass(slots=True)
class ProcessMessage:
    message_index: int
    role: str
    content: str
    source_id: str | None = None
    actor: str | None = None
    tool_call_id: str | None = None
    tool_calls: Any = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        payload = {
            "messageIndex": self.message_index,
            "role": self.role,
            "content": self.content,
            "sourceId": self.source_id,
            "actor": self.actor,
            "toolCallId": self.tool_call_id,
            "metadata": self.metadata,
        }
        if self.tool_calls is not None:
            payload["toolCalls"] = _json_value(self.tool_calls)
        return payload


@dataclass(slots=True)
class ProcessEvidence:
    evidence_id: str
    kind: str
    source_id: str | None
    status: str | None
    payload: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "evidenceId": self.evidence_id,
            "kind": self.kind,
            "sourceId": self.source_id,
            "status": self.status,
            "payload": self.payload,
        }


@dataclass(slots=True)
class ProcessStep:
    step_id: str
    ordinal: int
    message_index: int
    content: str
    status: str
    evidence: list[ProcessEvidence] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "stepId": self.step_id,
            "ordinal": self.ordinal,
            "messageIndex": self.message_index,
            "content": self.content,
            "status": self.status,
            "evidence": [item.to_dict() for item in self.evidence],
        }


@dataclass(slots=True)
class ProcessView:
    run_id: str
    request_id: str
    session_id: str | None
    query: str
    status: str
    final_text: str
    messages: list[ProcessMessage]
    assistant_message_indices: list[int]
    steps: list[ProcessStep]
    tools: list[dict[str, Any]]
    orphan_evidence: list[ProcessEvidence]
    source: dict[str, Any]
    schema_version: str = PROCESS_VIEW_SCHEMA_VERSION

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "runId": self.run_id,
            "requestId": self.request_id,
            "sessionId": self.session_id,
            "query": self.query,
            "status": self.status,
            "finalText": self.final_text,
            "messages": [item.to_dict() for item in self.messages],
            "assistantMessageIndices": self.assistant_message_indices,
            "steps": [item.to_dict() for item in self.steps],
            "tools": self.tools,
            "orphanEvidence": [item.to_dict() for item in self.orphan_evidence],
            "source": self.source,
        }


def _message_source_id(message: dict[str, Any]) -> str | None:
    for key in ("messageId", "id", "seqNo"):
        value = message.get(key)
        if value is not None and str(value).strip():
            return str(value)
    return None


def _tool_result_evidence(
    message: ProcessMessage,
    tool_invocations: dict[str, dict[str, Any]],
) -> ProcessEvidence:
    invocation = (
        tool_invocations.get(message.tool_call_id)
        if message.tool_call_id
        else None
    )
    payload = {
        "content": message.content,
    }
    if invocation is not None:
        payload.update(
            {
                "toolName": invocation.get("toolName"),
                "errorMsg": invocation.get("errorMsg"),
                "durationMs": invocation.get("durationMs"),
                "artifactCount": invocation.get("artifactCount"),
            }
        )
    source_id = message.tool_call_id or message.source_id
    return ProcessEvidence(
        evidence_id=_stable_id(
            "tool-result",
            source_id or f"message:{message.message_index}",
        ),
        kind="tool_result",
        source_id=source_id,
        status=(
            str(invocation.get("status"))
            if invocation is not None and invocation.get("status") is not None
            else None
        ),
        payload=payload,
    )


def project_process_view(trace: RunTrace | dict[str, Any]) -> ProcessView:
    """Project a ledger trace to the neutral, step-oriented judge input view."""
    run_trace = trace if isinstance(trace, RunTrace) else RunTrace.from_dict(trace)
    if not run_trace.conversation:
        raise ProcessViewError(
            "trace has no conversation messages; process quality cannot be judged"
        )

    messages: list[ProcessMessage] = []
    for index, item in enumerate(run_trace.conversation):
        if not isinstance(item, dict):
            raise ProcessViewError(f"conversation item {index} is not an object")
        role = str(item.get("role") or item.get("actor") or "").strip().lower()
        if not role:
            raise ProcessViewError(f"conversation item {index} has no role")
        messages.append(
            ProcessMessage(
                message_index=index,
                role=role,
                content=str(item.get("content") or ""),
                source_id=_message_source_id(item),
                actor=(
                    str(item["actor"])
                    if item.get("actor") is not None
                    else None
                ),
                tool_call_id=(
                    str(item["toolCallId"])
                    if item.get("toolCallId") is not None
                    else None
                ),
                tool_calls=item.get("toolCalls"),
                metadata={
                    key: _json_value(value)
                    for key, value in item.items()
                    if key
                    not in {
                        "messageId",
                        "id",
                        "seqNo",
                        "role",
                        "actor",
                        "content",
                        "toolCallId",
                        "toolCalls",
                    }
                },
            )
        )

    assistant_indices = [
        item.message_index
        for item in messages
        if item.role == "assistant"
    ]
    if not assistant_indices:
        raise ProcessViewError(
            "trace has no assistant messages; process quality cannot be judged"
        )

    steps_by_message: dict[int, ProcessStep] = {}
    seen_step_ids: set[str] = set()
    for ordinal, message_index in enumerate(assistant_indices, start=1):
        message = messages[message_index]
        seed = (
            message.source_id
            or f"{run_trace.request_id}:message:{message_index}"
        )
        step_id = _stable_id("step", seed)
        if step_id in seen_step_ids:
            raise ProcessViewError(f"duplicate process step id: {step_id}")
        seen_step_ids.add(step_id)
        steps_by_message[message_index] = ProcessStep(
            step_id=step_id,
            ordinal=ordinal,
            message_index=message_index,
            content=message.content,
            status=str(message.metadata.get("status") or "OBSERVED"),
        )

    ordered_steps = [steps_by_message[index] for index in assistant_indices]
    llm_by_step: dict[int, dict[str, Any]] = {}
    for index, invocation in enumerate(run_trace.llm_invocations):
        step_no = invocation.get("stepNo")
        target = int(step_no) if isinstance(step_no, int) else index + 1
        if 1 <= target <= len(ordered_steps):
            llm_by_step[target] = invocation
    for step in ordered_steps:
        invocation = llm_by_step.get(step.ordinal)
        if invocation is None:
            continue
        step.evidence.append(
            ProcessEvidence(
                evidence_id=_stable_id(
                    "llm",
                    str(invocation.get("id") or f"{run_trace.request_id}:{step.ordinal}"),
                ),
                kind="llm",
                source_id=(
                    str(invocation["id"])
                    if invocation.get("id") is not None
                    else None
                ),
                status=(
                    str(invocation["status"])
                    if invocation.get("status") is not None
                    else None
                ),
                payload={
                    key: _json_value(invocation.get(key))
                    for key in (
                        "modelName",
                        "stepNo",
                        "startedAt",
                        "firstTokenAt",
                        "finishedAt",
                        "promptTokens",
                        "completionTokens",
                        "reasoningTokens",
                        "totalTokens",
                    )
                    if invocation.get(key) is not None
                },
            )
        )

    tool_invocations = {
        str(item["toolCallId"]): item
        for item in run_trace.tool_invocations
        if item.get("toolCallId") is not None
    }
    orphan_evidence: list[ProcessEvidence] = []
    current_step: ProcessStep | None = None
    for message in messages:
        if message.role == "assistant":
            current_step = steps_by_message[message.message_index]
            for call_index, call in enumerate(
                message.tool_calls
                if isinstance(message.tool_calls, list)
                else []
            ):
                function = (
                    call.get("function")
                    if isinstance(call, dict)
                    and isinstance(call.get("function"), dict)
                    else {}
                )
                source_id = (
                    str(call.get("id"))
                    if isinstance(call, dict) and call.get("id") is not None
                    else f"{message.message_index}:{call_index}"
                )
                current_step.evidence.append(
                    ProcessEvidence(
                        evidence_id=_stable_id("tool-call", source_id),
                        kind="tool_call",
                        source_id=source_id,
                        status="REQUESTED",
                        payload={
                            "toolName": function.get("name"),
                            "arguments": function.get("arguments"),
                        },
                    )
                )
            continue
        if message.role != "tool":
            continue
        evidence = _tool_result_evidence(message, tool_invocations)
        if current_step is None:
            orphan_evidence.append(evidence)
        else:
            current_step.evidence.append(evidence)

    raw = run_trace.raw
    run = raw.get("run") or {}
    raw_tools = raw.get("tools") or run.get("tools") or []
    tools = [item for item in raw_tools if isinstance(item, dict)]
    return ProcessView(
        run_id=str(run.get("runUid") or run.get("requestId") or ""),
        request_id=run_trace.request_id,
        session_id=(
            str(run["sessionId"])
            if run.get("sessionId") is not None
            else None
        ),
        query=str(run.get("queryText") or ""),
        status=run_trace.status,
        final_text=run_trace.final_text,
        messages=messages,
        assistant_message_indices=assistant_indices,
        steps=ordered_steps,
        tools=tools,
        orphan_evidence=orphan_evidence,
        source={
            "schemaVersion": raw.get("schemaVersion"),
            "producer": raw.get("producer") or {},
            "conversationSource": run_trace.conversation_source,
        },
    )


def _invocation_order(invocation: dict[str, Any], index: int) -> tuple[Any, ...]:
    invocation_seq = invocation.get("invocationSeq")
    step_no = invocation.get("stepNo")
    started_at = str(invocation.get("startedAt") or "")
    invocation_id = invocation.get("id")
    return (
        invocation_seq if isinstance(invocation_seq, int) else 2**31,
        step_no if isinstance(step_no, int) else 2**31,
        started_at,
        invocation_id if isinstance(invocation_id, int) else index,
    )


def project_ledger_process_view(trace: RunTrace | dict[str, Any]) -> ProcessView:
    """Reconstruct judgeable steps from ledger facts when conversation is absent."""
    run_trace = trace if isinstance(trace, RunTrace) else RunTrace.from_dict(trace)
    invocations = [
        item
        for item in run_trace.llm_invocations
        if isinstance(item, dict)
    ]
    if not invocations:
        raise ProcessViewError(
            "trace has no conversation and no LLM invocations; "
            "process quality cannot be judged"
        )
    invocations = [
        item
        for _, item in sorted(
            enumerate(invocations),
            key=lambda pair: _invocation_order(pair[1], pair[0]),
        )
    ]

    tools = [
        item
        for item in run_trace.tool_invocations
        if isinstance(item, dict)
    ]
    tools_by_llm: dict[str, list[dict[str, Any]]] = {}
    tools_by_step: dict[str, list[dict[str, Any]]] = {}
    for tool in tools:
        llm_id = tool.get("llmInvocationId")
        if llm_id is not None:
            tools_by_llm.setdefault(str(llm_id), []).append(tool)
        step_no = tool.get("stepNo")
        if isinstance(step_no, int):
            tools_by_step.setdefault(str(step_no), []).append(tool)

    messages: list[ProcessMessage] = []
    query = str((run_trace.raw.get("run") or {}).get("queryText") or "")
    if query:
        messages.append(
            ProcessMessage(
                message_index=0,
                role="user",
                content=query,
                source_id=f"{run_trace.request_id}:query",
                metadata={"source": "run.queryText"},
            )
        )

    linked_tool_ids: set[str] = set()
    step_messages: list[ProcessMessage] = []
    step_tools: dict[int, list[dict[str, Any]]] = {}
    for invocation in invocations:
        invocation_id = invocation.get("id")
        linked = (
            list(tools_by_llm.get(str(invocation_id), []))
            if invocation_id is not None
            else []
        )
        if not linked:
            step_no = invocation.get("stepNo")
            candidates = (
                tools_by_step.get(str(step_no), [])
                if isinstance(step_no, int)
                else []
            )
            if len(candidates) == 1:
                linked = list(candidates)
        linked.sort(
            key=lambda item: (
                item.get("dispatchIndex")
                if isinstance(item.get("dispatchIndex"), int)
                else 2**31,
                str(item.get("startedAt") or ""),
                item.get("id") if isinstance(item.get("id"), int) else 2**31,
            )
        )
        for tool in linked:
            tool_call_id = tool.get("toolCallId")
            if tool_call_id is not None:
                linked_tool_ids.add(str(tool_call_id))
        message_index = len(messages)
        response_text = invocation.get("responseText")
        tool_calls = [
            {
                "id": (
                    str(tool["toolCallId"])
                    if tool.get("toolCallId") is not None
                    else str(tool.get("id"))
                ),
                "type": "function",
                "function": {
                    "name": tool.get("toolName"),
                    "arguments": tool.get("inputJson"),
                },
            }
            for tool in linked
        ]
        message = ProcessMessage(
            message_index=message_index,
            role="assistant",
            content=response_text if isinstance(response_text, str) else "",
            source_id=(
                f"llm-invocation:{invocation_id}"
                if invocation_id is not None
                else f"{run_trace.request_id}:llm:{len(step_messages) + 1}"
            ),
            actor=(
                str(invocation["agentName"])
                if invocation.get("agentName") is not None
                else None
            ),
            tool_calls=tool_calls or None,
            metadata={
                "source": "ledger.llmInvocation",
                "llmInvocationId": invocation_id,
                "stepNo": invocation.get("stepNo"),
                "responseTextAvailable": isinstance(response_text, str)
                and bool(response_text.strip()),
                "reasoningContentAvailable": isinstance(
                    invocation.get("reasoningContent"),
                    str,
                )
                and bool(str(invocation.get("reasoningContent")).strip()),
                "declaredToolCallCount": invocation.get("toolCallCount"),
                "linkedToolCallCount": len(linked),
                "status": invocation.get("status"),
            },
        )
        messages.append(message)
        step_messages.append(message)
        step_tools[message_index] = linked

        for tool in linked:
            tool_call_id = (
                str(tool["toolCallId"])
                if tool.get("toolCallId") is not None
                else str(tool.get("id"))
            )
            messages.append(
                ProcessMessage(
                    message_index=len(messages),
                    role="tool",
                    content=str(
                        tool.get("toolResult")
                        or tool.get("llmObservation")
                        or ""
                    ),
                    source_id=tool_call_id,
                    actor=(
                        str(tool["agentName"])
                        if tool.get("agentName") is not None
                        else None
                    ),
                    tool_call_id=tool_call_id,
                    metadata={
                        "source": "ledger.toolInvocation",
                        "toolInvocationId": tool.get("id"),
                        "toolName": tool.get("toolName"),
                        "status": tool.get("status"),
                        "errorMsg": tool.get("errorMsg"),
                        "durationMs": tool.get("durationMs"),
                    },
                )
            )

    if not step_messages:
        raise ProcessViewError(
            "trace has no reconstructable assistant steps"
        )

    assistant_indices = [message.message_index for message in step_messages]
    steps: list[ProcessStep] = []
    for ordinal, (message, invocation) in enumerate(
        zip(step_messages, invocations, strict=True),
        start=1,
    ):
        evidence: list[ProcessEvidence] = [
            ProcessEvidence(
                evidence_id=_stable_id(
                    "llm",
                    str(
                        invocation.get("id")
                        or f"{run_trace.request_id}:{ordinal}"
                    ),
                ),
                kind="llm",
                source_id=(
                    str(invocation["id"])
                    if invocation.get("id") is not None
                    else None
                ),
                status=(
                    str(invocation["status"])
                    if invocation.get("status") is not None
                    else None
                ),
                payload={
                    key: _json_value(invocation.get(key))
                    for key in (
                        "agentName",
                        "stepNo",
                        "invocationSeq",
                        "modelName",
                        "callKind",
                        "responseText",
                        "reasoningContent",
                        "startedAt",
                        "firstTokenAt",
                        "finishedAt",
                        "finishReason",
                        "promptTokens",
                        "completionTokens",
                        "reasoningTokens",
                        "totalTokens",
                        "cachedPromptTokens",
                        "cacheStatus",
                        "cacheRiskFlags",
                        "errorMsg",
                    )
                    if invocation.get(key) is not None
                },
            )
        ]
        for tool in step_tools.get(message.message_index, []):
            tool_call_id = (
                str(tool["toolCallId"])
                if tool.get("toolCallId") is not None
                else str(tool.get("id"))
            )
            evidence.append(
                ProcessEvidence(
                    evidence_id=_stable_id("tool-call", tool_call_id),
                    kind="tool_call",
                    source_id=tool_call_id,
                    status="REQUESTED",
                    payload={
                        "toolInvocationId": tool.get("id"),
                        "toolName": tool.get("toolName"),
                        "arguments": tool.get("inputJson"),
                        "llmInvocationId": tool.get("llmInvocationId"),
                        "dispatchIndex": tool.get("dispatchIndex"),
                    },
                )
            )
            evidence.append(
                ProcessEvidence(
                    evidence_id=_stable_id("tool-result", tool_call_id),
                    kind="tool_result",
                    source_id=tool_call_id,
                    status=(
                        str(tool["status"])
                        if tool.get("status") is not None
                        else None
                    ),
                    payload={
                        "content": _json_value(
                            tool.get("toolResult")
                            or tool.get("llmObservation")
                            or ""
                        ),
                        "toolName": tool.get("toolName"),
                        "errorMsg": tool.get("errorMsg"),
                        "durationMs": tool.get("durationMs"),
                        "artifactCount": tool.get("artifactCount"),
                    },
                )
            )
        steps.append(
            ProcessStep(
                step_id=_stable_id(
                    "step",
                    message.source_id or f"message:{message.message_index}",
                ),
                ordinal=ordinal,
                message_index=message.message_index,
                content=message.content,
                status=str(invocation.get("status") or "OBSERVED"),
                evidence=evidence,
            )
        )

    orphan_evidence: list[ProcessEvidence] = []
    for tool in tools:
        tool_call_id = (
            str(tool["toolCallId"])
            if tool.get("toolCallId") is not None
            else str(tool.get("id"))
        )
        if tool_call_id in linked_tool_ids:
            continue
        orphan_evidence.append(
            ProcessEvidence(
                evidence_id=_stable_id("tool-result", tool_call_id),
                kind="tool_result",
                source_id=tool_call_id,
                status=(
                    str(tool["status"])
                    if tool.get("status") is not None
                    else None
                ),
                payload={
                    "toolName": tool.get("toolName"),
                    "toolResult": _json_value(
                        tool.get("toolResult")
                        or tool.get("llmObservation")
                        or ""
                    ),
                    "errorMsg": tool.get("errorMsg"),
                    "llmInvocationId": tool.get("llmInvocationId"),
                    "stepNo": tool.get("stepNo"),
                },
            )
        )

    raw = run_trace.raw
    run = raw.get("run") or {}
    raw_tools = raw.get("tools") or run.get("tools") or []
    verification = {
        "responseTextMissing": sum(
            1
            for invocation in invocations
            if not isinstance(invocation.get("responseText"), str)
            or not str(invocation.get("responseText")).strip()
        ),
        "firstTokenAtMissing": sum(
            1
            for invocation in invocations
            if invocation.get("firstTokenAt") is None
        ),
        "cachedPromptTokensMissing": sum(
            1
            for invocation in invocations
            if invocation.get("cachedPromptTokens") is None
        ),
        "orphanToolInvocations": len(orphan_evidence),
        "toolCallCountMismatches": sum(
            1
            for invocation in invocations
            if isinstance(invocation.get("toolCallCount"), int)
            and str(invocation.get("id")) in tools_by_llm
            and int(invocation["toolCallCount"])
            != len(tools_by_llm[str(invocation.get("id"))])
        ),
    }
    return ProcessView(
        run_id=str(run.get("runUid") or run.get("requestId") or ""),
        request_id=run_trace.request_id,
        session_id=(
            str(run["sessionId"])
            if run.get("sessionId") is not None
            else None
        ),
        query=query,
        status=run_trace.status,
        final_text=run_trace.final_text,
        messages=messages,
        assistant_message_indices=assistant_indices,
        steps=steps,
        tools=[
            item
            for item in raw_tools
            if isinstance(item, dict)
        ],
        orphan_evidence=orphan_evidence,
        source={
            "schemaVersion": raw.get("schemaVersion"),
            "producer": raw.get("producer") or {},
            "conversationSource": "ledger-reconstructed",
            "reconstructionVersion": "1.0",
            "originalConversationSource": run_trace.conversation_source,
            "verification": verification,
        },
    )


def process_view_json(view: ProcessView) -> str:
    return json.dumps(view.to_dict(), ensure_ascii=False, indent=2)
