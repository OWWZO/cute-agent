# -*- coding: utf-8 -*-
import asyncio
import json

import pytest

from cute_tool.model.document import Doc
from cute_tool.model.research import (
    EditOp,
    EditPlan,
    ResearchAction,
    ResearchGap,
    ReviewResult,
    SearchAction,
)
from cute_tool.tool.deepsearch import ChapterState, DeepSearch
from cute_tool.tool.search_component.editor import EditParseError
from cute_tool.tool.search_component.reviewer import ReviewParseError, ReviewRound


async def _noop_noarg():
    return None


async def _noop_summary(summary, streaming):
    return None


def _review(*, query: str = "", sufficient: bool = False) -> ReviewResult:
    return ReviewResult(
        base_version=0,
        sufficient=sufficient,
        gaps=[
            ResearchGap(
                gap_id="G1",
                description="还缺证据",
                priority="high",
            )
        ]
        if not sufficient
        else [],
        actions=[]
        if sufficient
        else [
            SearchAction(
                type="search",
                tool="web_search",
                query=query or "补充查询",
                reason="补齐缺口",
                gap_ids=["G1"],
            )
        ],
    )


def _review_round(
    review: ReviewResult,
    *,
    summary: str,
) -> ReviewRound:
    return ReviewRound(
        result=review,
        messages=[
            {"role": "system", "content": "round-system"},
            {"role": "user", "content": f"current-summary:{summary}"},
        ],
    )


def _chapter() -> ChapterState:
    return ChapterState(
        chapter_id="C1",
        title="章节",
        content="章节任务",
        order=1,
        queries=["初始查询"],
        docs=[
            Doc(
                doc_type="web_page",
                title="initial",
                link="https://example.com/initial",
                content="initial evidence",
            )
        ],
        summary="S0",
    )


@pytest.mark.asyncio
async def test_iterative_flow_runs_two_rounds_then_forces_finish(monkeypatch):
    chapter = _chapter()
    reviews = [_review(query="第一轮"), _review(query="第二轮")]
    edits = [
        EditPlan(
            base_version=0,
            ops=[
                EditOp(
                    old_text="S0",
                    new_text="S1 [[1]](https://example.com/round1)",
                )
            ],
        ),
        EditPlan(
            base_version=1,
            ops=[
                EditOp(
                    old_text="S1",
                    new_text="S2 [[2]](https://example.com/round2)",
                )
            ],
        ),
    ]
    search_rounds = [
        [
            Doc(
                doc_type="web_page",
                title="round1",
                link="https://example.com/round1",
                content="round one evidence",
            )
        ],
        [
            Doc(
                doc_type="web_page",
                title="round2",
                link="https://example.com/round2",
                content="round two evidence",
            )
        ],
    ]
    emitted = []
    review_summaries = []
    editor_contexts = []

    async def fake_review(**kwargs):
        review_summaries.append(kwargs["state"].summary)
        return _review_round(reviews.pop(0), summary=kwargs["state"].summary)

    async def fake_editor(**kwargs):
        editor_contexts.append(kwargs["context_messages"])
        return edits.pop(0)

    async def fake_search(*, queries, request_id):
        return search_rounds.pop(0), []

    async def emit_search():
        emitted.append("search")

    async def emit_summary(summary, streaming):
        emitted.append(summary)

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.generate_edit_plan", fake_editor
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)

    await search._research_iteratively(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        max_research_rounds=2,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        emit_search=emit_search,
        emit_summary=emit_summary,
    )

    assert chapter.summary == (
        "S2 [[2]](https://example.com/round2) "
        "[[1]](https://example.com/round1)"
    )
    assert chapter.research_state.summary_version == 2
    assert chapter.research_state.iteration == 2
    assert chapter.research_state.stop_reason == "MAX_ROUNDS"
    assert emitted == [
        "search",
        "S1 [[1]](https://example.com/round1)",
        "search",
        "S2 [[2]](https://example.com/round2) "
        "[[1]](https://example.com/round1)",
    ]
    assert review_summaries == [
        "S0",
        "S1 [[1]](https://example.com/round1)",
    ]
    assert "current-summary:S0" in editor_contexts[0][1]["content"]
    assert "current-summary:S1" in editor_contexts[1][1]["content"]
    assert "S1" not in editor_contexts[0][1]["content"]


