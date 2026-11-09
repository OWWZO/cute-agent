from __future__ import annotations

import json

import requests

from cute_evals.benchmarks.rag.runner import run_mrag


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


def test_run_mrag_collects_answer_and_retrieval_trace(monkeypatch) -> None:
    retrieval_trace = {
        "merged_text": {
            "stage": "merged_text",
            "hits": [
                {
                    "canonical_key": "policy.pdf#p3",
                    "payload": {
                        "text": "国内差旅住宿报销上限为 1200 元。[policy.pdf#p3]",
                        "filename": "policy.pdf",
                    },
                }
            ],
        },
        "merged_image": {"stage": "merged_image", "hits": []},
        "merged_page": {"stage": "merged_page", "hits": []},
        "rerank_text": {"stage": "rerank_text", "hits": []},
    }
    events = [
        {"stage": "task", "data": "query", "isFinal": False},
        {
            "stage": "retrieval_trace",
            "data": "",
            "isFinal": False,
            "meta": {"retrievalTrace": retrieval_trace},
        },
        {
            "choices": [
                {
                    "delta": {
                        "content": "国内差旅住宿上限为 1200 元。[policy.pdf#p3]"
                    }
                }
            ]
        },
        {"stage": "final", "data": "", "isFinal": True},
    ]
    lines = [f"data: {json.dumps(event, ensure_ascii=False)}" for event in events]
    lines.extend(["data: heartbeat", "data: [DONE]"])
    captured: dict[str, object] = {}

    def fake_post(url, **kwargs):
        captured["url"] = url
        captured["json"] = kwargs["json"]
        return _FakeResponse(lines)

    monkeypatch.setattr(requests, "post", fake_post)

    result = run_mrag(
        base_url="http://127.0.0.1:1601",
        question="差旅报销上限是多少？",
        task_id="rag-python.cited-answer",
        kb_id="policy",
        timeout_seconds=30,
    )

    assert captured["url"] == "http://127.0.0.1:1601/v1/tool/mragQuery"
    assert captured["json"]["include_retrieval_trace"] is True
    assert result.status == "SUCCESS"
    assert result.answer == "国内差旅住宿上限为 1200 元。[policy.pdf#p3]"
    assert result.retrieval_trace == retrieval_trace
    assert "[policy.pdf#p3]" in result.context_text
    assert result.total_tokens is None


def test_run_mrag_requires_final_stage(monkeypatch) -> None:
    def fake_post(url, **kwargs):
        return _FakeResponse(
            [
                'data: {"choices":[{"delta":{"content":"partial"}}]}',
                "data: [DONE]",
            ]
        )

    monkeypatch.setattr(requests, "post", fake_post)

    result = run_mrag(
        base_url="http://127.0.0.1:1601",
        question="query",
        task_id="rag-python.no-final",
        timeout_seconds=30,
    )

    assert result.status == "FAILED"
    assert result.answer == "partial"
    assert "stage=final" in (result.error or "")
