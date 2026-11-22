from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any

import jsonschema

from ..semantic_judge.config import SemanticJudgeConfig
from ..semantic_judge.contract import extract_json_object
from ..semantic_judge.providers import SemanticJudgeProvider
from .action_compiler import build_developer_actions


def _strings(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


def _tool_story(fast: dict[str, Any]) -> list[dict[str, Any]]:
    counts: dict[str, Counter[str]] = {}
    for run in fast.get("runs") or []:
        for tool in (run.get("timeline") or {}).get("tools") or []:
            name = str(tool.get("toolName") or "unknown")
            counter = counts.setdefault(name, Counter())
            counter["calls"] += 1
            if tool.get("failed"):
                counter["failures"] += 1
    return [
        {
            "toolName": name,
            "purpose": "执行任务所需的操作并获取观察结果。",
            "calls": counter["calls"],
            "failures": counter["failures"],
            "assessment": (
                "存在失败调用，需要结合归因账本判断是参数、合约、运行时还是环境问题。"
                if counter["failures"]
                else "本次调用未记录确定性失败。"
            ),
        }
        for name, counter in sorted(counts.items())
    ]


def _key_events(fast: dict[str, Any]) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for run in fast.get("runs") or []:
        request_id = str(run.get("requestId") or "")
        for step in (run.get("timeline") or {}).get("steps") or []:
            content = str(step.get("content") or "").strip()
            if not content:
                continue
            events.append(
                {
                    "kind": "assistant_message",
                    "requestId": request_id,
                    "step": step.get("ordinal"),
                    "content": content[:1200],
                }
            )
        tools = list((run.get("timeline") or {}).get("tools") or [])
        selected = {
            str(tool.get("id") or index)
            for index, tool in enumerate(tools)
            if tool.get("failed")
            or index < 12
            or index >= max(0, len(tools) - 8)
        }
        for index, tool in enumerate(tools):
            key = str(tool.get("id") or index)
            if key not in selected:
                continue
            events.append(
                {
                    "kind": "tool",
                    "requestId": request_id,
                    "toolCallId": tool.get("id"),
                    "toolName": tool.get("toolName"),
                    "step": tool.get("stepNo"),
                    "status": tool.get("status"),
                    "failed": bool(tool.get("failed")),
                    "argument": str(tool.get("argumentBrief") or "")[:500],
                    "error": str(tool.get("errorMsg") or "")[:800],
                    "durationMs": tool.get("durationMs"),
                }
            )
    return events[:180]


def _fact_narrative(
    fast: dict[str, Any],
    *,
    query: str,
    failure_attributions: list[dict[str, Any]],
    diagnostic_summary: dict[str, Any],
    dimensions: list[dict[str, Any]],
) -> dict[str, Any]:
    completion = fast.get("completion") or {}
    execution = fast.get("execution") or {}
    runs = fast.get("runs") or []
    findings = fast.get("findings") or []
    artifacts = fast.get("artifacts") or []
    layers = diagnostic_summary.get("layerCounts") or {}
    top_layer = max(
        ((name, int(count)) for name, count in layers.items()),
        key=lambda item: item[1],
        default=("UNKNOWN", 0),
    )
    headline = str(completion.get("summary") or "任务执行完成，等待结合轨迹阅读。")
    if top_layer[1] > 0:
        headline += f" 主要失败责任层是 {top_layer[0]}。"
    if str(fast.get("subject", {}).get("scope") or "") == "tree":
        headline = "整棵 Agent 树复盘：" + headline

    execution_narrative = []
    for run in runs:
        tools = (run.get("timeline") or {}).get("tools") or []
        assistant_steps = [
            step
            for step in (run.get("timeline") or {}).get("steps") or []
            if str(step.get("content") or "").strip()
        ]
        tool_names = list(
            dict.fromkeys(str(tool.get("toolName") or "unknown") for tool in tools)
        )
        execution_narrative.append(
            {
                "phase": f"Run {run.get('requestId') or ''}".strip(),
                "summary": (
                    f"记录了 {len(assistant_steps)} 条可见助手回复、"
                    f"{len(tools)} 次工具调用"
                    + (
                        f"，主要工具：{'、'.join(tool_names)}。"
                        if tool_names
                        else "。"
                    )
                ),
                "outcome": str(run.get("status") or "UNKNOWN"),
                "evidenceRefs": [
                    f"tool:{tool.get('id')}"
                    for tool in tools[:8]
                    if tool.get("id")
                ],
            }
        )

    failure_story = [
        {
            "title": f"{item.get('toolName')} 调用失败",
            "primaryLayer": str(item.get("primaryLayer") or "UNKNOWN"),
            "detail": str(item.get("reason") or ""),
            "recovered": bool(item.get("recovered")),
            "evidenceRefs": list(item.get("evidenceRefs") or []),
        }
        for item in failure_attributions
    ]
    what_failed = [
        str(item.get("title") or item.get("code") or "确定性异常")
        for item in findings
    ]
    better = [
        {
            "title": "优先处理已确认的责任层",
            "detail": (
                f"本次最主要的责任层是 {top_layer[0]}，"
                "应先按证据修正该层，再重新执行同类任务。"
            ),
            "tradeoff": "需要补充验证样本，但能避免把 Harness 或环境问题误判为模型能力问题。",
        }
    ] if top_layer[1] else []
    developer_actions = build_developer_actions(
        failure_attributions,
        fast=fast,
    )

    applicable_dimensions = [
        item for item in dimensions if item.get("applicable", True)
    ]
    what_worked = [
        f"{item.get('label')}达到 {item.get('level')} 水平。"
        for item in applicable_dimensions
        if item.get("score") is not None and float(item["score"]) >= 4.0
    ]
    if str(execution.get("status") or "").upper() == "SUCCESS" and not what_worked:
        what_worked.append("执行以 SUCCESS 结束，未出现运行级阻断。")

    return {
        "schemaVersion": "1.0",
        "status": "fact_only",
        "headline": headline,
        "executiveSummary": [
            f"执行状态：{execution.get('status') or 'UNKNOWN'}。",
            f"共 {execution.get('toolCount') or 0} 次工具调用，"
            f"{execution.get('failedToolCount') or 0} 次失败，"
            f"生成 {len(artifacts)} 个产物。",
            (
                f"确定性归因覆盖 {diagnostic_summary.get('attributedFailures', 0)} 次失败，"
                f"其中 {diagnostic_summary.get('unrecoveredCount', 0)} 次未恢复。"
            ),
        ],
        "taskUnderstanding": {
            "goal": query or "Trace 中未记录原始用户目标。",
            "constraints": [],
            "acceptanceCriteria": _strings(
                [(fast.get("subject") or {}).get("criteria")]
            ),
        },
        "executionNarrative": execution_narrative,
        "toolStory": _tool_story(fast),
        "failureStory": failure_story,
        "whatWorked": what_worked,
        "whatFailed": what_failed,
        "betterApproach": better,
        "developerActions": developer_actions,
        "verification": {
            "verified": [
                "执行状态、Token、耗时、工具调用、失败调用和产物数量来自 Trace。",
                "失败责任层来自确定性规则，未知证据保留为 UNKNOWN 或 TRACE_GAP。",
            ] + (
                ["会话树结构与父子关系来自 Execution Ledger 会话身份缓存。"]
                if str(fast.get("subject", {}).get("scope") or "") == "tree"
                else []
            ),
            "unverified": _strings(fast.get("limitations")),
        },
        "limitations": _strings(fast.get("limitations")),
    }


def _provider_context(
    fact: dict[str, Any],
    *,
    fast: dict[str, Any],
    dimensions: list[dict[str, Any]],
    failure_attributions: list[dict[str, Any]],
    diagnostic_summary: dict[str, Any],
) -> dict[str, Any]:
    return {
        "facts": fact,
        "execution": fast.get("execution") or {},
        "completion": fast.get("completion") or {},
        "findings": fast.get("findings") or [],
        "artifacts": fast.get("artifacts") or [],
        "dimensions": [
            {
                "id": item.get("id"),
                "label": item.get("label"),
                "score": item.get("score"),
                "level": item.get("level"),
                "applicable": item.get("applicable", True),
                "reason": item.get("reason"),
                "diagnosticRefs": item.get("diagnosticRefs") or [],
            }
            for item in dimensions
        ],
        "failureAttributions": failure_attributions,
        "diagnosticSummary": diagnostic_summary,
        "keyEvents": _key_events(fast),
    }


def _model_prompt() -> str:
    return (
        "你是一名资深 Agent 工程师。请把已经验证的事实整理成一份中文任务复盘报告，"
        "供开发者理解 Agent 的执行过程、失败原因和改进方向。\n"
        "必须遵守：\n"
        "1. 只使用输入中的事实，不得重新评分，不得修改分数、责任层、恢复状态或证据引用。\n"
        "2. 不得猜测模型隐藏推理，不得补造不存在的工具、消息、文件或证据。\n"
        "3. 证据不足时明确写“无法判断”。\n"
        "4. 报告要按执行阶段讲清楚做了什么、遇到什么、如何调整、最终结果和可改进路径。\n"
        "5. 所有内容使用简体中文；JSON 键名、工具名、ID 和证据引用保持原样。\n"
        "6. 只返回一个 JSON 对象，不要返回 Markdown 或解释文字。\n"
        "JSON 结构：\n"
        + json.dumps(
            {
                "headline": "一句话结论",
                "executiveSummary": ["摘要"],
                "taskUnderstanding": {
                    "goal": "用户目标",
                    "constraints": ["明确约束"],
                    "acceptanceCriteria": ["验收标准"],
                },
                "executionNarrative": [
                    {
                        "phase": "阶段名",
                        "summary": "做了什么和结果",
                        "outcome": "completed/partial/incomplete/unknown",
                        "evidenceRefs": ["tool:..."],
                    }
                ],
                "toolStory": [
                    {
                        "toolName": "工具名",
                        "purpose": "用途",
                        "calls": 1,
                        "failures": 0,
                        "assessment": "是否合理",
                    }
                ],
                "failureStory": [
                    {
                        "title": "失败标题",
                        "primaryLayer": "MODEL_ARGUMENT",
                        "detail": "原因与恢复情况",
                        "recovered": True,
                        "evidenceRefs": ["tool:..."],
                    }
                ],
                "whatWorked": ["有证据的优点"],
                "whatFailed": ["有证据的问题"],
                "betterApproach": [
                    {
                        "title": "更优路径",
                        "detail": "具体做法",
                        "tradeoff": "代价或限制",
                    }
                ],
                "developerActions": "由确定性 Action Compiler 生成，模型不得修改",
                "verification": {
                    "verified": ["已验证内容"],
                    "unverified": ["未验证内容"],
                },
                "limitations": ["限制"],
            },
            ensure_ascii=False,
        )
    )


def _normalize_model_report(
    payload: dict[str, Any],
    fallback: dict[str, Any],
    *,
    allowed_evidence_refs: set[str],
) -> dict[str, Any]:
    result = dict(fallback)
    for key in ("headline",):
        if str(payload.get(key) or "").strip():
            result[key] = str(payload[key]).strip()
    result["executiveSummary"] = (
        _strings(payload.get("executiveSummary")) or fallback["executiveSummary"]
    )
    for key in (
        "executionNarrative",
        "betterApproach",
    ):
        value = payload.get(key)
        if isinstance(value, list) and value:
            result[key] = value
    narrative = []
    for item in result["executionNarrative"]:
        if not isinstance(item, dict):
            continue
        narrative.append(
            {
                "phase": str(item.get("phase") or "执行阶段"),
                "summary": str(item.get("summary") or ""),
                "outcome": str(item.get("outcome") or "unknown"),
                "evidenceRefs": [
                    ref
                    for ref in _strings(item.get("evidenceRefs"))
                    if ref in allowed_evidence_refs
                ],
            }
        )
    result["executionNarrative"] = narrative or fallback["executionNarrative"]
    # Developer actions are deterministic facts. Pass 2 may explain the path,
    # but it cannot add, remove, or rewrite repair instructions.
    result["status"] = "verified"
    return result


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Agent 任务复盘报告",
        "",
        "## 一句话结论",
        "",
        report["headline"],
        "",
        "## 执行摘要",
        "",
        *[f"- {item}" for item in report["executiveSummary"]],
        "",
        "## 任务理解与约束",
        "",
        f"**用户目标：** {report['taskUnderstanding']['goal']}",
        "",
        "**明确约束：**",
        "",
        *(
            [f"- {item}" for item in report["taskUnderstanding"]["constraints"]]
            or ["- Trace 中没有记录更多明确约束。"]
        ),
        "",
        "**验收标准：**",
        "",
        *(
            [
                f"- {item}"
                for item in report["taskUnderstanding"]["acceptanceCriteria"]
            ]
            or ["- Trace 中没有记录明确验收标准。"]
        ),
        "",
        "## 执行故事",
        "",
    ]
    for phase in report["executionNarrative"]:
        lines.extend(
            [
                f"### {phase.get('phase') or '执行阶段'}",
                "",
                str(phase.get("summary") or ""),
                "",
                f"结果：{phase.get('outcome') or 'unknown'}",
                "",
            ]
        )
        refs = phase.get("evidenceRefs") or []
        if refs:
            lines.extend([f"证据：`{ref}`" for ref in refs])
            lines.append("")
    lines.extend(["## 工具使用地图", ""])
    for tool in report["toolStory"]:
        lines.append(
            f"- **{tool.get('toolName')}**：{tool.get('calls')} 次调用，"
            f"{tool.get('failures')} 次失败。{tool.get('assessment')}"
        )
    lines.extend(["", "## 失败与责任归因", ""])
    if report["failureStory"]:
        for failure in report["failureStory"]:
            lines.extend(
                [
                    f"### {failure.get('title')}",
                    "",
                    f"- 主责任层：`{failure.get('primaryLayer')}`",
                    f"- 是否恢复：{'是' if failure.get('recovered') else '否'}",
                    f"- 原因：{failure.get('detail')}",
                    "",
                ]
            )
            refs = failure.get("evidenceRefs") or []
            if refs:
                lines.extend([f"证据：`{ref}`" for ref in refs])
                lines.append("")
    else:
        lines.extend(["未记录失败工具调用。", ""])
    lines.extend(["## 做对了什么", ""])
    lines.extend(
        [f"- {item}" for item in report["whatWorked"]]
        or ["- 暂无可验证优点。"]
    )
    lines.extend(["", "## 哪里可以更好", ""])
    for item in report["betterApproach"]:
        lines.append(
            f"- **{item.get('title')}**：{item.get('detail')} "
            f"取舍：{item.get('tradeoff')}"
        )
    if not report["betterApproach"]:
        lines.append("- 当前证据不足以提出更优路径。")
    lines.extend(["", "## 开发者行动项", ""])
    for action in report["developerActions"]:
        problem = action.get("problemDefinition") or {}
        reproduction = action.get("reproduction") or {}
        root = action.get("rootCauseHypothesis") or {}
        frequency = action.get("frequency") or {}
        test_plan = action.get("testPlan") or {}
        lines.extend(
            [
                f"### {action.get('priority')} {action.get('title')}",
                "",
                f"- 工具：`{action.get('toolName')}`",
                f"- 失败类型：`{action.get('failureKind')}`",
                f"- 主责任层：`{action.get('targetLayer')}`",
                f"- 行动范围：`{action.get('actionScope')}`",
                f"- 状态：`{action.get('status')}`",
                f"- 发生次数：{frequency.get('count', 0)}，"
                f"已恢复 {frequency.get('recovered', 0)}，"
                f"未恢复 {frequency.get('unrecovered', 0)}",
                "",
                "**问题定义**",
                "",
                f"- 观测：{problem.get('observed') or '未记录'}",
                f"- 期望：{problem.get('expected') or '未记录'}",
                f"- 影响：{problem.get('impact') or '未记录'}",
                "",
                "**证据**",
                "",
                *(
                    [f"- `{ref}`" for ref in action.get("evidenceRefs") or []]
                    or ["- 当前没有可定位证据，需先补齐 Trace。"]
                ),
                "",
                "**最小复现**",
                "",
                *(
                    [
                        f"{index}. {step}"
                        for index, step in enumerate(
                            reproduction.get("steps") or [],
                            start=1,
                        )
                    ]
                    or ["1. 使用证据中的工具和参数重新执行。"]
                ),
                "",
                f"预期失败：{reproduction.get('expectedFailure') or '与证据中的失败一致'}",
                "",
                "**根因假设**",
                "",
                f"{root.get('summary') or '当前证据不足，暂不猜测根因。'}",
                "",
                "**修复方案**",
                "",
                *(
                    [
                        f"{item.get('order')}. {item.get('summary')}："
                        f"{item.get('detail')}"
                        for item in action.get("fixPlan") or []
                    ]
                    or ["1. 先补齐证据，再定位实现。"]
                ),
                "",
                "**测试计划**",
                "",
                *(
                    [
                        f"- 单元：{item}"
                        for item in test_plan.get("unit") or []
                    ]
                    + [
                        f"- 集成：{item}"
                        for item in test_plan.get("integration") or []
                    ]
                    + [
                        f"- 回归：{item}"
                        for item in test_plan.get("regression") or []
                    ]
                ),
                "",
                "**验收标准**",
                "",
                *(
                    [
                        f"- {item}"
                        for item in action.get("acceptanceCriteria") or []
                    ]
                    or ["- 当前证据不足，不能验收。"]
                ),
                "",
                "**可复制给 Coding Agent 的修复任务**",
                "",
                "```text",
                str(action.get("agentPrompt") or ""),
                "```",
                "",
            ]
        )
    if not report["developerActions"]:
        lines.append("- 没有足够证据生成行动项。")
    lines.extend(["", "## 验证与限制", "", "**已验证：**", ""])
    lines.extend(
        [f"- {item}" for item in report["verification"]["verified"]]
        or ["- 无。"]
    )
    lines.extend(["", "**未验证：**", ""])
    lines.extend(
        [f"- {item}" for item in report["verification"]["unverified"]]
        or ["- 无。"]
    )
    if report["limitations"]:
        lines.extend(["", "**限制：**", ""])
        lines.extend([f"- {item}" for item in report["limitations"]])
    return "\n".join(lines).strip() + "\n"