@pytest.mark.asyncio
async def test_iterative_flow_stops_when_reviewer_is_sufficient(monkeypatch):
    chapter = _chapter()
    calls = []

    async def fake_review(**kwargs):
        calls.append("review")
        return _review_round(
            _review(sufficient=True),
            summary=kwargs["state"].summary,
        )

    async def fake_editor(**kwargs):
        calls.append("editor")
        raise AssertionError("editor should not be called")

    async def fake_search(**kwargs):
        calls.append("search")
        raise AssertionError("search should not be called")

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.generate_edit_plan", fake_editor
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)

    await search._research_iteratively(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        max_research_rounds=3,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        emit_search=_noop_noarg,
        emit_summary=_noop_summary,
    )

    assert chapter.summary == "S0"
    assert chapter.research_state.stop_reason == "SUFFICIENT"
    assert calls == ["review"]


@pytest.mark.asyncio
async def test_iterative_flow_stops_on_no_action(monkeypatch):
    chapter = _chapter()

    async def fake_review(**kwargs):
        return _review_round(
            ReviewResult(
            base_version=0,
            sufficient=False,
            gaps=[],
            actions=[],
            ),
            summary=kwargs["state"].summary,
        )

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    search = DeepSearch(engines=["ddg"])

    await search._research_iteratively(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        max_research_rounds=2,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        emit_search=_noop_noarg,
        emit_summary=_noop_summary,
    )

    assert chapter.research_state.stop_reason == "NO_ACTION"


@pytest.mark.asyncio
async def test_iterative_flow_stops_when_search_has_no_new_evidence(monkeypatch):
    chapter = _chapter()

    async def fake_review(**kwargs):
        return _review_round(
            _review(query="无结果查询"),
            summary=kwargs["state"].summary,
        )

    async def fake_search(*, queries, request_id):
        return [], []

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)

    await search._research_iteratively(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        max_research_rounds=2,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        emit_search=_noop_noarg,
        emit_summary=_noop_summary,
    )

    assert chapter.research_state.stop_reason == "NO_NEW_EVIDENCE"


@pytest.mark.asyncio
async def test_iterative_flow_retries_editor_with_same_base_version(monkeypatch):
    chapter = _chapter()
    editor_inputs = []
    attempts = [
        EditPlan(
            base_version=0,
            ops=[EditOp(old_text="不存在", new_text="x")],
        ),
        EditPlan(
            base_version=0,
            ops=[
                EditOp(
                    old_text="S0",
                    new_text="S1 [[1]](https://example.com/result)",
                )
            ],
        ),
    ]

    async def fake_review(**kwargs):
        return _review_round(
            _review(query="补搜"),
            summary=kwargs["state"].summary,
        )

    async def fake_editor(**kwargs):
        editor_inputs.append(kwargs)
        return attempts.pop(0)

    async def fake_search(*, queries, request_id):
        return (
            [
                Doc(
                    doc_type="web_page",
                    title="result",
                    link="https://example.com/result",
                    content="new evidence",
                )
            ],
            [],
        )

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.generate_edit_plan", fake_editor
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)

    await search._research_iteratively(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        max_research_rounds=1,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        emit_search=_noop_noarg,
        emit_summary=_noop_summary,
    )

    assert chapter.summary == "S1 [[1]](https://example.com/result)"
    assert len(editor_inputs) == 2
    assert editor_inputs[0]["base_version"] == 0
    assert editor_inputs[1]["base_version"] == 0
    assert editor_inputs[1]["previous_edit_failure"]
    assert chapter.research_state.stop_reason == "MAX_ROUNDS"


