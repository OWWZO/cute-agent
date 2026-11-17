from __future__ import annotations

import hashlib
import json
import re
from collections import defaultdict
from typing import Any

from .action_playbooks import ActionPlaybook, get_playbook

_UUID = re.compile(
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b",
    re.IGNORECASE,
)
_LONG_HEX = re.compile(r"\b[0-9a-f]{16,}\b", re.IGNORECASE)
_NUMBER = re.compile(r"\b\d{4,}\b")
_TIMESTAMP = re.compile(
    r"\b\d{4}-\d{2}-\d{2}[T\s]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?\b"
)
_URL_QUERY = re.compile(r"(https?://[^\s?#]+)\?[^\s#]*")


def _normalize_error(value: str) -> str:
    text = value.strip().lower()
    if not text:
        return "no_error_message"
    text = _TIMESTAMP.sub("<time>", text)
    text = _UUID.sub("<id>", text)
    text = _LONG_HEX.sub("<id>", text)
    text = _URL_QUERY.sub(r"\1?<query>", text)
    text = _NUMBER.sub("<n>", text)
    text = re.sub(r"#[A-Za-z0-9_-]+", "#<dynamic>", text)
    text = re.sub(r"\s+", " ", text)
    return text[:240]


def _tool_lookup(fast: dict[str, Any]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for run in fast.get("runs") or []:
        request_id = str(run.get("requestId") or "")
        for tool in (run.get("timeline") or {}).get("tools") or []:
            if not isinstance(tool, dict):
                continue
            call_id = str(tool.get("id") or tool.get("toolCallId") or "")
            ref = str(tool.get("evidenceRef") or f"tool:{request_id}:{call_id}")
            result[ref] = tool
            if call_id:
                result.setdefault(f"tool:{call_id}", tool)
    return result


def _failure_signature(
    attribution: dict[str, Any],
    tool: dict[str, Any] | None,
) -> str:
    rules = ",".join(sorted(str(item) for item in attribution.get("ruleIds") or []))
    error = _normalize_error(
        str(
            (tool or {}).get("errorMsg")
            or attribution.get("reason")
            or "no_error_message"
        )
    )
    return "|".join(
        [
            str(attribution.get("primaryLayer") or "UNKNOWN"),
            str(attribution.get("toolName") or "unknown"),
            str(attribution.get("failureKind") or "UNKNOWN_FAILURE"),
            rules or "NO_RULE",
            error,
        ]
    )


def _action_id(signature: str) -> str:
    digest = hashlib.sha256(signature.encode("utf-8")).hexdigest()[:10]
    return f"action-{digest}"


def _bullets(items: list[str], fallback: str = "无") -> str:
    return "\n".join(f"- {item}" for item in items) if items else f"- {fallback}"


def _numbered(items: list[str], fallback: str = "无") -> str:
    return (
        "\n".join(f"{index}. {item}" for index, item in enumerate(items, start=1))
        if items
        else f"1. {fallback}"
    )


def compile_agent_prompt(action: dict[str, Any]) -> str:
    problem = action["problemDefinition"]
    reproduction = action["reproduction"]
    root = action["rootCauseHypothesis"]
    areas = [
        f"{item.get('kind')}: {item.get('name')}"
        for item in action.get("affectedAreas") or []
    ]
    unit = list((action.get("testPlan") or {}).get("unit") or [])
    integration = list((action.get("testPlan") or {}).get("integration") or [])
    regression = list((action.get("testPlan") or {}).get("regression") or [])
    fix_steps = [
        f"{item.get('order')}. {item.get('summary')}：{item.get('detail')}"
        for item in action.get("fixPlan") or []
    ]
    return "\n".join(
        [
            f"# 修复任务：{action['title']}",
            "",
            "## 背景",
            "",
            "你在维护 Cute Agent 的任务执行链路。",
            "本次问题来自真实 Execution Ledger Trace，不是假设场景。",
            "",
            "## 问题定义",
            "",
            f"- 工具：`{action['toolName']}`",
            f"- 失败类型：`{action['failureKind']}`",
            f"- 主责任层：`{action['targetLayer']}`",
            f"- 行动范围：`{action['actionScope']}`",
            f"- 发生次数：`{action['frequency']['count']}`",
            f"- 未恢复次数：`{action['frequency']['unrecovered']}`",
            "",
            "## 观测到的现象",
            "",
            problem["observed"],
            "",
            "## 期望行为",
            "",
            problem["expected"],
            "",
            "## 影响",
            "",
            problem["impact"],
            "",
            "## 证据",
            "",
            _bullets(list(action.get("evidenceRefs") or []), "需要补充可定位证据"),
            "",
            "## 最小复现",
            "",
            "### 前置条件",
            "",
            _bullets(list(reproduction.get("preconditions") or [])),
            "",
            "### 步骤",
            "",
            _numbered(list(reproduction.get("steps") or [])),
            "",
            f"预期失败：{reproduction.get('expectedFailure') or '与证据中的失败一致'}",
            "",
            "## 根因假设",
            "",
            root["summary"],
            "",
            f"置信度：{root.get('confidence', 0)}",
            "",
            "其他可能：",
            "",
            _bullets(list(root.get("alternatives") or [])),
            "",
            "## 修改要求",
            "",
            "1. 先搜索并定位真实实现，不要直接假设文件路径。",
            "2. 先写一个能复现问题的失败测试。",
            "3. 修复根因，不要只吞异常或改报告文案。",
            "4. 保持错误码、状态和 Trace 字段结构化。",
            "5. 只修改与本问题相关的代码。",
            "6. 不自动提交 Git。",
            "",
            *[f"- {item}" for item in fix_steps],
            "",
            "## 建议检查区域",
            "",
            _bullets(areas),
            "",
            "## 测试要求",
            "",
            "### 单元测试",
            "",
            _bullets(unit),
            "",
            "### 集成测试",
            "",
            _bullets(integration),
            "",
            "### 回归测试",
            "",
            _bullets(regression),
            "",
            "## 验收标准",
            "",
            _bullets(list(action.get("acceptanceCriteria") or [])),
            "",
            "## 禁止事项",
            "",
            "- 不得伪造成功。",
            "- 不得把异常改成空结果。",
            "- 不得静默重试非幂等操作。",
            "- 不得删除现有失败证据。",
            "- 不得只修改报告文案而不修复运行时行为。",
            "",
            "## 交付格式",
            "",
            "请输出：",
            "",
            "1. 根因确认。",
            "2. 修改文件列表。",
            "3. 关键 diff 说明。",
            "4. 测试命令与结果。",
            "5. 仍存在的风险。",
        ]
    )


def _to_action(
    *,
    signature: str,
    group: list[dict[str, Any]],
) -> dict[str, Any]:
    first = group[0]
    tool_name = str(first.get("toolName") or "unknown")
    failure_kind = str(first.get("failureKind") or "UNKNOWN_FAILURE")
    primary_layer = str(first.get("primaryLayer") or "UNKNOWN")
    recovered = sum(1 for item in group if item.get("recovered"))
    unrecovered = len(group) - recovered
    count = len(group)
    evidence_refs = list(
        dict.fromkeys(
            str(ref)
            for item in group
            for ref in item.get("evidenceRefs") or []
            if str(ref).startswith("tool:")
        )
    )
    playbook: ActionPlaybook = get_playbook(
        failure_kind,
        tool_name=tool_name,
        primary_layer=primary_layer,
    )
    confidence = round(
        sum(float(item.get("confidence") or 0) for item in group) / count,
        2,
    )
    status = playbook.status
    if not evidence_refs:
        status = "needs_more_evidence"
    action = {
        "actionId": _action_id(signature),
        "title": playbook.title,
        "targetLayer": primary_layer,
        "priority": playbook.priority,
        "actionScope": playbook.action_scope,
        "status": status,
        "bugSignature": signature,
        "failureKind": failure_kind,
        "toolName": tool_name,
        "frequency": {
            "count": count,
            "recovered": recovered,
            "unrecovered": unrecovered,
        },
        "problemDefinition": {
            "observed": playbook.observed,
            "expected": playbook.expected,
            "impact": playbook.impact,
        },
        "evidenceRefs": evidence_refs,
        "reproduction": {
            "preconditions": list(playbook.preconditions),
            "steps": list(playbook.reproduction_steps),
            "expectedFailure": playbook.expected_failure,
        },
        "rootCauseHypothesis": {
            "summary": playbook.root_cause,
            "confidence": confidence,
            "alternatives": list(playbook.root_alternatives),
        },
        "affectedAreas": list(playbook.affected_areas),
        "fixPlan": [
            {"order": index, "summary": summary, "detail": detail}
            for index, (summary, detail) in enumerate(
                playbook.fix_plan,
                start=1,
            )
        ],
        "testPlan": {
            "unit": list(playbook.unit_tests),
            "integration": list(playbook.integration_tests),
            "regression": list(playbook.regression_tests),
        },
        "acceptanceCriteria": list(playbook.acceptance_criteria),
        # Legacy fields keep old clients readable while the detailed fields
        # carry the actionable repair task.
        "target": primary_layer,
        "action": playbook.title,
        "expectedEffect": playbook.expected,
        "agentPrompt": "",
    }
    action["agentPrompt"] = compile_agent_prompt(action)
    return action


def build_developer_actions(
    failure_attributions: list[dict[str, Any]],
    *,
    fast: dict[str, Any],
) -> list[dict[str, Any]]:
    tools = _tool_lookup(fast)
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for attribution in failure_attributions:
        refs = [
            str(ref)
            for ref in attribution.get("evidenceRefs") or []
            if str(ref).startswith("tool:")
        ]
        tool = next(
            (tools[ref] for ref in refs if ref in tools),
            tools.get(f"tool:{attribution.get('toolCallId')}"),
        )
        signature = _failure_signature(attribution, tool)
        grouped[signature].append(attribution)

    priority_order = {"P0": 0, "P1": 1, "P2": 2}
    actions = [
        _to_action(
            signature=signature,
            group=group,
        )
        for signature, group in grouped.items()
    ]
    actions.sort(
        key=lambda item: (
            priority_order.get(str(item.get("priority")), 9),
            -int((item.get("frequency") or {}).get("unrecovered") or 0),
            str(item.get("toolName") or ""),
            str(item.get("actionId") or ""),
        )
    )
    return actions


def action_to_json(action: dict[str, Any]) -> str:
    return json.dumps(action, ensure_ascii=False, indent=2)
