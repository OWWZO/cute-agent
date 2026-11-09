from __future__ import annotations

import json

import requests

from cute_evals.benchmarks.deepresearch.runner import run_deepsearch


class _FakeResponse:
    status_code = 200
    text = ""

    def __init__(self, lines: list[str]) -> None:
        self.lines = lines

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def iter_lines(self, decode_unicode: bool = True):
        yield from self.lines


def test_run_deepsearch_collects_sse_report_and_docs(monkeypatch) -> None:
    events = [
        {
            "requestId": "r",
            "messageType": "extend",
            "searchResult": {"chapters": [{"chapterId": "C1"}]},
            "isFinal": False,
        },
        {
            "requestId": "r",
            "messageType": "search",
            "searchResult": {
                "docs": [[
                    {
                        "title": "Source A",
                        "link": "https://example.com/a",
                        "content": "evidence",
                    }
                ]]
            },
            "isFinal": False,
        },
        {
            "requestId": "r",
            "messageType": "chapter_summary",
            "answer": "chapter",
            "isFinal": False,
        },
        {
            "requestId": "r",
            "messageType": "report",
            "answer": "Report ",
            "isFinal": False,
        },
        {
            "requestId": "r",
            "messageType": "report",
            "answer": "body.",
            "isFinal": False,
        },
        {
            "requestId": "r",
            "messageType": "report",
            "answer": "",
            "isFinal": True,
        },
    ]
    lines = [f"data: {json.dumps(event)}" for event in events]
    lines.extend(["data: heartbeat", "data: [DONE]"])

    captured = {}

    def fake_post(url, **kwargs):
        captured["url"] = url
        captured["json"] = kwargs["json"]
        return _FakeResponse(lines)

    monkeypatch.setattr(requests, "post", fake_post)

    result = run_deepsearch(
        base_url="http://127.0.0.1:1601",
        query="Do research",
        task_id="1",
        timeout_seconds=30,
        search_engines=["bing"],
    )

    assert captured["url"] == "http://127.0.0.1:1601/v1/tool/deepsearch"
    assert captured["json"]["query"] == "Do research"
    assert captured["json"]["search_engines"] == ["bing"]
    assert result.status == "SUCCESS"
    assert result.report == "Report body."
    assert len(result.events) == 6
    assert result.docs[0]["link"] == "https://example.com/a"
    assert result.event_counts == {
        "chapter_summary": 1,
        "extend": 1,
        "report": 3,
        "search": 1,
    }


def test_run_deepsearch_requires_final_event(monkeypatch) -> None:
    def fake_post(url, **kwargs):
        return _FakeResponse(
            [
                'data: {"messageType":"report","answer":"partial","isFinal":false}',
                "data: [DONE]",
            ]
        )

    monkeypatch.setattr(requests, "post", fake_post)

    result = run_deepsearch(
        base_url="http://127.0.0.1:1601",
        query="Do research",
        task_id="2",
        timeout_seconds=30,
    )

    assert result.status == "FAILED"
    assert result.report == "partial"
    assert "isFinal" in (result.error or "")