@pytest.mark.asyncio
async def test_iterative_flow_continues_after_editor_failures(monkeypatch):
    chapter = _chapter()
    calls = []
    search_round = [0]
    review_round = [0]

    async def fake_review(**kwargs):
        review_round[0] += 1
        return _review_round(
            _review(query=f"补搜{review_round[0]}"),
            summary=kwargs["state"].summary,
        )

    async def fake_editor(**kwargs):
        calls.append(kwargs)
        raise EditParseError("bad editor json")

    async def fake_search(*, queries, request_id):
        search_round[0] += 1
        return (
            [
                Doc(
                    doc_type="web_page",
                    title="result",
                    link=f"https://example.com/result-{search_round[0]}",
                    content=f"new evidence {search_round[0]}",
                )
            ],
            [],
        )

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.generate_edit_plan", fake_editor
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)

    await search._research_iteratively(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        max_research_rounds=2,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        emit_search=_noop_noarg,
        emit_summary=_noop_summary,
    )

    assert chapter.summary == "S0"
    assert chapter.research_state.summary_version == 0
    assert chapter.research_state.stop_reason == "NO_SUCCESSFUL_EDIT"
    assert chapter.research_state.iteration == 2
    assert len(calls) == 4


@pytest.mark.asyncio
async def test_iterative_flow_recovers_after_first_round_review_failure(monkeypatch):
    chapter = _chapter()
    review_calls = []
    search_round = [0]
    review_round = [0]
    editor_plans = [
        EditPlan(
            base_version=0,
            ops=[
                EditOp(
                    old_text="S0",
                    new_text="S2 [[1]](https://example.com/result-1)",
                )
            ],
        ),
    ]

    async def fake_review(**kwargs):
        review_calls.append("review")
        if len(review_calls) == 1:
            raise ReviewParseError("empty reviewer response after retry")
        review_round[0] += 1
        return _review_round(
            _review(
                query=f"补搜{review_round[0]}",
                sufficient=review_round[0] > 1,
            ),
            summary=kwargs["state"].summary,
        )

    async def fake_editor(**kwargs):
        return editor_plans.pop(0)

    async def fake_search(*, queries, request_id):
        search_round[0] += 1
        return (
            [
                Doc(
                    doc_type="web_page",
                    title="result",
                    link=f"https://example.com/result-{search_round[0]}",
                    content=f"new evidence {search_round[0]}",
                )
            ],
            [],
        )

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.generate_edit_plan", fake_editor
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)

    await search._research_iteratively(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        max_research_rounds=3,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        emit_search=_noop_noarg,
        emit_summary=_noop_summary,
    )

    assert chapter.summary == "S2 [[1]](https://example.com/result-1)"
    assert chapter.research_state.summary_version == 1
    assert chapter.research_state.iteration == 3
    assert chapter.research_state.stop_reason == "SUFFICIENT"
    assert len(review_calls) == 3


@pytest.mark.asyncio
async def test_iterative_flow_continues_after_first_round_editor_failure(monkeypatch):
    chapter = _chapter()
    editor_calls = []
    search_round = [0]
    review_round = [0]

    async def fake_review(**kwargs):
        review_round[0] += 1
        return _review_round(
            _review(query=f"补搜{review_round[0]}"),
            summary=kwargs["state"].summary,
        )

    async def fake_editor(**kwargs):
        editor_calls.append(kwargs)
        if len(editor_calls) <= 2:
            raise EditParseError("empty editor response after retry")
        return EditPlan(
            base_version=kwargs["base_version"],
            ops=[
                EditOp(
                    old_text="S0",
                    new_text="S1 [[1]](https://example.com/result-1)",
                )
            ],
        )

    async def fake_search(*, queries, request_id):
        search_round[0] += 1
        return (
            [
                Doc(
                    doc_type="web_page",
                    title="result",
                    link=f"https://example.com/result-{search_round[0]}",
                    content=f"new evidence {search_round[0]}",
                )
            ],
            [],
        )

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.generate_edit_plan", fake_editor
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)

    await search._research_iteratively(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        max_research_rounds=2,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        emit_search=_noop_noarg,
        emit_summary=_noop_summary,
    )

    assert chapter.summary == "S1 [[1]](https://example.com/result-1)"
    assert chapter.research_state.summary_version == 1
    assert chapter.research_state.iteration == 2
    assert chapter.research_state.stop_reason == "MAX_ROUNDS"
    assert len(editor_calls) == 3


