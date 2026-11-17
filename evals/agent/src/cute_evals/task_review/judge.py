from __future__ import annotations

from dataclasses import replace
from typing import Any

from ..models import RunTrace
from ..process_view import ProcessViewError, project_process_view
from ..semantic_judge.config import SemanticJudgeConfig
from ..semantic_judge.evidence import build_packet
from ..semantic_judge.rubrics import get_rubric
from ..semantic_judge.runner import judge_packet
from .diagnostics import (
    build_diagnostic_summary,
    build_diagnostics,
    build_event_sequence,
)
from .models import TaskReviewSettings
from .narrative import generate_narrative

DIMENSION_LABELS = {
    "task_completion": "任务完成度",
    "constraint_adherence": "指令遵循",
    "tool_selection": "工具选择",
    "tool_argument_correctness": "工具参数正确性",
    "error_recovery": "错误恢复",
    "diagnosis_quality": "Agent 诊断质量",
    "planning_adaptation": "规划与策略调整",
    "context_tracking": "上下文与状态跟踪",
    "stopping_discipline": "停止时机",
    "execution_efficiency": "执行效率",
    "result_verification": "结果验证",
    "artifact_quality": "产物质量",
    "answer_honesty": "回答诚实性",
}

SUGGESTIONS = {
    "task_completion": "对照用户原始要求逐条确认交付物，不要只依据执行状态判断完成。",
    "constraint_adherence": "把数量、范围、禁止项和危险操作约束显式转成执行前检查。",
    "tool_selection": "优先选择能直接获得证据的工具，避免为了尝试而反复调用。",
    "tool_argument_correctness": "调用前校验 JSON、必填字段、选择器、路径和 URL，不要提交编造的参数。",
    "error_recovery": "工具失败后先读取错误信息，再决定是否换工具或重试。",
    "diagnosis_quality": "把工具错误归因到参数、合约、运行时或环境后，再做下一步决策。",
    "planning_adaptation": "新证据与旧计划冲突时，先更新计划再继续批量执行。",
    "context_tracking": "明确区分当前 Request、历史轮次和用户最新纠正。",
    "stopping_discipline": "完成并验证后及时停止；证据不足时不要提前结束。",
    "execution_efficiency": "合并相同目标的搜索和读取，减少低价值工具调用。",
    "result_verification": "生成文件或回答后，至少执行一次读取、检查或测试验证。",
    "artifact_quality": "生成产物后读取实际内容，检查完整性、相关性和格式。",
    "answer_honesty": "明确区分已完成、未完成和未验证的内容。",
}


def _combined_trace(bundle: dict[str, Any], job_id: str) -> tuple[RunTrace, list[dict[str, str]]]:
    conversations: list[dict[str, Any]] = []
    tools: list[dict[str, Any]] = []
    artifacts: list[dict[str, Any]] = []
    queries: list[str] = []
    finals: list[str] = []
    run_refs: list[dict[str, str]] = []
    total_tokens = 0
    duration_ms = 0
    for item in bundle.get("runs") or []:
        trace_payload = item.get("trace") if isinstance(item, dict) else None
        if not isinstance(trace_payload, dict):
            continue
        request_id = str((trace_payload.get("run") or {}).get("requestId") or "")
        run = RunTrace.from_dict(trace_payload)
        queries.append(run.raw.get("run", {}).get("queryText") or "")
        try:
            scoped_view = project_process_view(run)
        except ProcessViewError:
            scoped_view = None
        final_text = (
            scoped_view.final_text
            if scoped_view is not None
            else run.final_text
        )
        finals.append(f"### Run {request_id}\n{final_text}")
        total_tokens += int(run.total_tokens or 0)
        duration_ms += int(run.duration_ms or 0)
        messages = (
            [
                {
                    "messageId": message.source_id,
                    "role": message.role,
                    "content": message.content,
                    "toolCallId": message.tool_call_id,
                    "toolCalls": message.tool_calls,
                    "turnId": message.metadata.get("turnId"),
                }
                for message in scoped_view.messages
            ]
            if scoped_view is not None
            else run.conversation
        )
        for index, message in enumerate(messages):
            if not isinstance(message, dict):
                continue
            copy = dict(message)
            original_id = str(message.get("messageId") or message.get("id") or index)
            copy["messageId"] = f"{request_id}:{original_id}"
            conversations.append(copy)
        for index, tool in enumerate(run.tool_invocations):
            if not isinstance(tool, dict):
                continue
            copy = dict(tool)
            original_id = str(tool.get("toolCallId") or tool.get("id") or index)
            copy["toolCallId"] = f"{request_id}:{original_id}"
            tools.append(copy)
        artifacts.extend(run.artifacts)
        run_refs.append({"requestId": request_id, "runId": run.run_uid or request_id})
    combined = RunTrace(
        case_id=job_id,
        request_id=job_id,
        status=str((bundle.get("runs") or [{}])[0].get("trace", {}).get("run", {}).get("status") or "UNKNOWN"),
        final_text="\n\n".join(item for item in finals if item.strip()),
        total_tokens=total_tokens or None,
        duration_ms=duration_ms or None,
        run_uid=job_id,
        user_id_hash=None,
        llm_invocations=[],
        tool_invocations=tools,
        artifacts=artifacts,
        raw={"run": {"requestId": job_id, "status": "UNKNOWN"}},
        conversation=conversations,
        conversation_source="task-review-bundle",
    )
    return combined, run_refs


