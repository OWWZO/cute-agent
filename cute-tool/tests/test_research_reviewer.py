# -*- coding: utf-8 -*-
import copy
import json

import pytest

from cute_tool.model.document import Doc
from cute_tool.model.research import (
    ResearchAction,
    ResearchGap,
    ResearchState,
    SearchAction,
)
from cute_tool.tool.search_component.reviewer import (
    ReviewParseError,
    review_research_round,
    review_research,
)
from cute_tool.tool.search_component.source_ledger import SourceLedger


def _stub_llm(monkeypatch, output: str, calls: list | None = None):
    async def fake_ask_llm(**kwargs):
        if calls is not None:
            calls.append(kwargs)
        yield output

    monkeypatch.setattr(
        "cute_tool.tool.search_component.reviewer.ask_llm", fake_ask_llm
    )


def _stub_llm_sequence(monkeypatch, outputs: list[str], calls: list):
    queue = list(outputs)

    async def fake_ask_llm(**kwargs):
        calls.append(copy.deepcopy(kwargs))
        yield queue.pop(0)

    monkeypatch.setattr(
        "cute_tool.tool.search_component.reviewer.ask_llm", fake_ask_llm
    )


@pytest.mark.asyncio
async def test_reviewer_accepts_fenced_json_and_corrects_high_gap(monkeypatch):
    _stub_llm(
        monkeypatch,
        """```json
        {
          "base_version": 0,
          "sufficient": true,
          "gaps": [
            {"gap_id": "G1", "description": "缺少官方结果", "priority": "high"}
          ],
          "actions": [],
          "reason": "还有关键缺口"
        }
        ```""",
    )
    state = ResearchState(summary="当前总结")

    result = await review_research(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        state=state,
        source_ledger=SourceLedger(),
    )

    assert result.sufficient is False
    assert result.gaps[0].priority == "high"


@pytest.mark.asyncio
async def test_reviewer_context_does_not_expose_internal_source_ids(monkeypatch):
    calls = []
    _stub_llm(
        monkeypatch,
        '{"base_version":0,"sufficient":true,"gaps":[],"actions":[]}',
        calls,
    )
    ledger = SourceLedger()
    ledger.add_documents(
        [
            Doc(
                doc_type="web_page",
                title="source",
                link="https://example.com/source",
                content="source content",
            )
        ],
        round_no=1,
    )

    await review_research(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        state=ResearchState(
            summary="当前总结",
            link_candidates=[
                {
                    "url": "https://example.com/a",
                    "title": "候选页",
                    "anchor": "查看",
                    "parent_url": "https://example.com",
                    "depth": 1,
                }
            ],
        ),
        source_ledger=ledger,
    )

    messages_text = json.dumps(calls[0]["messages"], ensure_ascii=False)
    assert "source_id" not in messages_text
    assert "R1-S1" not in messages_text
    assert "https://example.com/source" in messages_text


@pytest.mark.asyncio
async def test_reviewer_deduplicates_queries_and_limits_actions(monkeypatch):
    _stub_llm(
        monkeypatch,
        """
        {
          "base_version": 0,
          "sufficient": false,
          "gaps": [
            {"gap_id": "G1", "description": "缺口", "priority": "normal"}
          ],
          "actions": [
            {"type": "search", "tool": "web_search", "query": "查询一"},
            {"type": "search", "tool": "web_search", "query": "查询一"},
            {"type": "search", "tool": "web_search", "query": "查询二"},
            {"type": "search", "tool": "web_search", "query": "查询三"}
          ]
        }
        """,
    )
    state = ResearchState(summary="当前总结", searched_queries=["查询二"])

    result = await review_research(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        state=state,
        source_ledger=SourceLedger(),
        max_actions=2,
    )

    assert [action.query for action in result.actions] == ["查询一", "查询三"]


@pytest.mark.asyncio
async def test_reviewer_parse_failure_does_not_retry(monkeypatch):
    calls = []
    _stub_llm(monkeypatch, "not json", calls)

    with pytest.raises(ReviewParseError):
        await review_research(
            query="问题",
            chapter_title="章节",
            chapter_content="任务",
            state=ResearchState(summary="当前总结"),
            source_ledger=SourceLedger(),
        )

    assert len(calls) == 1


@pytest.mark.asyncio
async def test_reviewer_retries_empty_response_with_identical_messages(monkeypatch):
    calls = []
    _stub_llm_sequence(
        monkeypatch,
        ["", '{"base_version":0,"sufficient":true,"gaps":[],"actions":[]}'],
        calls,
    )

    result = await review_research(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        state=ResearchState(summary="当前总结"),
        source_ledger=SourceLedger(),
    )

    assert result.sufficient is True
    assert len(calls) == 2
    assert calls[0]["messages"] == calls[1]["messages"]
    assert calls[0]["response_format"] == calls[1]["response_format"]
    assert calls[0]["max_tokens"] == calls[1]["max_tokens"]