@pytest.mark.asyncio
async def test_iterative_flow_stops_on_empty_editor_ops(monkeypatch):
    chapter = _chapter()

    async def fake_review(**kwargs):
        return _review_round(
            _review(query="补搜"),
            summary=kwargs["state"].summary,
        )

    async def fake_editor(**kwargs):
        return EditPlan(base_version=0, ops=[])

    async def fake_search(*, queries, request_id):
        return (
            [
                Doc(
                    doc_type="web_page",
                    title="result",
                    link="https://example.com/result",
                    content="new evidence",
                )
            ],
            [],
        )

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.generate_edit_plan", fake_editor
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)

    await search._research_iteratively(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        max_research_rounds=2,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        emit_search=_noop_noarg,
        emit_summary=_noop_summary,
    )

    assert chapter.summary == "S0"
    assert chapter.research_state.stop_reason == "EDIT_NOOP"
    assert chapter.source_ledger.dropped_source_ids == ["R1-S1"]


@pytest.mark.asyncio
async def test_search_queries_continue_after_one_query_fails():
    search = DeepSearch(engines=["ddg"])

    async def fake_search(query, request_id):
        if query == "bad":
            raise RuntimeError("search failed")
        return [
            Doc(
                doc_type="web_page",
                title="good",
                link="https://example.com/good",
                content="good content",
            )
        ]

    search._search_single_query = fake_search
    docs, groups = await search._search_queries_and_dedup(
        ["bad", "good"], request_id="req"
    )

    assert [doc.title for doc in docs] == ["good"]
    assert groups == [[], docs]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "env_value,expected",
    [("0", False), ("1", True)],
)
async def test_run_reads_iterative_switch(monkeypatch, env_value, expected):
    search = DeepSearch(engines=["ddg"])
    captured = {}

    async def fake_query_decompose(query):
        return [{"title": "章节", "content": "任务", "search_queries": ["查询"]}]

    async def fake_iter_events(**kwargs):
        captured.update(kwargs)
        if False:
            yield ""

    async def fake_merge(query, chapter_sections):
        yield "报告"

    monkeypatch.setenv("DEEPSEARCH_ITERATIVE_EDITOR", env_value)
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.query_decompose", fake_query_decompose
    )
    monkeypatch.setattr(search, "_iter_research_events", fake_iter_events)
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.merge_chapters", fake_merge
    )

    events = [json.loads(event) async for event in search.run("问题")]

    assert captured["enable_iterative_editor"] is expected
    assert events[-1]["messageType"] == "report"
    assert events[-1]["answer"] == "报告"


@pytest.mark.asyncio
async def test_iterative_switch_bypasses_light_reflection(monkeypatch):
    chapter = _chapter()
    queue = asyncio.Queue()

    async def fake_summary_stream(**kwargs):
        yield "S0"

    async def fake_search(*, queries, request_id):
        return chapter.docs, []

    async def fake_review(**kwargs):
        return _review_round(
            _review(sufficient=True),
            summary=kwargs["state"].summary,
        )

    async def fake_reasoning(**kwargs):
        raise AssertionError("light reflection should be bypassed")

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.summarize_chapter_stream",
        fake_summary_stream,
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.docs_to_html", lambda docs, model=None: "docs"
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.search_reasoning", fake_reasoning
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)

    await search._research_one_chapter(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        enable_light_reflection=True,
        enable_iterative_editor=True,
        max_research_rounds=2,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        event_queue=queue,
        flush_every=5,
    )

    assert chapter.research_state.stop_reason == "SUFFICIENT"