def _validate(report: dict[str, Any]) -> None:
    schema_path = (
        Path(__file__).resolve().parents[3]
        / "schemas"
        / "task-review-narrative.schema.json"
    )
    jsonschema.validate(
        report,
        json.loads(schema_path.read_text(encoding="utf-8")),
    )


def generate_narrative(
    fast: dict[str, Any],
    *,
    query: str,
    dimensions: list[dict[str, Any]],
    failure_attributions: list[dict[str, Any]],
    diagnostic_summary: dict[str, Any],
    config: SemanticJudgeConfig,
    provider: SemanticJudgeProvider | None = None,
) -> dict[str, Any]:
    fallback = _fact_narrative(
        fast,
        query=query,
        failure_attributions=failure_attributions,
        diagnostic_summary=diagnostic_summary,
        dimensions=dimensions,
    )
    if not config.configured and provider is None:
        fallback["markdown"] = render_markdown(fallback)
        _validate(fallback)
        return fallback
    try:
        active_provider = provider or SemanticJudgeProvider(config)
        tool_ids = {
            str(tool.get("id"))
            for run in fast.get("runs") or []
            for tool in (run.get("timeline") or {}).get("tools") or []
            if tool.get("id")
        }
        allowed_evidence_refs = tool_ids | {
            f"tool:{tool_id}" for tool_id in tool_ids
        } | {
            str(ref)
            for attribution in failure_attributions
            for ref in attribution.get("evidenceRefs") or []
            if ref
        }
        response = active_provider.complete(
            [
                {"role": "system", "content": _model_prompt()},
                {
                    "role": "user",
                    "content": json.dumps(
                        _provider_context(
                            fallback,
                            fast=fast,
                            dimensions=dimensions,
                            failure_attributions=failure_attributions,
                            diagnostic_summary=diagnostic_summary,
                        ),
                        ensure_ascii=False,
                        indent=2,
                    ),
                },
            ]
        )
        payload = extract_json_object(response)
        report = _normalize_model_report(
            payload,
            fallback,
            allowed_evidence_refs=allowed_evidence_refs,
        )
        report["markdown"] = render_markdown(report)
        _validate(report)
        return report
    except Exception as exc:  # noqa: BLE001 - narrative is advisory
        fallback["markdown"] = render_markdown(fallback)
        fallback["error"] = {
            "kind": type(exc).__name__,
            "message": str(exc),
        }
        _validate(fallback)
        return fallback
