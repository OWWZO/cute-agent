from __future__ import annotations

import hashlib
import json
from collections import Counter
from typing import Any

from ..models import RunTrace
from ..trajectory_quality import _tool_failure_signal
from .argument_checks import check_arguments
from .failure_rules import FAILURE_LAYERS, classify_failure
from .recovery import canonical_args, detect_recovery


def tool_contracts(trace: RunTrace) -> list[dict[str, Any]]:
    raw_contracts = trace.raw.get("toolContracts") or []
    if isinstance(raw_contracts, list):
        result = []
        for item in raw_contracts:
            if not isinstance(item, dict):
                continue
            contract = dict(item)
            schema = (
                contract.get("inputSchema")
                or contract.get("parameters")
                or contract.get("schema")
            )
            if schema is not None and not contract.get("schemaHash"):
                encoded = json.dumps(
                    schema,
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode("utf-8")
                contract["schemaHash"] = (
                    "sha256:" + hashlib.sha256(encoded).hexdigest()
                )
            result.append(contract)
        return result
    return []


def _contract_map(contracts: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    return {
        str(item.get("toolName") or ""): item
        for item in contracts
        if item.get("toolName")
    }


def build_event_sequence(trace: RunTrace, *, request_id: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    seq = 0
    tools_by_id: dict[str, dict[str, Any]] = {}
    for index, tool in enumerate(trace.tool_invocations, start=1):
        if not isinstance(tool, dict):
            continue
        call_id = str(tool.get("toolCallId") or tool.get("id") or f"tool-{index}")
        tools_by_id[call_id] = tool
    emitted_calls: set[str] = set()

    def append_tool(call_id: str, tool: dict[str, Any] | None) -> None:
        nonlocal seq
        scoped_id = f"{request_id}:{call_id}"
        tool = tool or {}
        seq += 1
        events.append(
            {
                "seq": seq,
                "kind": "tool_call",
                "toolCallId": scoped_id,
                "toolName": str(tool.get("toolName") or "unknown"),
                "step": tool.get("stepNo"),
                "status": str(tool.get("status") or "UNKNOWN").upper(),
                "errorCode": tool.get("errorCode"),
                "arguments": tool.get("inputJson"),
            }
        )
        seq += 1
        events.append(
            {
                "seq": seq,
                "kind": "tool_result",
                "toolCallId": scoped_id,
                "status": str(tool.get("status") or "UNKNOWN").upper(),
                "content": (
                    tool.get("toolResult")
                    if tool.get("toolResult") is not None
                    else tool.get("llmObservation")
                ),
                "errorMsg": tool.get("errorMsg"),
            }
        )
        emitted_calls.add(call_id)

    for index, message in enumerate(trace.conversation, start=1):
        if not isinstance(message, dict):
            continue
        role = str(message.get("role") or "").lower()
        if role == "tool" and message.get("toolCallId"):
            continue
        content = str(message.get("content") or "").strip()
        if content:
            seq += 1
            events.append(
                {
                    "seq": seq,
                    "kind": "assistant_message"
                    if role == "assistant"
                    else "user_message",
                    "messageId": (
                        f"{request_id}:"
                        f"{message.get('messageId') or message.get('id') or index}"
                    ),
                    "content": content,
                }
            )
        for raw_call in message.get("toolCalls") or []:
            if not isinstance(raw_call, dict):
                continue
            function = raw_call.get("function")
            call_id = str(
                raw_call.get("id")
                or (
                    function.get("name")
                    if isinstance(function, dict)
                    else ""
                )
                or f"message-{index}-tool"
            )
            tool = tools_by_id.get(call_id)
            if tool is None:
                function = function if isinstance(function, dict) else {}
                tool = {
                    "toolName": function.get("name"),
                    "inputJson": function.get("arguments"),
                }
            append_tool(call_id, tool)

    for index, tool in enumerate(trace.tool_invocations, start=1):
        if not isinstance(tool, dict):
            continue
        call_id = str(tool.get("toolCallId") or tool.get("id") or f"tool-{index}")
        if call_id in emitted_calls:
            continue
        append_tool(call_id, tool)
    return events


def build_diagnostic_summary(
    attributions: list[dict[str, Any]],
    *,
    diagnosis_mismatch_count: int = 0,
) -> dict[str, Any]:
    layer_counts = Counter(item["primaryLayer"] for item in attributions)
    return {
        "totalFailures": len(attributions),
        "attributedFailures": sum(
            1 for item in attributions if item["primaryLayer"] != "UNKNOWN"
        ),
        "unattributedFailures": sum(
            1 for item in attributions if item["primaryLayer"] == "UNKNOWN"
        ),
        "layerCounts": {layer: layer_counts.get(layer, 0) for layer in FAILURE_LAYERS},
        "recoveredCount": sum(1 for item in attributions if item["recovered"]),
        "unrecoveredCount": sum(1 for item in attributions if not item["recovered"]),
        "parameterIssueCount": sum(
            1
            for item in attributions
            if item["primaryLayer"] == "MODEL_ARGUMENT"
            or "MODEL_ARGUMENT" in item["contributingLayers"]
        ),
        "diagnosisMismatchCount": diagnosis_mismatch_count,
    }


def build_diagnostics(
    bundle: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, Any], list[dict[str, Any]]]:
    attributions: list[dict[str, Any]] = []
    contracts: list[dict[str, Any]] = []
    failure_counter = 0
    seen_failed_calls: dict[tuple[str, str], str] = {}

    for run_item in bundle.get("runs") or []:
        if not isinstance(run_item, dict):
            continue
        trace_payload = run_item.get("trace")
        if not isinstance(trace_payload, dict):
            continue
        trace = RunTrace.from_dict(trace_payload)
        request_id = trace.request_id or str(run_item.get("requestId") or "")
        run_id = trace.run_uid or request_id
        run_contracts = tool_contracts(trace)
        contracts.extend(run_contracts)
        contract_by_name = _contract_map(run_contracts)

        for index, tool in enumerate(trace.tool_invocations):
            if not isinstance(tool, dict) or _tool_failure_signal(tool) is None:
                continue
            failure_counter += 1
            tool_name = str(tool.get("toolName") or "unknown")
            tool_call_id = str(tool.get("toolCallId") or tool.get("id") or f"tool-{index + 1}")
            args_key = (tool_name, canonical_args(tool.get("inputJson")))
            repeated = args_key in seen_failed_calls
            seen_failed_calls[args_key] = tool_call_id
            check = check_arguments(
                tool.get("inputJson"),
                contract_by_name.get(tool_name),
            )
            classification = classify_failure(
                tool,
                json_invalid=check.json_invalid,
                missing_required=check.missing_required,
                type_errors=check.type_errors,
                repeated_after_failure=repeated,
            )
            recovery = detect_recovery(index, trace.tool_invocations, request_id=request_id)
            scoped_tool_call_id = f"{request_id}:{tool_call_id}"
            evidence_refs = list(
                dict.fromkeys(
                    [f"tool:{scoped_tool_call_id}", *recovery.evidence_refs]
                )
            )
            attributions.append(
                {
                    "attributionId": f"attr-{failure_counter:03d}",
                    "requestId": request_id,
                    "runId": run_id,
                    "toolCallId": scoped_tool_call_id,
                    "step": tool.get("stepNo"),
                    "toolName": tool_name,
                    "toolStatus": str(tool.get("status") or "UNKNOWN").upper(),
                    "failureKind": classification.failure_kind,
                    "primaryLayer": classification.primary_layer,
                    "contributingLayers": list(classification.contributing_layers),
                    "reason": classification.reason,
                    "recovered": recovery.recovered,
                    "recoverySummary": recovery.summary,
                    "confidence": classification.confidence,
                    "source": "deterministic",
                    "ruleIds": list(classification.rule_ids),
                    "evidenceRefs": evidence_refs,
                    "evidence": [],
                }
            )

    summary = build_diagnostic_summary(attributions)
    return attributions, summary, contracts