@pytest.mark.asyncio
async def test_disabled_iterative_switch_keeps_light_reflection_path(monkeypatch):
    chapter = _chapter()
    queue = asyncio.Queue()
    reasoning_calls = []

    async def fake_summary_stream(**kwargs):
        yield "S0"

    async def fake_search(*, queries, request_id):
        return (
            [
                Doc(
                    doc_type="web_page",
                    title="extra",
                    link="https://example.com/extra",
                    content="extra evidence",
                )
            ],
            [],
        )

    async def fake_reasoning(**kwargs):
        reasoning_calls.append(kwargs)
        return {"is_verify": "0", "rewrite_query": "补搜"}

    async def fake_update(**kwargs):
        return "S1"

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.summarize_chapter_stream",
        fake_summary_stream,
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.docs_to_html", lambda docs, model=None: "docs"
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.search_reasoning", fake_reasoning
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.update_chapter_summary", fake_update
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)

    await search._research_one_chapter(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        enable_light_reflection=True,
        enable_iterative_editor=False,
        max_research_rounds=2,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        event_queue=queue,
        flush_every=5,
    )

    assert chapter.summary == "S1"
    assert chapter.research_state is None
    assert len(reasoning_calls) == 1


@pytest.mark.asyncio
async def test_iterative_flow_fetches_reviewer_selected_candidate_url(monkeypatch):
    chapter = _chapter()
    chapter.link_candidates = [
        {
            "url": "https://example.com/candidate",
            "title": "候选公告",
            "anchor": "查看公告",
            "parent_url": "https://example.com/initial",
            "depth": 1,
        }
    ]
    review = ReviewResult(
        base_version=0,
        sufficient=False,
        gaps=[
            ResearchGap(
                gap_id="G1",
                description="缺少候选公告正文",
                priority="high",
            )
        ],
        actions=[
            ResearchAction(
                type="fetch_url",
                tool="web_fetch",
                url="https://example.com/candidate",
                reason="补齐公告正文",
                gap_ids=["G1"],
            )
        ],
    )
    fetched = []

    async def fake_review(**kwargs):
        return _review_round(review, summary=kwargs["state"].summary)

    async def fake_editor(**kwargs):
        return EditPlan(
            base_version=0,
            ops=[
                EditOp(
                    old_text="S0",
                    new_text="S1 [[1]](https://example.com/candidate)",
                )
            ],
        )

    async def fake_search(*, queries, request_id):
        assert queries == []
        return [], []

    async def fake_url_get(url, **kwargs):
        fetched.append((url, kwargs))
        return Doc(
            doc_type="web_page",
            title="候选公告",
            link="https://example.com/candidate",
            content="候选公告正文",
            data={
                "origin": "link",
                "depth": 1,
                "parent_url": "https://example.com/initial",
                "link_candidates": [],
            },
        )

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.review_research_round", fake_review
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.generate_edit_plan", fake_editor
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)
    monkeypatch.setattr(search._url_fetch_cache, "get", fake_url_get)

    await search._research_iteratively(
        query="用户问题",
        chapter=chapter,
        request_id="req",
        max_research_rounds=1,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        emit_search=_noop_noarg,
        emit_summary=_noop_summary,
    )

    assert fetched[0][0] == "https://example.com/candidate"
    assert fetched[0][1]["origin"] == "link"
    assert fetched[0][1]["depth"] == 1
    assert chapter.summary == "S1 [[1]](https://example.com/candidate)"
    assert any(doc.link == "https://example.com/candidate" for doc in chapter.docs)


