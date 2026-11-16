from __future__ import annotations

import hashlib
import json
from collections import Counter
from typing import Any

from ..models import RunTrace
from ..trajectory_quality import (
    _tool_failure_signal,
    deterministic_metrics,
    project_quality_view,
)
from .models import json_preview

_STATUS_ORDER = {
    "SUCCESS": 0,
    "STOPPED": 1,
    "TIMEOUT": 2,
    "FAILED": 3,
    "UNKNOWN": 4,
}


def _sha256(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


def _canonical_args(value: Any) -> str:
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except (TypeError, ValueError):
            return value
        if isinstance(parsed, (dict, list)):
            value = parsed
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return str(value or "")


def _tool_id(tool: dict[str, Any], index: int) -> str:
    return str(
        tool.get("toolCallId")
        or tool.get("id")
        or f"tool-{index + 1}"
    )


def _finding(
    code: str,
    severity: str,
    weight: int,
    title: str,
    detail: str,
    evidence_refs: list[str],
) -> dict[str, Any]:
    return {
        "code": code,
        "severity": severity,
        "weight": weight,
        "title": title,
        "detail": detail,
        "evidenceRefs": evidence_refs,
    }


def _tools(trace: RunTrace) -> list[dict[str, Any]]:
    result = []
    for index, tool in enumerate(trace.tool_invocations):
        if not isinstance(tool, dict):
            continue
        tool_id = _tool_id(tool, index)
        status = str(tool.get("status") or "UNKNOWN").upper()
        failure = _tool_failure_signal(tool)
        tool_name = str(tool.get("toolName") or "unknown")
        result.append(
            {
                "id": tool_id,
                "toolCallId": tool.get("toolCallId"),
                "toolName": tool_name,
                "status": status,
                "failed": failure is not None,
                "failureSignal": failure,
                "errorMsg": str(tool.get("errorMsg") or ""),
                "durationMs": tool.get("durationMs"),
                "stepNo": tool.get("stepNo"),
                "dispatchIndex": tool.get("dispatchIndex"),
                "llmInvocationId": tool.get("llmInvocationId"),
                "subAgentId": tool.get("subAgentId"),
                "subAgentType": tool.get("subAgentType"),
                "argumentBrief": _argument_brief(
                    tool_name,
                    tool.get("inputJson"),
                ),
                "argumentsPreview": json_preview(tool.get("inputJson")),
                "inputPreview": json_preview(tool.get("inputJson")),
                "resultPreview": json_preview(
                    tool.get("toolResult")
                    if tool.get("toolResult") is not None
                    else tool.get("llmObservation")
                ),
                "artifactCount": tool.get("artifactCount"),
                "evidenceRef": f"tool:{tool_id}",
            }
        )
    return result


def _artifact_items(trace: RunTrace) -> list[dict[str, Any]]:
    items = []
    for index, artifact in enumerate(trace.artifacts):
        if not isinstance(artifact, dict):
            continue
        artifact_id = str(
            artifact.get("artifactId")
            or artifact.get("id")
            or f"artifact-{index + 1}"
        )
        items.append(
            {
                "id": artifact_id,
                "relativeRef": artifact.get("relativeRef"),
                "artifactRole": artifact.get("artifactRole"),
                "visibility": artifact.get("visibility"),
                "sourceType": artifact.get("sourceType"),
                "mimeType": artifact.get("mimeType"),
                "fileSize": artifact.get("fileSize"),
                "fileHash": artifact.get("fileHash"),
                "evidenceRef": f"artifact:{artifact_id}",
            }
        )
    return items


def _argument_brief(tool_name: str, value: Any) -> str:
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except (TypeError, ValueError):
            return value.strip()
    if isinstance(value, list):
        return " ".join(str(item) for item in value[:3] if str(item).strip())
    if not isinstance(value, dict):
        return str(value or "").strip()

    if tool_name == "browser" and isinstance(value.get("args"), list):
        return " ".join(
            str(item) for item in value["args"][:3] if str(item).strip()
        )
    if tool_name == "host_cli" and value.get("tool"):
        args = value.get("args")
        suffix = (
            " ".join(str(item) for item in args[:3])
            if isinstance(args, list)
            else ""
        )
        return " ".join(item for item in (str(value["tool"]), suffix) if item)
    for key in ("command", "cmd", "path", "url", "query", "name", "tool"):
        item = value.get(key)
        if item is not None and str(item).strip():
            return str(item)
    return json_preview(value, 180).replace("\n", " ")


def _step_tool_calls(step: Any) -> list[dict[str, Any]]:
    results = {
        item.source_id: item
        for item in step.evidence
        if item.kind == "tool_result" and item.source_id
    }
    calls = []
    for evidence in step.evidence:
        if evidence.kind != "tool_call":
            continue
        payload = evidence.payload or {}
        result = results.get(evidence.source_id)
        result_payload = (result.payload or {}) if result is not None else {}
        tool_name = str(payload.get("toolName") or "unknown")
        status = str(
            result.status
            if result is not None and result.status
            else "REQUESTED"
        ).upper()
        error_msg = str(result_payload.get("errorMsg") or "")
        result_content = result_payload.get("content")
        failure = (
            _tool_failure_signal(
                {
                    "status": status,
                    "errorMsg": error_msg,
                    "toolResult": result_content,
                }
            )
            if result is not None
            else None
        )
        calls.append(
            {
                "toolCallId": evidence.source_id,
                "toolName": tool_name,
                "argumentBrief": _argument_brief(
                    tool_name,
                    payload.get("arguments"),
                ),
                "argumentsPreview": json_preview(payload.get("arguments")),
                "status": status,
                "failed": failure is not None,
                "failureSignal": failure,
                "errorMsg": error_msg,
                "durationMs": result_payload.get("durationMs"),
                "resultPreview": json_preview(result_content),
            }
        )
    return calls


def _step_presentation(step: Any) -> tuple[str, str, str, list[dict[str, Any]]]:
    calls = _step_tool_calls(step)
    content = str(step.content or "").strip()
    tool_names = list(dict.fromkeys(item["toolName"] for item in calls))
    if content:
        summary = content
    elif tool_names:
        summary = f"调用工具：{'、'.join(tool_names)}"
    else:
        has_llm = any(item.kind == "llm" for item in step.evidence)
        summary = (
            "模型完成了这次决策，但没有保存可展示的文本。"
            if has_llm
            else "执行了一个步骤。"
        )

    raw_status = str(step.status or "").upper()
    if calls:
        if any(item["failed"] for item in calls):
            display_status = "FAILED"
        elif all(item["status"] != "REQUESTED" for item in calls):
            display_status = "SUCCESS"
        else:
            display_status = "REQUESTED"
        kind = "tool"
    else:
        display_status = (
            "COMPLETED"
            if raw_status in {"", "OBSERVED", "UNKNOWN"} and content
            else "MODEL_STEP"
            if raw_status in {"", "OBSERVED", "UNKNOWN"}
            else raw_status
        )
        kind = "model"
    return summary, display_status, kind, calls


def _timeline(trace: RunTrace, view: Any) -> dict[str, Any]:
    steps = []
    for step in view.steps:
        summary, display_status, kind, tool_calls = _step_presentation(step)
        steps.append(
            {
                "id": step.step_id,
                "ordinal": step.ordinal,
                "messageIndex": step.message_index,
                "content": step.content,
                "summary": summary,
                "status": display_status,
                "rawStatus": step.status,
                "kind": kind,
                "visible": bool(str(step.content or "").strip()),
                "toolCalls": tool_calls,
                "evidence": [
                    {
                        "id": item.evidence_id,
                        "kind": item.kind,
                        "sourceId": item.source_id,
                        "status": item.status,
                        "payload": item.payload,
                    }
                    for item in step.evidence
                ],
                "evidenceRef": f"step:{step.step_id}",
            }
        )
    return {"steps": steps, "tools": _tools(trace)}


def _repeated_and_retry_findings(
    trace: RunTrace,
) -> tuple[list[dict[str, Any]], int]:
    tools = [item for item in trace.tool_invocations if isinstance(item, dict)]
    findings: list[dict[str, Any]] = []
    grouped: dict[tuple[str, str], list[int]] = {}
    for index, tool in enumerate(tools):
        key = (
            str(tool.get("toolName") or ""),
            _canonical_args(tool.get("inputJson")),
        )
        grouped.setdefault(key, []).append(index)

    repeated_excess = 0
    for (tool_name, _), indexes in grouped.items():
        if len(indexes) < 2:
            continue
        repeated_excess += len(indexes) - 1
        refs = [f"tool:{_tool_id(tools[index], index)}" for index in indexes]
        findings.append(
            _finding(
                "repeated_identical_call",
                "warning",
                40,
                "完全相同的工具调用重复执行",
                f"{tool_name} 使用相同参数调用了 {len(indexes)} 次。",
                refs,
            )
        )

    retry_refs: list[str] = []
    for index, tool in enumerate(tools):
        if _tool_failure_signal(tool) is None:
            continue
        name = str(tool.get("toolName") or "")
        args = _canonical_args(tool.get("inputJson"))
        for later in range(index + 1, len(tools)):
            later_tool = tools[later]
            if (
                str(later_tool.get("toolName") or "") == name
                and _canonical_args(later_tool.get("inputJson")) == args
            ):
                retry_refs.extend(
                    [
                        f"tool:{_tool_id(tool, index)}",
                        f"tool:{_tool_id(later_tool, later)}",
                    ]
                )
    if retry_refs:
        findings.append(
            _finding(
                "retry_after_failure",
                "warning",
                55,
                "失败后使用相同参数重试",
                "工具失败后又使用相同工具和参数重复调用，未先诊断失败原因。",
                list(dict.fromkeys(retry_refs)),
            )
        )
    return findings, repeated_excess


def _completion(
    statuses: list[str],
    findings: list[dict[str, Any]],
    criteria: str | None,
) -> dict[str, Any]:
    if any(status in {"FAILED", "TIMEOUT"} for status in statuses):
        return {
            "conclusion": "incomplete",
            "basis": "execution",
            "advisory": False,
            "summary": "执行没有成功结束，不能判定任务完成。",
        }
    if any(status == "STOPPED" for status in statuses):
        return {
            "conclusion": "partial",
            "basis": "execution",
            "advisory": False,
            "summary": "执行被停止，可能只完成了部分工作。",
        }
    if criteria and criteria.strip():
        return {
            "conclusion": "unknown",
            "basis": "explicit",
            "advisory": True,
            "summary": "已记录验收标准，等待深度复盘逐条判断。",
        }
    if any(item["code"] == "empty_final_answer" for item in findings):
        return {
            "conclusion": "unknown",
            "basis": "none",
            "advisory": True,
            "summary": "执行成功，但最终回答为空，完成度暂时无法判断。",
        }
    return {
        "conclusion": "unknown",
        "basis": "none",
        "advisory": True,
        "summary": "执行成功；当前没有明确验收标准，任务完成度等待深度复盘。",
    }


def _run_report(
    trace: RunTrace,
    *,
    process_view: Any,
    metrics: dict[str, Any],
    source: str,
    criteria: str | None,
) -> dict[str, Any]:
    status = trace.status.upper()
    tools = _tools(trace)
    failed_tools = [item for item in tools if item["failed"]]
    repeated_findings, repeated_count = _repeated_and_retry_findings(trace)
    findings: list[dict[str, Any]] = []
    if status == "FAILED":
        findings.append(
            _finding(
                "run_failed",
                "blocker",
                100,
                "Run 执行失败",
                "执行以 FAILED 结束。",
                [f"run:{trace.request_id}"],
            )
        )
    elif status == "TIMEOUT":
        findings.append(
            _finding(
                "run_timeout",
                "blocker",
                95,
                "Run 执行超时",
                "执行以 TIMEOUT 结束。",
                [f"run:{trace.request_id}"],
            )
        )
    elif status == "STOPPED":
        findings.append(
            _finding(
                "run_stopped",
                "warning",
                40,
                "Run 被停止",
                "执行被用户或上层流程停止。",
                [f"run:{trace.request_id}"],
            )
        )
    if status == "SUCCESS" and not trace.final_text.strip():
        findings.append(
            _finding(
                "empty_final_answer",
                "warning",
                70,
                "最终回答为空",
                "执行成功结束，但没有记录最终回答。",
                ["final_text"],
            )
        )
    if failed_tools:
        recovered = set(metrics.get("tools", {}).get("recoveredTools") or [])
        for item in failed_tools:
            recovered_label = "后续已恢复" if item["toolName"] in recovered else "后续未恢复"
            findings.append(
                _finding(
                    "tool_failure",
                    "warning",
                    60,
                    f"{item['toolName']} 调用失败",
                    f"{item['errorMsg'] or item['failureSignal'] or '工具返回失败'}；{recovered_label}。",
                    [item["evidenceRef"]],
                )
            )
        if not recovered:
            findings.append(
                _finding(
                    "no_recovery_after_failure",
                    "warning",
                    65,
                    "工具失败后没有恢复",
                    "失败工具没有出现后续成功调用。",
                    [item["evidenceRef"] for item in failed_tools],
                )
            )
    findings.extend(repeated_findings)
    risk_score = sum(int(item["weight"]) for item in findings)
    artifacts = _artifact_items(trace)
    timeline = _timeline(trace, process_view) if process_view is not None else {
        "steps": [],
        "tools": tools,
    }
    limitations = list(metrics.get("limitations") or [])
    if source == "ledger-reconstructed":
        limitations.append("轨迹从 Execution Ledger 重建，缺少部分原始对话上下文。")
    completion = _completion([status], findings, criteria)
    return {
        "runId": trace.run_uid or trace.request_id,
        "requestId": trace.request_id,
        "sessionId": str((trace.raw.get("run") or {}).get("sessionId") or ""),
        "status": status,
        "finalText": trace.final_text,
        "traceSha256": _sha256(trace.raw),
        "execution": {
            "status": status,
            "durationMs": trace.duration_ms,
            "firstTokenMs": trace.first_token_ms(),
            "totalTokens": trace.total_tokens,
        },
        "completion": completion,
        "metrics": {
            "tokens": metrics.get("tokens") or {},
            "cache": metrics.get("cache") or {},
            "steps": metrics.get("steps") or {},
            "tools": metrics.get("tools") or {},
            "artifactCount": len(artifacts),
            "subagentCount": len(
                {
                    str(item.get("subAgentId"))
                    for item in trace.tool_invocations
                    if isinstance(item, dict) and item.get("subAgentId")
                }
            ),
            "repeatedCallExcess": repeated_count,
            "riskScore": risk_score,
        },
        "findings": findings,
        "timeline": timeline,
        "artifacts": artifacts,
        "limitations": list(dict.fromkeys(limitations)),
    }


def build_fast_report(
    bundle: dict[str, Any],
    *,
    criteria: str | None = None,
) -> dict[str, Any]:
    runs = []
    for item in bundle.get("runs") or []:
        trace_payload = item.get("trace") if isinstance(item, dict) else None
        if not isinstance(trace_payload, dict):
            continue
        trace = RunTrace.from_dict(trace_payload)
        try:
            process_view, source = project_quality_view(trace_payload)
            metrics = deterministic_metrics(trace, process_view)
        except Exception as exc:  # noqa: BLE001 - a trace can be evidence-poor
            process_view = None
            source = "unavailable"
            metrics = {
                "runStatus": trace.status,
                "tokens": {"total": trace.total_tokens},
                "tools": {
                    "callCount": len(trace.tool_invocations),
                    "failureCount": sum(
                        1
                        for tool in trace.tool_invocations
                        if isinstance(tool, dict)
                        and _tool_failure_signal(tool) is not None
                    ),
                    "recoveredTools": [],
                    "repeatedCalls": [],
                },
                "limitations": [f"ProcessView 构建失败: {type(exc).__name__}"],
            }
        runs.append(
            _run_report(
                trace,
                process_view=process_view,
                metrics=metrics,
                source=source,
                criteria=criteria,
            )
        )
    if not runs:
        raise ValueError("Trace Bundle 中没有可复盘的 Run")

    statuses = [run["status"] for run in runs]
    severity = max(statuses, key=lambda item: _STATUS_ORDER.get(item, 4))
    all_findings = [
        {
            **finding,
            "runRequestId": run["requestId"],
        }
        for run in runs
        for finding in run["findings"]
    ]
    completion = _completion(statuses, all_findings, criteria)
    artifacts = [item for run in runs for item in run["artifacts"]]
    limitations = list(
        dict.fromkeys(
            limitation
            for run in runs
            for limitation in run["limitations"]
        )
    )
    return {
        "schemaVersion": "1.0",
        "subject": {
            "sessionId": runs[0].get("sessionId"),
            "requestIds": [run["requestId"] for run in runs],
            "runCount": len(runs),
            "criteria": criteria,
        },
        "execution": {
            "status": severity,
            "durationMs": sum(
                int(run["execution"].get("durationMs") or 0) for run in runs
            ),
            "totalTokens": sum(
                int(run["execution"].get("totalTokens") or 0) for run in runs
            ),
            "toolCount": sum(
                int(run["metrics"]["tools"].get("callCount") or 0)
                for run in runs
            ),
            "failedToolCount": sum(
                int(run["metrics"]["tools"].get("failureCount") or 0)
                for run in runs
            ),
            "artifactCount": len(artifacts),
        },
        "completion": completion,
        "findings": all_findings,
        "metrics": {
            "riskScore": sum(int(item["weight"]) for item in all_findings),
            "repeatedCallExcess": sum(
                int(run["metrics"].get("repeatedCallExcess") or 0)
                for run in runs
            ),
            "runStatusCounts": dict(Counter(statuses)),
        },
        "runs": runs,
        "artifacts": artifacts,
        "limitations": limitations,
    }