def _queries(bundle: dict[str, Any]) -> list[str]:
    return [
        str((run.get("trace") or {}).get("run", {}).get("queryText") or "")
        for run in bundle.get("runs") or []
        if isinstance(run, dict)
        and str(
            (run.get("trace") or {}).get("run", {}).get("queryText") or ""
        ).strip()
    ]


def _event_sequence(bundle: dict[str, Any]) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for item in bundle.get("runs") or []:
        trace_payload = item.get("trace") if isinstance(item, dict) else None
        if not isinstance(trace_payload, dict):
            continue
        trace = RunTrace.from_dict(trace_payload)
        request_id = trace.request_id or str(item.get("requestId") or "")
        for event in build_event_sequence(trace, request_id=request_id):
            events.append({**event, "seq": len(events) + 1})
    return events


def _merge_failure_attributions(
    deterministic: list[dict[str, Any]],
    reviewed: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    merged = {str(item.get("attributionId")): dict(item) for item in deterministic}
    order = [str(item.get("attributionId")) for item in deterministic]
    for item in reviewed:
        attribution_id = str(item.get("attributionId") or "")
        if not attribution_id:
            continue
        base = merged.get(attribution_id, {})
        merged[attribution_id] = {
            **base,
            **item,
            "source": "deterministic+judge" if base else "judge",
            "evidenceRefs": list(
                dict.fromkeys(
                    [
                        *(base.get("evidenceRefs") or []),
                        *(item.get("evidenceRefs") or []),
                    ]
                )
            ),
        }
        if attribution_id not in order:
            order.append(attribution_id)
    return [merged[item] for item in order if item in merged]


def _level(score: float | None) -> str:
    if score is None:
        return "unknown"
    if score >= 4.0:
        return "high"
    if score >= 2.5:
        return "medium"
    return "low"


def _completion_from_judge(
    fast: dict[str, Any],
    verdict: Any,
    criteria: str | None,
) -> dict[str, Any]:
    base = dict(fast.get("completion") or {})
    if base.get("conclusion") == "incomplete":
        return base
    task_completion = next(
        (item for item in verdict.dimensions if item.id == "task_completion"),
        None,
    )
    if verdict.status != "verified" or task_completion is None:
        return base
    score = task_completion.score
    conclusion = "completed" if score >= 4.0 else "partial" if score >= 2.5 else "incomplete"
    return {
        "conclusion": conclusion,
        "basis": "explicit" if criteria and criteria.strip() else "inferred",
        "advisory": not bool(criteria and criteria.strip()),
        "summary": task_completion.reason or base.get("summary") or "",
    }


def run_task_review_judge(
    bundle: dict[str, Any],
    fast: dict[str, Any],
    *,
    settings: TaskReviewSettings,
    job_id: str,
) -> dict[str, Any]:
    config = replace(
        settings.judge,
        output_language="Simplified Chinese",
    )
    rubric = get_rubric("task-review.v2")
    trace, run_refs = _combined_trace(bundle, job_id)
    criteria = (fast.get("subject") or {}).get("criteria")
    deterministic_attributions, diagnostic_summary, contracts = build_diagnostics(
        bundle
    )
    query = "\n\n".join(_queries(bundle))
    packet = build_packet(
        trace,
        case_id=job_id,
        agent="cute-agent",
        attempt=1,
        query=query,
        expected_contract=str(criteria) if criteria else None,
        risk=None,
        deterministic_scores=[
            {
                "name": item.get("code"),
                "title": item.get("title"),
                "severity": item.get("severity"),
                "value": item.get("detail"),
            }
            for item in fast.get("findings") or []
        ]
        + [
            {"name": name, "severity": "info", "value": value}
            for name, value in (
                (
                    "run_status",
                    (fast.get("execution") or {}).get("status"),
                ),
                (
                    "duration_ms",
                    (fast.get("execution") or {}).get("durationMs"),
                ),
                (
                    "total_tokens",
                    (fast.get("execution") or {}).get("totalTokens"),
                ),
                (
                    "tool_count",
                    (fast.get("execution") or {}).get("toolCount"),
                ),
                (
                    "failed_tool_count",
                    (fast.get("execution") or {}).get("failedToolCount"),
                ),
                (
                    "risk_score",
                    (fast.get("metrics") or {}).get("riskScore"),
                ),
                (
                    "failure_count",
                    diagnostic_summary.get("totalFailures"),
                ),
                (
                    "parameter_issue_count",
                    diagnostic_summary.get("parameterIssueCount"),
                ),
                (
                    "unrecovered_failure_count",
                    diagnostic_summary.get("unrecoveredCount"),
                ),
            )
            if value is not None
        ],
        workspace=None,
        event_sequence=_event_sequence(bundle),
        tool_contracts=contracts,
        artifacts=list(fast.get("artifacts") or []),
        failure_candidates=deterministic_attributions,
        metadata={
            "taskType": "task-review",
            "runRefs": run_refs,
            "judgePolicy": "advisory-only",
            "rubricId": rubric.id,
            "diagnosticSummary": diagnostic_summary,
        },
        max_evidence_chars=config.max_evidence_chars,
        max_tool_result_chars=config.max_tool_result_chars,
        max_file_chars=config.max_file_chars,
    )
    verdict = judge_packet(
        packet,
        rubric,
        config,
        cache_dir=settings.review_home / "cache",
    )
    failure_attributions = _merge_failure_attributions(
        deterministic_attributions,
        list(getattr(verdict, "failure_attributions", []) or []),
    )
    diagnosis_quality = next(
        (
            item.score
            for item in verdict.dimensions
            if item.id == "diagnosis_quality"
        ),
        None,
    )
    inferred_mismatch_count = (
        1
        if diagnosis_quality is not None
        and diagnosis_quality < 3.0
        and failure_attributions
        else 0
    )
    judge_summary = getattr(verdict, "diagnostic_summary", None)
    judge_mismatch = (
        judge_summary.get("diagnosisMismatchCount")
        if isinstance(judge_summary, dict)
        else None
    )
    diagnosis_mismatch_count = (
        max(0, min(int(judge_mismatch), len(failure_attributions)))
        if isinstance(judge_mismatch, int)
        and not isinstance(judge_mismatch, bool)
        else inferred_mismatch_count
    )
    diagnostic_summary = build_diagnostic_summary(
        failure_attributions,
        diagnosis_mismatch_count=diagnosis_mismatch_count,
    )
    dimensions = [
        {
            "id": item.id,
            "label": DIMENSION_LABELS.get(item.id, item.id),
            "score": item.score,
            "level": _level(item.score),
            "passed": item.passed,
            "applicable": getattr(item, "applicable", True),
            "notApplicableReason": getattr(item, "not_applicable_reason", ""),
            "diagnosticRefs": getattr(item, "diagnostic_refs", []),
            "reason": item.reason,
            "evidence": [evidence.to_dict() for evidence in item.evidence],
        }
        for item in verdict.dimensions
    ]
    semantic_findings = [
        {
            "code": f"semantic_{item['id']}",
            "severity": "warning",
            "title": f"{item['label']}偏弱",
            "detail": item["reason"],
            "evidence": item["evidence"],
        }
        for item in dimensions
        if item["applicable"] and item["score"] < 3.0
    ]
    verified = verdict.status == "verified"
    error = verdict.error
    limitations = list(verdict.limitations)
    if not verified:
        error_kind = str((verdict.error or {}).get("kind") or "unverified")
        error_detail = str(
            (verdict.error or {}).get("message") or "unknown validation error"
        )
        error = {
            "kind": error_kind,
            "message": (
                f"AI 深度复盘输出校验失败：{error_detail}"
                if error_kind == "invalid_judge_output"
                else error_detail
            ),
        }
        limitations = ["AI 深度复盘未生成可验证结论。"]
    suggestions = [
        {
            "dimension": item["id"],
            "title": item["label"],
            "detail": SUGGESTIONS[item["id"]],
        }
        for item in dimensions
        if item["applicable"]
        and item["score"] < 3.5
        and item["id"] in SUGGESTIONS
    ]
    narrative = generate_narrative(
        fast,
        query=query,
        dimensions=dimensions,
        failure_attributions=failure_attributions,
        diagnostic_summary=diagnostic_summary,
        config=config if verified else SemanticJudgeConfig(),
        provider=None,
    )
    return {
        "status": verdict.status,
        "summary": (
            verdict.summary
            if verified
            else "AI 深度复盘没有生成可验证结论。"
        ),
        "confidence": verdict.confidence,
        "weightedScore": verdict.weighted_score,
        "judgeModel": verdict.judge_model,
        "promptHash": verdict.prompt_hash,
        "promptInjectionSuspected": verdict.prompt_injection_suspected,
        "dimensions": dimensions,
        "findings": semantic_findings,
        "suggestions": suggestions,
        "completion": _completion_from_judge(fast, verdict, str(criteria) if criteria else None),
        "rubricId": rubric.id,
        "failureAttributions": failure_attributions,
        "diagnosticSummary": diagnostic_summary,
        "diagnostics": {
            "failureAttributions": failure_attributions,
            "diagnosticSummary": diagnostic_summary,
            "toolContracts": contracts,
        },
        "narrative": narrative,
        "narrativeStatus": narrative.get("status", "fact_only"),
        "limitations": limitations,
        "error": error,
        "advisory": True,
    }
