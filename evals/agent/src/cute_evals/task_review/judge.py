from __future__ import annotations

from dataclasses import replace
from typing import Any

from ..models import RunTrace
from ..semantic_judge.evidence import build_packet
from ..semantic_judge.rubrics import get_rubric
from ..semantic_judge.runner import judge_packet
from .models import TaskReviewSettings

DIMENSION_LABELS = {
    "task_completion": "任务完成度",
    "tool_selection": "工具选择",
    "error_recovery": "错误恢复",
    "execution_efficiency": "执行效率",
    "result_verification": "结果验证",
    "answer_honesty": "回答诚实性",
}

SUGGESTIONS = {
    "task_completion": "对照用户原始要求逐条确认交付物，不要只依据执行状态判断完成。",
    "tool_selection": "优先选择能直接获得证据的工具，避免为了尝试而反复调用。",
    "error_recovery": "工具失败后先读取错误信息，再决定是否换工具或重试。",
    "execution_efficiency": "合并相同目标的搜索和读取，减少低价值工具调用。",
    "result_verification": "生成文件或回答后，至少执行一次读取、检查或测试验证。",
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
        finals.append(f"### Run {request_id}\n{run.final_text}")
        total_tokens += int(run.total_tokens or 0)
        duration_ms += int(run.duration_ms or 0)
        for index, message in enumerate(run.conversation):
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
    rubric = get_rubric("task-review.v1")
    trace, run_refs = _combined_trace(bundle, job_id)
    criteria = (fast.get("subject") or {}).get("criteria")
    packet = build_packet(
        trace,
        case_id=job_id,
        agent="cute-agent",
        attempt=1,
        query="\n\n".join(
            item for item in (
                str((run.get("trace") or {}).get("run", {}).get("queryText") or "")
                for run in bundle.get("runs") or []
                if isinstance(run, dict)
            )
            if item.strip()
        ),
        expected_contract=str(criteria) if criteria else None,
        risk=None,
        deterministic_scores=[
            {
                "name": item.get("code"),
                "severity": item.get("severity"),
                "value": item.get("detail"),
            }
            for item in fast.get("findings") or []
        ],
        workspace=None,
        metadata={
            "taskType": "task-review",
            "runRefs": run_refs,
            "judgePolicy": "advisory-only",
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
    dimensions = [
        {
            "id": item.id,
            "label": DIMENSION_LABELS.get(item.id, item.id),
            "score": item.score,
            "level": _level(item.score),
            "passed": item.passed,
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
        if item["score"] < 3.0
    ]
    verified = verdict.status == "verified"
    error = verdict.error
    limitations = list(verdict.limitations)
    if not verified:
        error = {
            "kind": (verdict.error or {}).get("kind", "unverified"),
            "message": "AI 复盘模型没有返回可验证的中文结论，请重新复盘。",
        }
        limitations = ["AI 深度复盘未生成可验证结论。"]
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
        "suggestions": [
            {
                "dimension": item["id"],
                "title": item["label"],
                "detail": SUGGESTIONS[item["id"]],
            }
            for item in dimensions
            if item["score"] < 3.5 and item["id"] in SUGGESTIONS
        ],
        "completion": _completion_from_judge(fast, verdict, str(criteria) if criteria else None),
        "limitations": limitations,
        "error": error,
        "advisory": True,
    }