@pytest.mark.asyncio
async def test_reviewer_reports_empty_response_after_retry(monkeypatch):
    calls = []
    _stub_llm_sequence(monkeypatch, ["", ""], calls)

    with pytest.raises(ReviewParseError, match="empty reviewer response after retry"):
        await review_research(
            query="问题",
            chapter_title="章节",
            chapter_content="任务",
            state=ResearchState(summary="当前总结"),
            source_ledger=SourceLedger(),
        )

    assert len(calls) == 2


@pytest.mark.asyncio
async def test_sufficient_reviewer_clears_search_actions(monkeypatch):
    _stub_llm(
        monkeypatch,
        """
        {
          "base_version": 0,
          "sufficient": true,
          "gaps": [],
          "actions": [
            {"type": "search", "tool": "web_search", "query": "不应执行"}
          ]
        }
        """,
    )

    result = await review_research(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        state=ResearchState(summary="当前总结"),
        source_ledger=SourceLedger(),
    )

    assert result.actions == []


@pytest.mark.asyncio
async def test_reviewer_rejects_stale_base_version(monkeypatch):
    _stub_llm(
        monkeypatch,
        '{"base_version":0,"sufficient":true,"gaps":[],"actions":[]}',
    )
    state = ResearchState(summary="当前总结", summary_version=1)

    with pytest.raises(ReviewParseError, match="base_version"):
        await review_research(
            query="问题",
            chapter_title="章节",
            chapter_content="任务",
            state=state,
            source_ledger=SourceLedger(),
        )


def test_open_gap_model_can_be_stored_in_state():
    gap = ResearchGap(gap_id="G1", description="缺口", priority="normal")
    state = ResearchState(summary="总结", gaps=[gap])

    assert state.gaps[0].status == "open"


def test_reviewer_gap_normalizes_medium_priority_to_high():
    gap = ResearchGap(gap_id="G1", description="缺口", priority="medium")

    assert gap.priority == "high"


@pytest.mark.parametrize(
    "value,expected",
    [
        ("urgent", "high"),
        ("critical", "high"),
        ("medium", "high"),
        ("high", "high"),
        ("low", "normal"),
        ("minor", "normal"),
        ("normal", "normal"),
    ],
)
def test_reviewer_normalizes_priority_aliases(value, expected):
    gap = ResearchGap(gap_id="G1", description="缺口", priority=value)

    assert gap.priority == expected


@pytest.mark.parametrize(
    "action_type,tool",
    [
        ("web search", "web search"),
        ("web_search", "search_web"),
        ("websearch", "websearch"),
    ],
)
def test_reviewer_normalizes_action_enum_aliases(action_type, tool):
    action = SearchAction(
        type=action_type,
        tool=tool,
        query="查询",
    )

    assert action.type == "search"
    assert action.tool == "web_search"


def test_research_action_accepts_fetch_url():
    action = ResearchAction(
        type="web fetch",
        tool="fetch url",
        url="https://example.com/report",
    )

    assert action.type == "fetch_url"
    assert action.tool == "web_fetch"
    assert action.url == "https://example.com/report"


@pytest.mark.parametrize(
    "payload",
    [
        {"type": "search", "tool": "web_fetch", "query": "查询"},
        {"type": "fetch_url", "tool": "web_search", "url": "https://example.com"},
        {"type": "search", "tool": "web_search", "query": ""},
        {
            "type": "search",
            "tool": "web_search",
            "query": "查询",
            "url": "https://example.com",
        },
        {"type": "fetch_url", "tool": "web_fetch", "url": ""},
        {
            "type": "fetch_url",
            "tool": "web_fetch",
            "url": "file:///tmp/report.html",
        },
        {
            "type": "fetch_url",
            "tool": "web_fetch",
            "url": "https://example.com",
            "query": "不应同时存在",
        },
    ],
)
def test_research_action_rejects_invalid_type_tool_and_payload_combinations(payload):
    with pytest.raises(ValueError):
        ResearchAction.model_validate(payload)


def test_search_action_name_remains_compatible():
    action = SearchAction(
        type="search",
        tool="web_search",
        query="查询",
    )

    assert isinstance(action, ResearchAction)


@pytest.mark.asyncio
async def test_reviewer_deduplicates_search_and_fetch_actions_by_protocol(monkeypatch):
    _stub_llm(
        monkeypatch,
        """
        {
          "base_version": 0,
          "sufficient": false,
          "gaps": [
            {"gap_id": "G1", "description": "缺口", "priority": "normal"}
          ],
          "actions": [
            {"type": "search", "tool": "web_search", "query": "查询"},
            {"type": "search", "tool": "web_search", "query": "查询"},
            {
              "type": "fetch_url",
              "tool": "web_fetch",
              "url": "https://example.com/a#section"
            },
            {
              "type": "fetch_url",
              "tool": "web_fetch",
              "url": "https://example.com/a"
            }
          ]
        }
        """,
    )

    result = await review_research(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        state=ResearchState(
            summary="当前总结",
            link_candidates=[
                {
                    "url": "https://example.com/a",
                    "title": "候选页",
                    "anchor": "查看",
                    "parent_url": "https://example.com",
                    "depth": 1,
                }
            ],
        ),
        source_ledger=SourceLedger(),
        max_actions=3,
    )

    assert [action.type for action in result.actions] == ["search", "fetch_url"]


