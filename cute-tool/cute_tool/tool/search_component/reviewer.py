# -*- coding: utf-8 -*-
"""DeepSearch 章节研究审查器。"""

import json
import os
from dataclasses import dataclass

from json_repair import repair_json
from pydantic import ValidationError

from cute_tool.model.research import (
    ResearchGap,
    ResearchState,
    ReviewResult,
    json_schema_response_format,
)
from cute_tool.tool.search_component.source_ledger import SourceLedger, normalize_url
from cute_tool.util.llm_util import (
    ask_llm,
    resolve_openai_compat_env,
    structured_output_max_tokens,
)
from cute_tool.util.prompt_util import get_prompt


class ReviewParseError(ValueError):
    """Reviewer 输出无法通过 JSON 或协议校验。"""


@dataclass(slots=True)
class ReviewRound:
    """一轮 Reviewer 结果及其可供 Editor 复用的消息前缀。"""

    result: ReviewResult
    messages: list[dict]


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
        raise ReviewParseError(f"invalid reviewer JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise ReviewParseError("reviewer JSON must be an object")
    return value


def _normalize_query(value: str) -> str:
    return " ".join((value or "").split()).casefold()


def _render_prompt(
    template: str,
    *,
    query: str,
    chapter_title: str,
    chapter_content: str,
    max_actions: int,
) -> str:
    return (
        template.replace("{query}", query)
        .replace("{chapter_title}", chapter_title)
        .replace("{chapter_content}", chapter_content)
        .replace("{max_actions}", str(max_actions))
    )


def build_research_round_context(
    *,
    query: str,
    chapter_title: str,
    chapter_content: str,
    state: ResearchState,
    source_ledger: SourceLedger,
) -> list[dict]:
    """构建同一轮 Reviewer/Editor 共用的稳定消息前缀。"""
    system_prompt = get_prompt("deepsearch")["research_round_system_prompt"]
    dynamic_state = {
        "query": query,
        "chapter_title": chapter_title,
        "chapter_content": chapter_content or chapter_title,
        "base_version": state.summary_version,
        "iteration": state.iteration,
        "current_summary": state.summary,
        "open_gaps": [
            gap.model_dump(mode="json")
            for gap in state.gaps
            if gap.status != "resolved"
        ],
        "searched_queries": list(state.searched_queries),
        "fetched_urls": list(state.fetched_urls),
        "link_candidates": list(state.link_candidates),
        "source_index": source_ledger.source_index(),
    }
    return [
        {"role": "system", "content": system_prompt},
        {
            "role": "user",
            "content": "当前轮次状态：\n"
            + json.dumps(dynamic_state, ensure_ascii=False, indent=2),
        },
    ]


def _build_review_messages(
    *,
    query: str,
    chapter_title: str,
    chapter_content: str,
    state: ResearchState,
    source_ledger: SourceLedger,
    max_actions: int,
) -> list[dict]:
    messages = build_research_round_context(
        query=query,
        chapter_title=chapter_title,
        chapter_content=chapter_content,
        state=state,
        source_ledger=source_ledger,
    )
    prompt_template = get_prompt("deepsearch")["research_review_prompt"]
    messages.append(
        {
            "role": "user",
            "content": _render_prompt(
                prompt_template,
                query=query,
                chapter_title=chapter_title,
                chapter_content=chapter_content or chapter_title,
                max_actions=max_actions,
            ),
        }
    )
    return messages


async def _request_review(
    *,
    messages: list[dict],
    state: ResearchState,
    source_ledger: SourceLedger,
    max_actions: int,
) -> ReviewResult:
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
            ReviewResult, name="research_review"
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
        raise ReviewParseError("empty reviewer response after retry")

    try:
        result = ReviewResult.model_validate(_parse_json_object(raw))
    except ValidationError as exc:
        raise ReviewParseError(f"invalid reviewer schema: {exc}") from exc
    if result.base_version != state.summary_version:
        raise ReviewParseError(
            f"reviewer base_version={result.base_version} "
            f"does not match current_version={state.summary_version}"
        )

    high_priority_gaps = [
        gap
        for gap in result.gaps
        if gap.priority == "high" and gap.status != "resolved"
    ]
    if high_priority_gaps:
        result.sufficient = False
    if result.sufficient:
        result.actions = []
        return result

    seen_queries = {
        _normalize_query(item) for item in state.searched_queries if item.strip()
    }
    seen_actions: set[tuple[str, str]] = set()
    candidate_urls = {
        normalize_url(item.get("url", ""))
        for item in state.link_candidates
        if isinstance(item, dict)
    }
    candidate_urls.discard("")
    fetched_urls = {
        normalize_url(item) for item in state.fetched_urls if item
    }
    ledger_urls = {
        normalize_url(item.get("url", ""))
        for item in source_ledger.source_index()
        if item.get("url")
    }
    accepted_actions = []
    for action in result.actions:
        if action.type == "search":
            normalized = _normalize_query(action.query)
            action_key = ("search", normalized)
            if not normalized or normalized in seen_queries:
                continue
        else:
            normalized = normalize_url(action.url)
            action_key = ("fetch_url", normalized)
            if (
                not normalized
                or normalized in fetched_urls
                or normalized in ledger_urls
                or normalized not in candidate_urls
            ):
                continue
        if action_key in seen_actions:
            continue
        seen_actions.add(action_key)
        if action.type == "search":
            seen_queries.add(normalized)
        accepted_actions.append(action)
        if len(accepted_actions) >= max_actions:
            break
    result.actions = accepted_actions
    return result


async def review_research_round(
    *,
    query: str,
    chapter_title: str,
    chapter_content: str,
    state: ResearchState,
    source_ledger: SourceLedger,
    max_actions: int = 3,
) -> ReviewRound:
    """执行 Reviewer，并保留 Editor 可复用的同一轮上下文。"""
    max_actions = min(3, max(1, int(max_actions)))
    messages = _build_review_messages(
        query=query,
        chapter_title=chapter_title,
        chapter_content=chapter_content,
        state=state,
        source_ledger=source_ledger,
        max_actions=max_actions,
    )
    result = await _request_review(
        messages=messages,
        state=state,
        source_ledger=source_ledger,
        max_actions=max_actions,
    )
    assistant_message = {
        "role": "assistant",
        "content": json.dumps(
            result.model_dump(mode="json"),
            ensure_ascii=False,
            separators=(",", ":"),
        ),
    }
    return ReviewRound(result=result, messages=[*messages, assistant_message])


async def review_research(
    *,
    query: str,
    chapter_title: str,
    chapter_content: str,
    state: ResearchState,
    source_ledger: SourceLedger,
    max_actions: int = 3,
) -> ReviewResult:
    """兼容入口：只返回 Reviewer 的结构化结果。"""
    round_context = await review_research_round(
        query=query,
        chapter_title=chapter_title,
        chapter_content=chapter_content,
        state=state,
        source_ledger=source_ledger,
        max_actions=max_actions,
    )
    return round_context.result
