# -*- coding: utf-8 -*-
# =====================
#
# Author: liumin.423
# Date:   2025/7/9
# =====================
"""DeepSearch 查询拆解与章节搜索词。"""

import os
import re
import time
import json

from loguru import logger

from cute_tool.util.llm_util import ask_llm
from cute_tool.util.llm_util import resolve_openai_compat_env
from cute_tool.util.prompt_util import get_prompt
from cute_tool.model.context import RequestIdCtx
from cute_tool.model.research import ResearchAction
from cute_tool.tool.search_component.source_ledger import normalize_url
from cute_tool.util.log_util import timer


_URL_PATTERN = re.compile(r"https?://[^\s<>'\"\]\)}]+", re.IGNORECASE)
_URL_TRAILING_PUNCTUATION = ".,;:!?，。；：！？"


@timer()
async def query_decompose(query: str, **kwargs):
    """将复杂问题拆成章节，并为每个章节生成搜索关键词。"""
    llm_config = resolve_openai_compat_env("DEEPSEARCH")
    model = (
        os.getenv("QUERY_DECOMPOSE_MODEL") or os.getenv("DEFAULT_MODEL") or "gpt-4.1"
    )
    current_date = time.strftime("%Y-%m-%d", time.localtime())
    prompt = get_prompt("deepsearch")["chapter_structure_prompt"].format(
        query=query,
        current_date=current_date,
        max_queries=os.getenv("QUERY_DECOMPOSE_MAX_SIZE", 5),
        max_search_queries=os.getenv("CHAPTER_SEARCH_QUERY_MAX_SIZE", 3),
    )
    messages = [
        {
            "role": "system",
            "content": prompt,
        },
        {"role": "user", "content": "请只输出符合要求的 JSON 数组。"},
    ]
    extend_queries = ""
    async for chunk in ask_llm(
        messages=messages,
        model=model,
        stream=True,
        only_content=True,  # 只返回内容
        api_base=llm_config["api_base"],
        api_key=llm_config["api_key"],
    ):
        if chunk:
            extend_queries += chunk

    chapters = parse_report_structure(extend_queries, fallback_query=query)
    chapters = apply_query_url_actions(chapters, query=query)
    logger.debug(
        f"{RequestIdCtx.request_id} query_decompose queries completed, "
        f"chars={len(extend_queries)} chapters={len(chapters)}"
    )
    return chapters


def normalize_search_queries(value) -> list[str]:
    """将模型返回的搜索词统一为去重后的字符串列表。"""
    values = value if isinstance(value, list) else [value]
    normalized = []
    for item in values:
        text = _URL_PATTERN.sub(" ", str(item or ""))
        text = " ".join(text.split())
        if text and text not in normalized:
            normalized.append(text)
    return normalized


def normalize_research_actions(value) -> list[ResearchAction]:
    """解析模型动作列表；非法动作被丢弃，不会静默降级成搜索。"""
    values = value if isinstance(value, list) else [value]
    actions = []
    for item in values:
        if not item:
            continue
        try:
            actions.append(
                item
                if isinstance(item, ResearchAction)
                else ResearchAction.model_validate(item)
            )
        except (TypeError, ValueError) as exc:
            logger.warning(
                f"{RequestIdCtx.request_id} invalid research action ignored: "
                f"value={item!r} error={exc}"
            )
    return actions


def chapter_research_actions(item: dict) -> list[ResearchAction]:
    """兼容 actions、search_queries 和 content 三种章节动作来源。"""
    if "actions" in item:
        return normalize_research_actions(item.get("actions"))

    queries = normalize_search_queries(
        item.get("search_queries") or item.get("queries")
    )
    if not queries:
        content = str(item.get("content") or item.get("title") or "").strip()
        queries = [content] if content else []
    return [
        ResearchAction(type="search", tool="web_search", query=query)
        for query in queries
    ]


def extract_query_urls(query: str) -> list[str]:
    """按出现顺序提取并规范化 query 中的 http(s) URL。"""
    urls = []
    for match in _URL_PATTERN.findall(query or ""):
        normalized = normalize_url(match.rstrip(_URL_TRAILING_PUNCTUATION))
        if normalized and normalized not in urls:
            urls.append(normalized)
    return urls


def apply_query_url_actions(chapters: list[dict], *, query: str) -> list[dict]:
    """把用户 query 中漏规划的 URL 补为第一章节的 web_fetch 动作。"""
    query_urls = extract_query_urls(query)
    if not query_urls or not chapters:
        return chapters

    existing_urls = {
        normalize_url(action.url)
        for chapter in chapters
        for action in chapter_research_actions(chapter)
        if action.type == "fetch_url"
    }
    missing_urls = [url for url in query_urls if url not in existing_urls]
    if not missing_urls:
        return chapters

    first = chapters[0]
    actions = chapter_research_actions(first)
    actions.extend(
        ResearchAction(
            type="fetch_url",
            tool="web_fetch",
            url=url,
            reason="用户 query 中明确提供的 URL",
        )
        for url in missing_urls
    )
    first["actions"] = [action.model_dump(mode="json") for action in actions]
    first["search_queries"] = [
        action.query for action in actions if action.type == "search"
    ]
    logger.info(
        f"{RequestIdCtx.request_id} URL_PLAN_FALLBACK "
        f"chapter={first.get('title', '')!r} url_count={len(missing_urls)}"
    )
    return chapters


def parse_report_structure(content: str, fallback_query: str = "") -> list[dict]:
    """解析章节和每章搜索词，并兼容旧版 markdown 查询列表。"""
    text = (content or "").strip()
    if text:
        try:
            parsed = json.loads(text)
            if isinstance(parsed, dict):
                parsed = parsed.get("chapters") or parsed.get("items") or []
            if isinstance(parsed, list):
                chapters = []
                for item in parsed:
                    if isinstance(item, str) and item.strip():
                        value = item.strip()
                        chapters.append(
                            {
                                "title": value,
                                "content": value,
                                "search_queries": [value],
                            }
                        )
                    elif isinstance(item, dict):
                        title = str(item.get("title") or item.get("name") or "").strip()
                        content_text = str(
                            item.get("content") or item.get("description") or title
                        ).strip()
                        if title:
                            actions = (
                                normalize_research_actions(item.get("actions"))
                                if "actions" in item
                                else []
                            )
                            search_queries = normalize_search_queries(
                                item.get("search_queries") or item.get("queries")
                            )
                            if not search_queries and actions:
                                search_queries = [
                                    action.query
                                    for action in actions
                                    if action.type == "search"
                                ]
                            if not search_queries and not actions and content_text:
                                search_queries = [content_text]
                            chapter = {
                                "title": title,
                                "content": content_text or title,
                                "search_queries": search_queries,
                            }
                            if "actions" in item:
                                chapter["actions"] = [
                                    action.model_dump(mode="json")
                                    for action in actions
                                ]
                            chapters.append(chapter)
                if chapters:
                    return chapters
        except (TypeError, ValueError, json.JSONDecodeError):
            pass

    legacy_queries = re.findall(r"^- (.+)$", text, re.MULTILINE)
    if legacy_queries:
        return [
            {
                "title": item.strip(),
                "content": item.strip(),
                "search_queries": [item.strip()],
            }
            for item in legacy_queries
            if item.strip()
        ]

    fallback = (fallback_query or "").strip()
    return (
        [{"title": fallback, "content": fallback, "search_queries": [fallback]}]
        if fallback
        else []
    )


if __name__ == "__main__":
    pass