@pytest.mark.asyncio
async def test_initial_chapter_actions_combine_search_and_seed_url(monkeypatch):
    chapter = ChapterState(
        chapter_id="C1",
        title="官方资料研究",
        content="读取用户 URL 并补充搜索",
        order=1,
        actions=[
            ResearchAction(
                type="fetch_url",
                tool="web_fetch",
                url="https://example.com/seed",
            ),
            ResearchAction(
                type="search",
                tool="web_search",
                query="官方补充资料",
            ),
        ],
    )
    queue = asyncio.Queue()
    captured_context = []

    async def fake_search(*, queries, request_id):
        assert queries == ["官方补充资料"]
        return (
            [
                Doc(
                    doc_type="web_page",
                    title="搜索结果",
                    link="https://search.example/result",
                    content="搜索证据",
                )
            ],
            [],
        )

    async def fake_url_get(url, **kwargs):
        assert url == "https://example.com/seed"
        return Doc(
            doc_type="web_page",
            title="用户网页",
            link="https://example.com/seed",
            content="用户网页正文",
            data={"link_candidates": []},
        )

    async def fake_summary_stream(**kwargs):
        captured_context.append(kwargs["search_content"])
        yield "章节总结"

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.summarize_chapter_stream",
        fake_summary_stream,
    )
    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.docs_to_html",
        lambda docs, model=None: "|".join(doc.link for doc in docs),
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)
    monkeypatch.setattr(search._url_fetch_cache, "get", fake_url_get)

    await search._research_one_chapter(
        query="参考 https://example.com/seed 研究生题",
        chapter=chapter,
        request_id="req",
        enable_light_reflection=False,
        enable_iterative_editor=False,
        max_research_rounds=2,
        max_actions_per_round=3,
        remaining_timeout=lambda: 5,
        event_queue=queue,
        flush_every=5,
    )

    assert "https://example.com/seed" in captured_context[0]
    assert "https://search.example/result" in captured_context[0]
    assert chapter.url_fetch_count == 1
    assert chapter.summary == "章节总结"


@pytest.mark.asyncio
async def test_seed_url_document_takes_precedence_over_same_search_url(monkeypatch):
    chapter = ChapterState(
        chapter_id="C1",
        title="官方资料研究",
        content="读取用户 URL",
        order=1,
        actions=[
            ResearchAction(
                type="fetch_url",
                tool="web_fetch",
                url="https://example.com/same",
            )
        ],
    )

    async def fake_search(*, queries, request_id):
        return (
            [
                Doc(
                    doc_type="web_page",
                    title="搜索摘要",
                    link="https://example.com/same",
                    content="搜索摘要正文",
                    data={"origin": "search"},
                )
            ],
            [],
        )

    async def fake_url_get(url, **kwargs):
        return Doc(
            doc_type="web_page",
            title="精确抓取",
            link="https://example.com/same",
            content="精确抓取正文",
            data={
                "origin": "seed",
                "depth": 0,
                "link_candidates": [
                    {
                        "url": "https://example.com/next",
                        "title": "下一篇",
                        "anchor": "继续",
                        "parent_url": "https://example.com/same",
                        "depth": 1,
                    }
                ],
            },
        )

    monkeypatch.setattr(
        "cute_tool.tool.deepsearch.docs_to_html",
        lambda docs, model=None: "|".join(doc.content for doc in docs),
    )
    search = DeepSearch(engines=["ddg"])
    monkeypatch.setattr(search, "_search_queries_and_dedup", fake_search)
    monkeypatch.setattr(search._url_fetch_cache, "get", fake_url_get)

    docs = await search._execute_research_actions(
        chapter=chapter,
        actions=chapter.actions,
        request_id="req",
        allowed_urls={"https://example.com/same"},
        initial=True,
    )

    assert [doc.content for doc in docs] == ["精确抓取正文"]
    assert chapter.link_candidates[0]["url"] == "https://example.com/next"
