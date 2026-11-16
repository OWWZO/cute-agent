# -*- coding: utf-8 -*-
# =====================
#
# Author: wanghanmin1
# Date:   2025/7/8
# =====================
"""深度搜索（DeepSearch）主流程。

链路：查询拆解并生成章节搜索词 → 分章并发检索 → 分章总结
→（可选有界 Review/Search/Edit）→ 合并终稿。
依赖 search_component 子模块与 MixSearch 混合检索引擎。
"""

import asyncio
import json
import os
import time
from dataclasses import dataclass, field
from functools import partial
from typing import List, AsyncGenerator, Tuple

from cute_tool.util.log_util import logger
from cute_tool.model.document import Doc
from cute_tool.util.log_util import timer
from cute_tool.tool.search_component.query_process import (
    chapter_research_actions,
    extract_query_urls,
    normalize_search_queries,
    query_decompose,
)
from cute_tool.tool.search_component.chapter import (
    docs_to_html,
    summarize_chapter_stream,
    update_chapter_summary,
    merge_chapters,
)
from cute_tool.tool.search_component.reasoning import search_reasoning
from cute_tool.tool.search_component.search_engine import MixSearch
from cute_tool.model.research import ResearchAction, ResearchState
from cute_tool.tool.search_component.editor import (
    EditParseError,
    generate_edit_plan,
)
from cute_tool.tool.search_component.reviewer import (
    ReviewParseError,
    review_research_round,
)
from cute_tool.tool.search_component.source_ledger import SourceLedger
from cute_tool.tool.search_component.source_ledger import normalize_url
from cute_tool.tool.search_component.summary_edit import (
    EditApplyError,
    apply_edit_plan,
)
from cute_tool.tool.search_component.url_fetch import UrlFetchCache, UrlSafetyError
from cute_tool.model.protocal import StreamMode


@dataclass
class ChapterState:
    """单章研究状态。"""

    chapter_id: str
    title: str
    content: str
    order: int
    queries: List[str] = field(default_factory=list)
    actions: List[ResearchAction] = field(default_factory=list)
    link_candidates: List[dict] = field(default_factory=list)
    url_fetch_count: int = 0
    docs: List[Doc] = field(default_factory=list)
    summary: str = ""
    status: str = "pending"
    error: str = ""
    research_state: ResearchState | None = None
    source_ledger: SourceLedger | None = None


