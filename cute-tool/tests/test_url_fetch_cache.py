# -*- coding: utf-8 -*-
import asyncio

import pytest

from cute_tool.tool.search_component.url_fetch import UrlFetchCache
from cute_tool.tool.web_fetcher import LinkCandidate, WebFetchResult


class FakeFetcher:
    def __init__(self, *, fail: bool = False):
        self.fail = fail
        self.calls = []

    async def fetch(self, request):
        self.calls.append(request.url)
        await asyncio.sleep(0)
        if self.fail:
            raise ValueError("upstream failed")
        return WebFetchResult(
            title="页面标题",
            final_url=request.url + "/final",
            content="页面正文",
            content_format="markdown",
            word_count=4,
            content_source="trafilatura",
            metadata={},
            status_code=200,
            status_text="OK",
            link_candidates=[
                LinkCandidate(
                    url="https://example.com/next",
                    title="下一篇",
                    anchor="继续阅读",
                    parent_url=request.url + "/final",
                    depth=1,
                )
            ],
        )


@pytest.mark.asyncio
async def test_url_fetch_cache_deduplicates_concurrent_requests():
    cache = UrlFetchCache()
    fetcher = FakeFetcher()
    cache.fetcher = fetcher

    docs = await asyncio.gather(
        *[
            cache.get(
                "https://example.com/page#section",
                request_id=f"req-{index}",
                origin="seed",
                depth=0,
            )
            for index in range(3)
        ]
    )

    assert fetcher.calls == ["https://example.com/page"]
    assert [doc.content for doc in docs] == ["页面正文"] * 3
    assert [doc.link for doc in docs] == [
        "https://example.com/page/final"
    ] * 3
    assert docs[0].data["link_candidates"][0]["url"] == (
        "https://example.com/next"
    )
    assert len(cache.fetched_urls) == 2


@pytest.mark.asyncio
async def test_url_fetch_cache_reuses_failure_without_second_request():
    cache = UrlFetchCache()
    fetcher = FakeFetcher(fail=True)
    cache.fetcher = fetcher

    for _ in range(2):
        with pytest.raises(ValueError, match="upstream failed"):
            await cache.get(
                "https://example.com/broken",
                request_id="req",
                origin="seed",
                depth=0,
            )

    assert fetcher.calls == ["https://example.com/broken"]
    assert cache.failures == {"https://example.com/broken": "upstream failed"}
