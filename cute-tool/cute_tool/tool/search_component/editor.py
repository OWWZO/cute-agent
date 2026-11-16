# -*- coding: utf-8 -*-
"""DeepSearch 章节总结结构化编辑器。"""

import copy
import json
import os

from json_repair import repair_json
from pydantic import ValidationError

from cute_tool.model.research import (
    EditPlan,
    ResearchGap,
    json_schema_response_format,
)
from cute_tool.tool.search_component.source_ledger import SourceLedger
from cute_tool.util.llm_util import (
    ask_llm,
    resolve_openai_compat_env,
    structured_output_max_tokens,
)
from cute_tool.util.prompt_util import get_prompt


class EditParseError(ValueError):
    """Editor 输出无法通过 JSON 或协议校验。"""


def _parse_json_object(raw: str) -> dict:
    text = (raw or "").strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines:
            lines = lines[1:]
        if lines and lines[-1].strip().startswith("```"):
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    try:
        repaired = repair_json(text)
        value = json.loads(repaired)
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise EditParseError(f"invalid editor JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise EditParseError("editor JSON must be an object")
    return value


def _render_prompt(
    template: str,
    *,
    query: str,
    chapter_title: str,
    chapter_content: str,
) -> str:
    return (
        template.replace("{query}", query)
        .replace("{chapter_title}", chapter_title)
        .replace("{chapter_content}", chapter_content)
    )


async def generate_edit_plan(
    *,
    query: str,
    chapter_title: str,
    chapter_content: str,
    base_version: int,
    current_summary: str,
    gaps: list[ResearchGap],
    source_ledger: SourceLedger,
    new_evidence: str,
    previous_edit_failure: str = "",
    context_messages: list[dict] | None = None,
) -> EditPlan:
    """调用 Editor，解析一批编辑意图。"""
    prompt_template = get_prompt("deepsearch")["summary_edit_prompt"]
    system_prompt = _render_prompt(
        prompt_template,
        query=query,
        chapter_title=chapter_title,
        chapter_content=chapter_content or chapter_title,
    )
    try:
        evidence = json.loads(new_evidence) if new_evidence else []
    except (TypeError, ValueError, json.JSONDecodeError):
        evidence = new_evidence
    if context_messages is None:
        dynamic_state = {
            "base_version": base_version,
            "current_summary": current_summary,
            "gaps": [
                gap.model_dump(mode="json")
                for gap in gaps
                if gap.status != "resolved"
            ],
            "new_evidence": evidence,
            "source_index": source_ledger.source_index(),
        }
        if previous_edit_failure:
            dynamic_state["previous_edit_failure"] = previous_edit_failure
        messages = [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": "当前编辑状态：\n"
                + json.dumps(dynamic_state, ensure_ascii=False, indent=2),
            },
        ]
    else:
        messages = copy.deepcopy(context_messages)
        evidence_state = {
            "base_version": base_version,
            "new_evidence": evidence,
            "current_source_index": source_ledger.source_index(),
        }
        messages.append(
            {
                "role": "user",
                "content": "本轮新增证据与当前来源索引：\n"
                + json.dumps(evidence_state, ensure_ascii=False, indent=2),
            }
        )
        if previous_edit_failure:
            messages.append(
                {
                    "role": "user",
                    "content": "上一次编辑失败，请只修正编辑计划：\n"
                    + previous_edit_failure,
                }
            )
        messages.append({"role": "user", "content": system_prompt})
    llm_config = resolve_openai_compat_env("DEEPSEARCH")
    model = os.getenv("SEARCH_ANSWER_MODEL", "gpt-4.1")

    request_params = {
        "messages": messages,
        "model": model,
        "stream": True,
        "only_content": True,
        "api_base": llm_config["api_base"],
        "api_key": llm_config["api_key"],
        "response_format": json_schema_response_format(
            EditPlan, name="summary_edit_plan"
        ),
        "max_tokens": structured_output_max_tokens(messages, model),
    }
    for _ in range(2):
        raw = ""
        async for chunk in ask_llm(**request_params):
            if chunk:
                raw += chunk
        if raw.strip():
            break
    else:
        raise EditParseError("empty editor response after retry")

    try:
        plan = EditPlan.model_validate(_parse_json_object(raw))
    except ValidationError as exc:
        raise EditParseError(f"invalid editor schema: {exc}") from exc
    if plan.base_version != base_version:
        raise EditParseError(
            f"editor base_version={plan.base_version} "
            f"does not match current_version={base_version}"
        )
    return plan