@pytest.mark.asyncio
async def test_reviewer_rejects_fetch_url_outside_candidate_allowlist(monkeypatch):
    _stub_llm(
        monkeypatch,
        """
        {
          "base_version": 0,
          "sufficient": false,
          "gaps": [
            {"gap_id": "G1", "description": "缺口", "priority": "normal"}
          ],
          "actions": [
            {
              "type": "fetch_url",
              "tool": "web_fetch",
              "url": "https://example.com/invented"
            }
          ]
        }
        """,
    )

    result = await review_research(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        state=ResearchState(
            summary="当前总结",
            link_candidates=[
                {
                    "url": "https://example.com/allowed",
                    "title": "允许页",
                    "anchor": "查看",
                    "parent_url": "https://example.com",
                    "depth": 1,
                }
            ],
        ),
        source_ledger=SourceLedger(),
    )

    assert result.actions == []


def test_reviewer_normalizes_gap_status_aliases():
    gap = ResearchGap(
        gap_id="G1",
        description="缺口",
        priority="high",
        status="closed",
    )

    assert gap.status == "resolved"


@pytest.mark.asyncio
async def test_reviewer_uses_structured_output_and_prompt_coupled_max_tokens(
    monkeypatch,
):
    calls = []
    _stub_llm(
        monkeypatch,
        '{"base_version":0,"sufficient":true,"gaps":[],"actions":[]}',
        calls,
    )

    await review_research(
        query="问题",
        chapter_title="章节",
        chapter_content="任务" * 30000,
        state=ResearchState(summary="当前总结"),
        source_ledger=SourceLedger(),
    )

    call = calls[0]
    response_format = call["response_format"]
    schema = response_format["json_schema"]["schema"]

    assert response_format["type"] == "json_schema"
    assert response_format["json_schema"]["strict"] is True
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {
        "base_version",
        "sufficient",
        "gaps",
        "actions",
        "reason",
    }
    assert call["max_tokens"] > 6000


@pytest.mark.asyncio
async def test_reviewer_max_tokens_depends_on_input_length(monkeypatch):
    calls = []
    _stub_llm(
        monkeypatch,
        '{"base_version":0,"sufficient":true,"gaps":[],"actions":[]}',
        calls,
    )

    for chapter_content in ("短任务", "长任务" * 20000):
        await review_research(
            query="问题",
            chapter_title="章节",
            chapter_content=chapter_content,
            state=ResearchState(summary="当前总结"),
            source_ledger=SourceLedger(),
        )

    assert calls[1]["max_tokens"] > calls[0]["max_tokens"]
    assert calls[0]["max_tokens"] >= 6000


def test_reviewer_priority_unknown_does_not_raise_validation_error():
    gap = ResearchGap(gap_id="G1", description="缺口", priority="unexpected")

    assert gap.priority == "normal"


@pytest.mark.asyncio
async def test_reviewer_accepts_natural_enum_aliases(monkeypatch):
    _stub_llm(
        monkeypatch,
        """
        {
          "base_version": 0,
          "sufficient": false,
          "gaps": [
            {
              "gap_id": "G1",
              "description": "缺少官方发布时间",
              "priority": "urgent",
              "status": "active"
            }
          ],
          "actions": [
            {
              "type": "web search",
              "tool": "search_web",
              "query": "官方发布时间",
              "gap_ids": ["G1"]
            }
          ]
        }
        """,
    )

    result = await review_research(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        state=ResearchState(summary="当前总结"),
        source_ledger=SourceLedger(),
    )

    assert result.gaps[0].priority == "high"
    assert result.gaps[0].status == "open"
    assert result.actions[0].type == "search"
    assert result.actions[0].tool == "web_search"


@pytest.mark.asyncio
async def test_review_round_keeps_reviewer_result_as_shared_prefix(monkeypatch):
    calls = []
    _stub_llm(
        monkeypatch,
        """
        {
          "base_version": 0,
          "sufficient": false,
          "gaps": [{"gap_id": "G1", "description": "缺口", "priority": "high"}],
          "actions": [
            {
              "type": "search",
              "tool": "web_search",
              "query": "补充查询",
              "reason": "补齐缺口",
              "gap_ids": ["G1"]
            }
          ],
          "reason": "仍缺证据"
        }
        """,
        calls,
    )

    round_context = await review_research_round(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        state=ResearchState(summary="当前总结"),
        source_ledger=SourceLedger(),
    )

    assert calls[0]["messages"] == round_context.messages[:-1]
    assert round_context.messages[-1]["role"] == "assistant"
    assert json.loads(round_context.messages[-1]["content"]) == (
        round_context.result.model_dump(mode="json")
    )