class DeepSearch:
    """深度搜索工具：章节拆解 + 并发分章研究 + 轻量反思 + 合并回答。"""

    def __init__(self, engines: List[str] = []):
        """初始化搜索引擎开关；未传 engines 时读 USE_SEARCH_ENGINE 环境变量。"""
        normalized_engines = [
            engine.strip().lower() for engine in engines if engine and engine.strip()
        ]
        if not normalized_engines:
            env_value = os.getenv("USE_SEARCH_ENGINE", "ddg")
            normalized_engines = [
                engine.strip().lower()
                for engine in env_value.split(",")
                if engine.strip()
            ]
        if not normalized_engines:
            normalized_engines = ["ddg"]

        self.engines = normalized_engines
        use_ddg = "ddg" in normalized_engines
        use_bing = "bing" in normalized_engines
        use_jina = "jina" in normalized_engines
        use_sogou = "sogou" in normalized_engines
        use_serp = "serp" in normalized_engines
        use_exa = "exa" in normalized_engines
        self._search_single_query = partial(
            MixSearch().search_and_dedup,
            use_ddg=use_ddg,
            use_bing=use_bing,
            use_jina=use_jina,
            use_sogou=use_sogou,
            use_serp=use_serp,
            use_exa=use_exa,
            use_jina_reader=False,
        )
        self.searched_queries: List[str] = []
        self.current_docs: List[Doc] = []
        self._url_fetch_cache = self._new_url_fetch_cache()

    @timer("deepsearch", level="info")
    async def run(
        self,
        query: str,
        request_id: str = None,
        max_loop: int = 1,
        stream: bool = False,
        stream_mode: StreamMode = StreamMode(),
        *args,
        **kwargs,
    ) -> AsyncGenerator[str, None]:
        """深度搜索主循环（流式 yield SSE 数据片段）。"""

        total_timeout_seconds = int(
            os.getenv("DEEPSEARCH_TOTAL_TIMEOUT_SECONDS", "1200")
        )
        deadline = time.monotonic() + total_timeout_seconds
        self._url_fetch_cache = self._new_url_fetch_cache()

        def _remaining_timeout() -> float:
            return max(0.1, deadline - time.monotonic())

        # 轻量反思默认关闭；需要时设 DEEPSEARCH_LIGHT_REFLECTION=1
        _ = max_loop
        enable_light_reflection = str(
            os.getenv("DEEPSEARCH_LIGHT_REFLECTION", "0")
        ).lower() in ("1", "true", "yes")
        enable_iterative_editor = str(
            os.getenv("DEEPSEARCH_ITERATIVE_EDITOR", "0")
        ).lower() in ("1", "true", "yes")
        max_research_rounds = min(
            2, max(1, int(os.getenv("DEEPSEARCH_MAX_RESEARCH_ROUNDS", "2")))
        )
        max_actions_per_round = min(
            3, max(1, int(os.getenv("DEEPSEARCH_MAX_ACTIONS_PER_ROUND", "3")))
        )

        try:
            sub_queries = await asyncio.wait_for(
                query_decompose(query=query),
                timeout=_remaining_timeout(),
            )
            if not sub_queries:
                sub_queries = [{"title": query, "content": query}]

            chapters = []
            for idx, item in enumerate(sub_queries, start=1):
                title = str(item.get("title") or "").strip()
                if not title:
                    continue
                content = str(item.get("content") or title).strip()
                actions = chapter_research_actions(item)
                if not actions and content:
                    actions = [
                        ResearchAction(
                            type="search",
                            tool="web_search",
                            query=content,
                        )
                    ]
                chapters.append(
                    ChapterState(
                        chapter_id=f"C{idx}",
                        title=title,
                        content=content,
                        order=idx,
                        queries=normalize_search_queries(
                            [
                                action.query
                                for action in actions
                                if action.type == "search"
                            ]
                        ),
                        actions=actions,
                    )
                )

            yield json.dumps(
                {
                    "requestId": request_id,
                    "query": query,
                    "searchResult": {
                        "query": [c.title for c in chapters],
                        "chapters": [
                            {
                                "chapterId": c.chapter_id,
                                "chapterTitle": c.title,
                                "chapterContent": c.content,
                                "chapterOrder": c.order,
                                "queries": list(c.queries),
                            }
                            for c in chapters
                        ],
                        "docs": [[] for _ in chapters],
                    },
                    "isFinal": False,
                    "messageType": "extend",
                },
                ensure_ascii=False,
            )

            await asyncio.sleep(0.1)

            truncate_len = int(os.getenv("SINGLE_PAGE_MAX_SIZE", 200))
            flush_every = max(1, int(getattr(stream_mode, "token", None) or 5))
            async for event in self._iter_research_events(
                query=query,
                chapters=chapters,
                request_id=request_id,
                enable_light_reflection=enable_light_reflection,
                enable_iterative_editor=enable_iterative_editor,
                max_research_rounds=max_research_rounds,
                max_actions_per_round=max_actions_per_round,
                remaining_timeout=_remaining_timeout,
                truncate_len=truncate_len,
                flush_every=flush_every,
            ):
                yield event

            for chapter in chapters:
                self.current_docs.extend(chapter.docs)
                self.searched_queries.extend(chapter.queries)

            chapter_sections = [
                {
                    "id": c.chapter_id,
                    "title": c.title,
                    "content": c.content,
                    "order": c.order,
                    "summary": c.summary or f"（章节「{c.title}」暂无可用总结）",
                }
                for c in chapters
            ]

            answer = ""
            acc_content = ""
            acc_token = 0
            answer_stream = merge_chapters(
                query=query, chapter_sections=chapter_sections
            )
            while True:
                try:
                    chunk = await asyncio.wait_for(
                        answer_stream.__anext__(),
                        timeout=_remaining_timeout(),
                    )
                except StopAsyncIteration:
                    break

                if stream:
                    if acc_token >= stream_mode.token:
                        yield json.dumps(
                            {
                                "requestId": request_id,
                                "query": query,
                                "searchResult": {"query": [], "docs": []},
                                "answer": acc_content,
                                "isFinal": False,
                                "messageType": "report",
                            },
                            ensure_ascii=False,
                        )
                        acc_content = ""
                        acc_token = 0
                    acc_content += chunk
                    acc_token += 1
                answer += chunk

            if stream and acc_content:
                yield json.dumps(
                    {
                        "requestId": request_id,
                        "query": query,
                        "searchResult": {"query": [], "docs": []},
                        "answer": acc_content,
                        "isFinal": False,
                        "messageType": "report",
                    },
                    ensure_ascii=False,
                )

            yield json.dumps(
                {
                    "requestId": request_id,
                    "query": query,
                    "searchResult": {"query": [], "docs": []},
                    "answer": "" if stream else answer,
                    "isFinal": True,
                    "messageType": "report",
                },
                ensure_ascii=False,
            )

        except asyncio.TimeoutError:
            logger.warning(
                f"{request_id} deepsearch total timeout after {total_timeout_seconds}s"
            )
            fallback_answer = (
                "深度搜索超时，已返回当前可用结果，请基于已有搜索内容继续处理。"
            )
            yield json.dumps(
                {
                    "requestId": request_id,
                    "query": query,
                    "searchResult": {"query": [], "docs": []},
                    "answer": fallback_answer,
                    "isFinal": True,
                    "messageType": "report",
                },
                ensure_ascii=False,
            )

    def _dumps_search_event(
        self,
        query: str,
        request_id: str,
        chapters: List[ChapterState],
        truncate_len: int,
    ) -> str:
        """按章节顺序输出累计检索结果，前端按 query 原位更新卡片。"""
        queries = []
        docs = []
        for chapter in chapters:
            chapter_queries = self._chapter_display_queries(chapter)
            for index, chapter_query in enumerate(chapter_queries):
                queries.append(chapter_query)
                docs.append(
                    [doc.to_dict(truncate_len=truncate_len) for doc in chapter.docs]
                    if index == 0
                    else []
                )
        return json.dumps(
            {
                "requestId": request_id,
                "query": query,
                "searchResult": {
                    "query": queries,
                    "docs": docs,
                    "chapters": [
                        {
                            "chapterId": chapter.chapter_id,
                            "chapterTitle": chapter.title,
                            "chapterContent": chapter.content,
                            "chapterOrder": chapter.order,
                            "queries": self._chapter_display_queries(chapter),
                        }
                        for chapter in chapters
                    ],
                },
                "isFinal": False,
                "messageType": "search",
            },
            ensure_ascii=False,
        )

    def _dumps_chapter_summary_event(
        self,
        query: str,
        request_id: str,
        chapter: ChapterState,
        summary: str,
        truncate_len: int,
        streaming: bool,
    ) -> str:
        chapter_queries = self._chapter_display_queries(chapter)
        chapter_docs_groups = [
            [d.to_dict(truncate_len=truncate_len) for d in chapter.docs]
        ]
        while len(chapter_docs_groups) < len(chapter_queries):
            chapter_docs_groups.append([])
        return json.dumps(
            {
                "requestId": request_id,
                "query": query,
                "chapterId": chapter.chapter_id,
                "chapterTitle": chapter.title,
                "chapterContent": chapter.content,
                "chapterOrder": chapter.order,
                "chapterSummary": summary,
                "chapterStreaming": streaming,
                "answer": summary,
                "searchResult": {
                    "query": chapter_queries,
                    "docs": chapter_docs_groups,
                },
                "isFinal": False,
                "messageType": "chapter_summary",
            },
            ensure_ascii=False,
        )

    async def _iter_research_events(
        self,
        query: str,
        chapters: List[ChapterState],
        request_id: str,
        enable_light_reflection: bool,
        enable_iterative_editor: bool,
        max_research_rounds: int,
        max_actions_per_round: int,
        remaining_timeout,
        truncate_len: int,
        flush_every: int,
    ) -> AsyncGenerator[str, None]:
        """并发研究各章，检索完成和总结增量立刻推给前端。"""
        max_concurrent = max(
            1, int(os.getenv("CHAPTER_THREAD_NUM", os.getenv("SEARCH_THREAD_NUM", 5)))
        )
        semaphore = asyncio.Semaphore(max_concurrent)
        event_queue: asyncio.Queue = asyncio.Queue()
        chapter_by_id = {chapter.chapter_id: chapter for chapter in chapters}

        async def _one(chapter: ChapterState) -> None:
            async with semaphore:
                try:
                    await self._research_one_chapter(
                        query=query,
                        chapter=chapter,
                        request_id=request_id,
                        enable_light_reflection=enable_light_reflection,
                        enable_iterative_editor=enable_iterative_editor,
                        max_research_rounds=max_research_rounds,
                        max_actions_per_round=max_actions_per_round,
                        remaining_timeout=remaining_timeout,
                        event_queue=event_queue,
                        flush_every=flush_every,
                    )
                finally:
                    await event_queue.put({"kind": "done"})

        workers = [asyncio.create_task(_one(chapter)) for chapter in chapters]
        finished = 0
        while finished < len(chapters):
            item = await event_queue.get()
            kind = item.get("kind")
            if kind == "done":
                finished += 1
                continue
            if kind == "search":
                yield self._dumps_search_event(
                    query, request_id, chapters, truncate_len
                )
                continue
            if kind == "chapter_summary":
                chapter = chapter_by_id.get(item.get("chapter_id"))
                if chapter is None:
                    continue
                yield self._dumps_chapter_summary_event(
                    query,
                    request_id,
                    chapter,
                    item.get("summary") or "",
                    truncate_len,
                    bool(item.get("streaming")),
                )

        await asyncio.gather(*workers, return_exceptions=True)

    async def _research_one_chapter(
        self,
        query: str,
        chapter: ChapterState,
        request_id: str,
        enable_light_reflection: bool,
        enable_iterative_editor: bool,
        max_research_rounds: int,
        max_actions_per_round: int,
        remaining_timeout,
        event_queue: asyncio.Queue,
        flush_every: int,
    ) -> ChapterState:
        """单章：初搜 → 流式总结 →（可选）轻量反思补搜 → 更新总结。"""
        answer_model = os.getenv("SEARCH_ANSWER_MODEL", "gpt-4.1")

        async def _emit_search() -> None:
            await event_queue.put({"kind": "search"})

        async def _emit_summary(summary: str, streaming: bool) -> None:
            await event_queue.put(
                {
                    "kind": "chapter_summary",
                    "chapter_id": chapter.chapter_id,
                    "summary": summary,
                    "streaming": streaming,
                }
            )

        try:
            if not chapter.actions:
                fallback_query = (
                    chapter.queries[0]
                    if chapter.queries
                    else chapter.content or query
                )
                chapter.actions = [
                    ResearchAction(
                        type="search",
                        tool="web_search",
                        query=fallback_query,
                    )
                ]
            chapter.queries = normalize_search_queries(
                [
                    action.query
                    for action in chapter.actions
                    if action.type == "search"
                ]
            )
            docs = await asyncio.wait_for(
                self._execute_research_actions(
                    chapter=chapter,
                    actions=chapter.actions,
                    request_id=request_id,
                    allowed_urls=set(extract_query_urls(query)),
                    initial=True,
                ),
                timeout=remaining_timeout(),
            )
            chapter.docs = docs
            chapter.status = "searched"
            await _emit_search()

            acc = ""
            pending = 0
            stream = summarize_chapter_stream(
                query=query,
                chapter_title=chapter.title,
                chapter_content=chapter.content,
                chapter_order=chapter.order,
                search_content=docs_to_html(docs, model=answer_model),
            )
            while True:
                try:
                    chunk = await asyncio.wait_for(
                        stream.__anext__(),
                        timeout=remaining_timeout(),
                    )
                except StopAsyncIteration:
                    break
                if not chunk:
                    continue
                acc += chunk
                pending += 1
                chapter.summary = acc
                if pending >= flush_every:
                    await _emit_summary(acc, True)
                    pending = 0
            chapter.summary = acc
            chapter.status = "summarized"
            await _emit_summary(acc, False)

            if enable_iterative_editor:
                await self._research_iteratively(
                    query=query,
                    chapter=chapter,
                    request_id=request_id,
                    max_research_rounds=max_research_rounds,
                    max_actions_per_round=max_actions_per_round,
                    remaining_timeout=remaining_timeout,
                    emit_search=_emit_search,
                    emit_summary=_emit_summary,
                )
            elif enable_light_reflection and chapter.summary:
                reasoning_result = await asyncio.wait_for(
                    search_reasoning(
                        request_id=request_id,
                        query=f"{query} / 章节：{chapter.title}",
                        content=chapter.summary,
                        history_query_list=list(chapter.queries),
                    ),
                    timeout=remaining_timeout(),
                )
                if reasoning_result.get("is_verify", "1") not in ["1", 1]:
                    rewrite_query = (
                        reasoning_result.get("rewrite_query") or ""
                    ).strip()
                    if rewrite_query and rewrite_query not in chapter.queries:
                        more_docs, _ = await asyncio.wait_for(
                            self._search_queries_and_dedup(
                                queries=[rewrite_query],
                                request_id=request_id,
                            ),
                            timeout=remaining_timeout(),
                        )
                        chapter.queries.append(rewrite_query)
                        seen = {d.content for d in chapter.docs if d.content}
                        for doc in more_docs:
                            if doc.content and doc.content not in seen:
                                chapter.docs.append(doc)
                                seen.add(doc.content)
                        await _emit_search()
                        chapter.summary = await asyncio.wait_for(
                            update_chapter_summary(
                                query=query,
                                chapter_title=chapter.title,
                                chapter_content=chapter.content,
                                chapter_order=chapter.order,
                                previous_summary=chapter.summary,
                                search_content=docs_to_html(
                                    more_docs, model=answer_model
                                ),
                            ),
                            timeout=remaining_timeout(),
                        )
                        chapter.status = "reflected"
                        await _emit_summary(chapter.summary, False)
                    else:
                        logger.info(
                            f"{request_id} chapter={chapter.chapter_id} 反思认为不足但无有效 rewrite_query"
                        )

            chapter.status = "completed"
            return chapter
        except Exception as exc:
            logger.exception(
                f"{request_id} chapter={chapter.chapter_id} research failed: {exc}"
            )
            chapter.status = "failed"
            chapter.error = str(exc)
            if not chapter.summary:
                chapter.summary = f"章节「{chapter.title}」研究失败：{exc}"
            await _emit_summary(chapter.summary, False)
            return chapter

    async def _research_iteratively(
        self,
        *,
        query: str,
        chapter: ChapterState,
        request_id: str,
        max_research_rounds: int,
        max_actions_per_round: int,
        remaining_timeout,
        emit_search,
        emit_summary,
    ) -> None:
        """执行有界 Review → Search → Edit 循环。"""
        ledger = SourceLedger()
        default_query = (chapter.queries or [chapter.content or chapter.title])[0]
        ledger.add_documents(chapter.docs, round_no=0, default_query=default_query)
        state = ResearchState(
            summary=chapter.summary,
            searched_queries=list(chapter.queries),
            fetched_urls=[
                url
                for doc in chapter.docs
                for url in self._document_urls(doc)
            ],
            link_candidates=list(chapter.link_candidates),
            source_ids=ledger.source_ids,
        )
        chapter.source_ledger = ledger
        chapter.research_state = state

        if not chapter.summary:
            state.stop_reason = "EMPTY_SUMMARY"
            logger.info(
                f"{request_id} chapter={chapter.chapter_id} iterative stop "
                "reason=EMPTY_SUMMARY"
            )
            return

        round_errors: list[str] = []
        for round_no in range(1, max_research_rounds + 1):
            state.iteration = round_no
            try:
                review_round = await asyncio.wait_for(
                    review_research_round(
                        query=query,
                        chapter_title=chapter.title,
                        chapter_content=chapter.content,
                        state=state,
                        source_ledger=ledger,
                        max_actions=max_actions_per_round,
                    ),
                    timeout=remaining_timeout(),
                )
                review = review_round.result
            except ReviewParseError as exc:
                error = f"round={round_no} REVIEW_PARSE_FAILED: {exc}"
                round_errors.append(error)
                logger.warning(
                    f"{request_id} chapter={chapter.chapter_id} round={round_no} "
                    f"review parse failed: {exc}"
                )
                continue

            state.gaps = review.gaps
            if review.sufficient:
                state.stop_reason = "SUFFICIENT"
                break
            if not review.actions:
                state.stop_reason = "NO_ACTION"
                break

            action_queries = [
                action.query.strip()
                for action in review.actions
                if action.type == "search"
            ]
            state.searched_queries.extend(
                item for item in action_queries if item not in state.searched_queries
            )
            allowed_urls = {
                normalize_url(item.get("url", ""))
                for item in chapter.link_candidates
                if isinstance(item, dict)
            }
            allowed_urls.discard("")
            round_docs = await asyncio.wait_for(
                self._execute_research_actions(
                    chapter=chapter,
                    actions=review.actions,
                    request_id=request_id,
                    allowed_urls=allowed_urls,
                    initial=False,
                ),
                timeout=remaining_timeout(),
            )
            seen_content = {doc.content for doc in chapter.docs if doc.content}
            new_docs = []
            for doc in round_docs:
                if doc.content and doc.content not in seen_content:
                    chapter.docs.append(doc)
                    seen_content.add(doc.content)
                    new_docs.append(doc)
            new_records = ledger.add_documents(
                new_docs,
                round_no=round_no,
                default_query=action_queries[0] if action_queries else "URL_FETCH",
            )
            state.fetched_urls = [
                url
                for doc in chapter.docs
                for url in self._document_urls(doc)
            ]
            state.link_candidates = list(chapter.link_candidates)
            await emit_search()
            if not new_records:
                state.stop_reason = "NO_NEW_EVIDENCE"
                logger.info(
                    f"{request_id} chapter={chapter.chapter_id} round={round_no} "
                    "iterative stop reason=NO_NEW_EVIDENCE"
                )
                break

            evidence_context = ledger.build_evidence_context(
                new_records,
                max_total_chars=int(
                    os.getenv("DEEPSEARCH_EVIDENCE_MAX_CHARS", "16000")
                ),
                max_source_chars=int(
                    os.getenv("DEEPSEARCH_EVIDENCE_SOURCE_MAX_CHARS", "4000")
                ),
            )
            previous_edit_failure = ""
            applied = False
            last_edit_error = ""
            for attempt in range(2):
                try:
                    plan = await asyncio.wait_for(
                        generate_edit_plan(
                            query=query,
                            chapter_title=chapter.title,
                            chapter_content=chapter.content,
                            base_version=state.summary_version,
                            current_summary=state.summary,
                            gaps=state.gaps,
                            source_ledger=ledger,
                            new_evidence=evidence_context,
                            previous_edit_failure=previous_edit_failure,
                            context_messages=review_round.messages,
                        ),
                        timeout=remaining_timeout(),
                    )
                    if not plan.ops:
                        state.stop_reason = "EDIT_NOOP"
                        ledger.drop_unused(round_no)
                        state.dropped_source_ids = ledger.dropped_source_ids
                        break
                    result = apply_edit_plan(
                        state.summary,
                        base_version=plan.base_version,
                        current_version=state.summary_version,
                        ops=plan.ops,
                        source_ledger=ledger,
                    )
                except (EditParseError, EditApplyError) as exc:
                    previous_edit_failure = str(exc)
                    last_edit_error = str(exc)
                    logger.warning(
                        f"{request_id} chapter={chapter.chapter_id} round={round_no} "
                        f"editor attempt={attempt + 1} failed: {exc}"
                    )
                    if attempt == 0:
                        continue
                    ledger.drop_unused(round_no)
                    state.dropped_source_ids = ledger.dropped_source_ids
                    break

                chapter.summary = result.summary
                state.summary = result.summary
                state.summary_version = result.summary_version
                state.used_source_ids = ledger.used_source_ids
                state.previous_edit_summary = self._format_edit_summary(plan)
                ledger.drop_unused(round_no)
                state.dropped_source_ids = ledger.dropped_source_ids
                state.source_ids = ledger.source_ids
                chapter.status = f"iterated_{round_no}"
                await emit_summary(chapter.summary, False)
                applied = True
                break

            if state.stop_reason == "EDIT_NOOP":
                break
            if not applied:
                round_errors.append(
                    f"round={round_no} EDIT_FAILED: {last_edit_error}"
                )
                continue

        if not state.stop_reason:
            state.stop_reason = (
                "MAX_ROUNDS" if state.summary_version > 0 else "NO_SUCCESSFUL_EDIT"
            )
        state.summary = chapter.summary
        state.source_ids = ledger.source_ids
        state.fetched_urls = [
            url for doc in chapter.docs for url in self._document_urls(doc)
        ]
        state.link_candidates = list(chapter.link_candidates)
        state.used_source_ids = ledger.used_source_ids
        state.dropped_source_ids = ledger.dropped_source_ids
        logger.info(
            f"{request_id} chapter={chapter.chapter_id} iterative completed "
            f"round={state.iteration} max_rounds={max_research_rounds} "
            f"summary_version={state.summary_version} "
            f"fetched_sources={len(ledger.records)} "
            f"used_sources={len(ledger.used_source_ids)} "
            f"dropped_sources={len(ledger.dropped_source_ids)} "
            f"round_errors={round_errors} "
            f"stop_reason={state.stop_reason}"
        )

    @staticmethod
    def _format_edit_summary(plan) -> str:
        """生成给下一轮 Reviewer 看的短编辑摘要。"""
        parts = []
        for op in plan.ops:
            action = "delete" if not op.new_text else "replace"
            parts.append(f"{action}: {op.reason or op.old_text[:60]}")
        return "；".join(parts)

    @staticmethod
    def _new_url_fetch_cache() -> UrlFetchCache:
        return UrlFetchCache(
            timeout_seconds=int(os.getenv("DEEPSEARCH_URL_TIMEOUT_SECONDS", "30"))
        )

    @staticmethod
    def _chapter_display_queries(chapter: ChapterState) -> List[str]:
        """为前端事件生成搜索词和 URL 的稳定展示列表。"""
        queries = [
            action.query
            for action in chapter.actions
            if action.type == "search" and action.query
        ]
        queries.extend(
            action.url for action in chapter.actions if action.type == "fetch_url"
        )
        return queries or chapter.queries or [chapter.content or chapter.title]

    @staticmethod
    def _document_urls(doc: Doc) -> List[str]:
        data = doc.data if isinstance(doc.data, dict) else {}
        return list(
            dict.fromkeys(
                normalized
                for normalized in (
                    normalize_url(doc.link),
                    normalize_url(data.get("original_url", "")),
                    normalize_url(data.get("final_url", "")),
                )
                if normalized
            )
        )

    async def _execute_research_actions(
        self,
        *,
        chapter: ChapterState,
        actions: List[ResearchAction],
        request_id: str,
        allowed_urls: set[str],
        initial: bool,
    ) -> List[Doc]:
        """并发执行搜索和受控 URL 抓取，并合并为章节文档。"""
        search_queries = []
        fetch_actions = []
        seen_queries = set()
        seen_urls = set()
        for action in actions:
            if action.type == "search":
                normalized_query = " ".join(action.query.split())
                if normalized_query and normalized_query not in seen_queries:
                    seen_queries.add(normalized_query)
                    search_queries.append(normalized_query)
                continue

            normalized_url = normalize_url(action.url)
            if normalized_url and normalized_url not in seen_urls:
                seen_urls.add(normalized_url)
                fetch_actions.append(action)
                logger.info(
                    f"{request_id} "
                    f"{'URL_ACTION_PLANNED' if initial else 'URL_ACTION_SELECTED'} "
                    f"chapter={chapter.chapter_id} url={normalized_url}"
                )

        search_task = self._search_queries_and_dedup(
            queries=search_queries,
            request_id=request_id,
        )
        fetch_task = self._fetch_research_urls(
            chapter=chapter,
            actions=fetch_actions,
            request_id=request_id,
            allowed_urls=allowed_urls,
            initial=initial,
        )
        search_result, fetched_docs = await asyncio.gather(
            search_task, fetch_task, return_exceptions=True
        )

        if isinstance(search_result, Exception):
            logger.warning(
                f"{request_id} chapter={chapter.chapter_id} search actions failed: "
                f"{search_result}"
            )
            search_docs = []
        else:
            search_docs = search_result[0]
        if isinstance(fetched_docs, Exception):
            logger.warning(
                f"{request_id} chapter={chapter.chapter_id} url actions failed: "
                f"{fetched_docs}"
            )
            fetched_docs = []

        # 精确抓取的正文和候选链接优先于同一 URL 的搜索摘要。
        return self._merge_docs(fetched_docs, search_docs)

    async def _fetch_research_urls(
        self,
        *,
        chapter: ChapterState,
        actions: List[ResearchAction],
        request_id: str,
        allowed_urls: set[str],
        initial: bool,
    ) -> List[Doc]:
        """在数量、深度和并发限制内抓取允许的 URL。"""
        max_url_fetches = min(
            8, max(0, int(os.getenv("DEEPSEARCH_MAX_URL_FETCHES_PER_CHAPTER", "8")))
        )
        remaining_budget = max(0, max_url_fetches - chapter.url_fetch_count)
        if remaining_budget <= 0 or not actions:
            return []

        max_depth = max(
            0, int(os.getenv("DEEPSEARCH_MAX_URL_DEPTH", "1"))
        )
        candidate_depth = {
            normalize_url(item.get("url", "")): int(item.get("depth", 0))
            for item in chapter.link_candidates
            if isinstance(item, dict)
        }
        seen_doc_urls = {
            url for doc in chapter.docs for url in self._document_urls(doc)
        }
        max_concurrent = max(1, int(os.getenv("DEEPSEARCH_URL_CONCURRENCY", "4")))
        semaphore = asyncio.Semaphore(max_concurrent)

        async def _fetch_one(action: ResearchAction) -> Doc | None:
            normalized_url = normalize_url(action.url)
            if normalized_url not in allowed_urls:
                logger.warning(
                    f"{request_id} URL_ACTION_REJECTED_UNTRUSTED "
                    f"chapter={chapter.chapter_id} url={normalized_url}"
                )
                return None
            if normalized_url in seen_doc_urls:
                return None

            depth = candidate_depth.get(normalized_url, 0) if not initial else 0
            if depth > max_depth:
                logger.info(
                    f"{request_id} URL_LIMIT_REACHED reason=DEPTH "
                    f"chapter={chapter.chapter_id} url={normalized_url} depth={depth}"
                )
                return None
            parent_url = next(
                (
                    item.get("parent_url", "")
                    for item in chapter.link_candidates
                    if isinstance(item, dict)
                    and normalize_url(item.get("url", "")) == normalized_url
                ),
                "",
            )
            async with semaphore:
                try:
                    doc = await self._url_fetch_cache.get(
                        normalized_url,
                        request_id=request_id,
                        origin="seed" if initial else "link",
                        depth=depth,
                        parent_url=parent_url,
                    )
                except UrlSafetyError as exc:
                    logger.warning(
                        f"{request_id} URL_FETCH_FAILED "
                        f"chapter={chapter.chapter_id} url={normalized_url} "
                        f"error={exc}"
                    )
                    return None
            doc.data["search_query"] = action.reason or action.url
            self._record_link_candidates(
                chapter=chapter,
                doc=doc,
                max_depth=max_depth,
            )
            return doc

        limited_actions = actions[:remaining_budget]
        results = await asyncio.gather(
            *(_fetch_one(action) for action in limited_actions),
            return_exceptions=True,
        )
        docs = []
        for result in results:
            if isinstance(result, Exception):
                logger.warning(
                    f"{request_id} URL_ACTION_UNEXPECTED_FAILURE "
                    f"chapter={chapter.chapter_id} error={result}"
                )
                continue
            if result is not None:
                docs.append(result)
        chapter.url_fetch_count += len(docs)
        logger.info(
            f"{request_id} URL_LINK_CANDIDATES chapter={chapter.chapter_id} "
            f"candidates={len(chapter.link_candidates)} "
            f"fetched={chapter.url_fetch_count}"
        )
        return docs

    @staticmethod
    def _record_link_candidates(
        *,
        chapter: ChapterState,
        doc: Doc,
        max_depth: int,
    ) -> None:
        doc_depth = int((doc.data or {}).get("depth", 0))
        if doc_depth >= max_depth:
            return
        known = {
            normalize_url(item.get("url", ""))
            for item in chapter.link_candidates
            if isinstance(item, dict)
        }
        for candidate in (doc.data or {}).get("link_candidates") or []:
            if not isinstance(candidate, dict):
                continue
            normalized_url = normalize_url(candidate.get("url", ""))
            if not normalized_url or normalized_url in known:
                continue
            item = dict(candidate)
            item["url"] = normalized_url
            item["parent_url"] = normalize_url(doc.link)
            item["depth"] = doc_depth + 1
            chapter.link_candidates.append(item)
            known.add(normalized_url)

    @staticmethod
    def _merge_docs(*doc_groups: List[Doc]) -> List[Doc]:
        merged = []
        seen_content = set()
        seen_urls = set()
        for docs in doc_groups:
            for doc in docs:
                content = (doc.content or "").strip()
                normalized_url = normalize_url(doc.link)
                if not content:
                    continue
                if content in seen_content or (
                    normalized_url and normalized_url in seen_urls
                ):
                    continue
                merged.append(doc)
                seen_content.add(content)
                if normalized_url:
                    seen_urls.add(normalized_url)
        return merged

    async def _search_queries_and_dedup(
        self,
        queries: List[str],
        request_id: str,
    ) -> Tuple[List[Doc], List[List[Doc]]]:
        """异步并行搜索多个查询并去重，避免阻塞当前 Uvicorn 事件循环。"""
        max_concurrent = max(1, int(os.getenv("SEARCH_THREAD_NUM", 5)))
        semaphore = asyncio.Semaphore(max_concurrent)

        async def _search_one(q: str) -> List[Doc]:
            async with semaphore:
                return await self._search_single_query(q, request_id)

        raw_results = await asyncio.gather(
            *(_search_one(q) for q in queries), return_exceptions=True
        )
        results = []
        for q, result in zip(queries, raw_results):
            if isinstance(result, Exception):
                logger.warning(
                    f"{request_id} search query failed, continue with others: "
                    f"query={q!r} error={result}"
                )
                results.append([])
            else:
                for doc in result:
                    if isinstance(doc.data, dict):
                        doc.data.setdefault("origin", "search")
                        doc.data.setdefault("depth", 0)
                        doc.data["search_query"] = q
                results.append(result)
        all_docs = [doc for docs in results for doc in docs]
        seen_content = set()
        deduped_docs = []
        for doc in all_docs:
            if doc.content and doc.content not in seen_content:
                deduped_docs.append(doc)
                seen_content.add(doc.content)
        return deduped_docs, results
