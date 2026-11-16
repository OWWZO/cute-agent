import os
from unittest.mock import patch

import pytest

from cute_tool.util.llm_util import resolve_openai_compat_env
from cute_tool.tool.search_component.query_process import (
    apply_query_url_actions,
    extract_query_urls,
    parse_report_structure,
    query_decompose,
)


def test_resolve_openai_compat_env_prefers_prefixed_over_global():
    with patch.dict(
        os.environ,
        {
            "DEEPSEARCH_BASE_URL": "https://deepsearch.example/v1",
            "DEEPSEARCH_API_KEY": "deepsearch-key",
            "OPENAI_BASE_URL": "https://global.example/v1",
            "OPENAI_API_KEY": "global-key",
        },
        clear=False,
    ):
        config = resolve_openai_compat_env("DEEPSEARCH")

    assert config == {
        "api_base": "https://deepsearch.example/v1",
        "api_key": "deepsearch-key",
    }


def test_parse_report_structure_returns_chapter_and_search_queries():
    chapters = parse_report_structure(
        '[{"title":"技术路线","content":"分析电池和电控",'
        '"search_queries":["电池技术","电控技术","电池技术"]}]'
    )

    assert chapters == [
        {
            "title": "技术路线",
            "content": "分析电池和电控",
            "search_queries": ["电池技术", "电控技术"],
        }
    ]


def test_parse_report_structure_supports_legacy_query_list():
    chapters = parse_report_structure("- 技术路线\n- 市场格局")

    assert chapters == [
        {
            "title": "技术路线",
            "content": "技术路线",
            "search_queries": ["技术路线"],
        },
        {
            "title": "市场格局",
            "content": "市场格局",
            "search_queries": ["市场格局"],
        },
    ]


def test_parse_report_structure_supports_research_actions():
    chapters = parse_report_structure(
        '[{"title":"官方回应","content":"查看公告",'
        '"actions":['
        '{"type":"search","tool":"web_search","query":"公司 公告"},'
        '{"type":"fetch_url","tool":"web_fetch",'
        '"url":"https://example.com/notice"}]}]'
    )

    assert chapters[0]["search_queries"] == ["公司 公告"]
    assert chapters[0]["actions"] == [
        {
            "type": "search",
            "tool": "web_search",
            "query": "公司 公告",
            "url": "",
            "reason": "",
            "gap_ids": [],
        },
        {
            "type": "fetch_url",
            "tool": "web_fetch",
            "query": "",
            "url": "https://example.com/notice",
            "reason": "",
            "gap_ids": [],
        },
    ]


def test_parse_report_structure_does_not_convert_invalid_fetch_to_search():
    chapters = parse_report_structure(
        '[{"title":"官方回应","content":"查看公告",'
        '"actions":[{"type":"fetch_url","tool":"web_search",'
        '"url":"https://example.com/notice"}]}]'
    )

    assert chapters[0]["actions"] == []
    assert chapters[0]["search_queries"] == ["查看公告"]


def test_extract_query_urls_removes_fragments_and_trailing_punctuation():
    urls = extract_query_urls(
        "请参考 https://example.com/a#part one 和 https://example.com/b。"
    )

    assert urls == ["https://example.com/a", "https://example.com/b"]


def test_apply_query_url_actions_adds_missing_url_to_first_chapter():
    chapters = [
        {
            "title": "章节一",
            "content": "研究任务一",
            "search_queries": ["关键词"],
        },
        {
            "title": "章节二",
            "content": "研究任务二",
            "search_queries": ["关键词二"],
        },
    ]

    result = apply_query_url_actions(
        chapters,
        query="请根据 https://example.com/source 继续研究",
    )

    actions = result[0]["actions"]
    assert actions[0]["type"] == "search"
    assert actions[1]["type"] == "fetch_url"
    assert actions[1]["url"] == "https://example.com/source"
    assert "actions" not in result[1]


@pytest.mark.asyncio
async def test_query_decompose_falls_back_when_model_omits_query_url():
    async def fake_ask_llm(**kwargs):
        yield (
            '[{"title":"章节标题","content":"研究任务",'
            '"search_queries":["研究任务"]}]'
        )

    with patch(
        "cute_tool.tool.search_component.query_process.ask_llm",
        fake_ask_llm,
    ):
        chapters = await query_decompose(
            "请根据 https://example.com/source 继续研究"
        )

    actions = chapters[0]["actions"]
    assert actions[0]["type"] == "search"
    assert actions[1] == {
        "type": "fetch_url",
        "tool": "web_fetch",
        "query": "",
        "url": "https://example.com/source",
        "reason": "用户 query 中明确提供的 URL",
        "gap_ids": [],
    }
