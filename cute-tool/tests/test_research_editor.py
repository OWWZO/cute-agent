# -*- coding: utf-8 -*-
import copy

import pytest

from cute_tool.tool.search_component.editor import (
    EditParseError,
    generate_edit_plan,
)
from cute_tool.tool.search_component.source_ledger import SourceLedger


def _stub_llm(monkeypatch, output: str, calls: list | None = None):
    async def fake_ask_llm(**kwargs):
        if calls is not None:
            calls.append(kwargs)
        yield output

    monkeypatch.setattr(
        "cute_tool.tool.search_component.editor.ask_llm", fake_ask_llm
    )


def _stub_llm_sequence(monkeypatch, outputs: list[str], calls: list):
    queue = list(outputs)

    async def fake_ask_llm(**kwargs):
        calls.append(copy.deepcopy(kwargs))
        yield queue.pop(0)

    monkeypatch.setattr(
        "cute_tool.tool.search_component.editor.ask_llm", fake_ask_llm
    )


@pytest.mark.asyncio
async def test_editor_accepts_multiple_ops_without_full_rewrite(monkeypatch):
    _stub_llm(
        monkeypatch,
        """
        {
          "base_version": 2,
          "ops": [
            {
              "old_text": "旧结论",
              "new_text": "新结论",
              "reason": "补充官方信息"
            },
            {
              "old_text": "待删除",
              "new_text": ""
            }
          ]
        }
        """,
    )

    plan = await generate_edit_plan(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        base_version=2,
        current_summary="旧结论\n待删除",
        gaps=[],
        source_ledger=SourceLedger(),
        new_evidence="[]",
    )

    assert plan.base_version == 2
    assert len(plan.ops) == 2
    assert "source_ids" not in plan.ops[0].model_dump()


@pytest.mark.asyncio
async def test_editor_accepts_empty_ops(monkeypatch):
    _stub_llm(monkeypatch, '{"base_version":0,"ops":[]}')

    plan = await generate_edit_plan(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        base_version=0,
        current_summary="总结",
        gaps=[],
        source_ledger=SourceLedger(),
        new_evidence="[]",
    )

    assert plan.ops == []


@pytest.mark.asyncio
async def test_editor_retries_empty_response_with_identical_messages(monkeypatch):
    calls = []
    _stub_llm_sequence(
        monkeypatch,
        ["", '{"base_version":0,"ops":[]}'],
        calls,
    )

    plan = await generate_edit_plan(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        base_version=0,
        current_summary="总结",
        gaps=[],
        source_ledger=SourceLedger(),
        new_evidence="[]",
    )

    assert plan.ops == []
    assert len(calls) == 2
    assert calls[0]["messages"] == calls[1]["messages"]
    assert calls[0]["response_format"] == calls[1]["response_format"]
    assert calls[0]["max_tokens"] == calls[1]["max_tokens"]


@pytest.mark.asyncio
async def test_editor_reports_empty_response_after_retry(monkeypatch):
    calls = []
    _stub_llm_sequence(monkeypatch, ["", ""], calls)

    with pytest.raises(EditParseError, match="empty editor response after retry"):
        await generate_edit_plan(
            query="问题",
            chapter_title="章节",
            chapter_content="任务",
            base_version=0,
            current_summary="总结",
            gaps=[],
            source_ledger=SourceLedger(),
            new_evidence="[]",
        )

    assert len(calls) == 2


@pytest.mark.asyncio
async def test_editor_rejects_stale_base_version(monkeypatch):
    _stub_llm(monkeypatch, '{"base_version":1,"ops":[]}')

    with pytest.raises(EditParseError, match="base_version"):
        await generate_edit_plan(
            query="问题",
            chapter_title="章节",
            chapter_content="任务",
            base_version=0,
            current_summary="总结",
            gaps=[],
            source_ledger=SourceLedger(),
            new_evidence="[]",
        )


@pytest.mark.asyncio
async def test_editor_repairs_truncated_json_once(monkeypatch):
    _stub_llm(
        monkeypatch,
        '{"base_version":0,"ops":[{"old_text":"a","new_text":"b"}]',
    )

    plan = await generate_edit_plan(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        base_version=0,
        current_summary="a",
        gaps=[],
        source_ledger=SourceLedger(),
        new_evidence="[]",
    )

    assert plan.ops[0].new_text == "b"


@pytest.mark.asyncio
async def test_editor_uses_structured_output_and_prompt_coupled_max_tokens(
    monkeypatch,
):
    calls = []
    _stub_llm(monkeypatch, '{"base_version":0,"ops":[]}', calls)

    await generate_edit_plan(
        query="问题",
        chapter_title="章节",
        chapter_content="任务" * 30000,
        base_version=0,
        current_summary="总结" * 20000,
        gaps=[],
        source_ledger=SourceLedger(),
        new_evidence="[]",
    )

    call = calls[0]
    response_format = call["response_format"]
    schema = response_format["json_schema"]["schema"]
    edit_op_schema = schema["$defs"]["EditOp"]

    assert response_format["type"] == "json_schema"
    assert response_format["json_schema"]["strict"] is True
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == {"base_version", "ops"}
    assert "maxLength" not in edit_op_schema["properties"]["old_text"]
    assert "source_ids" not in edit_op_schema["properties"]
    assert call["max_tokens"] > 6000


@pytest.mark.asyncio
async def test_editor_appends_round_context_without_rebuilding_prefix(monkeypatch):
    calls = []
    _stub_llm(monkeypatch, '{"base_version":0,"ops":[]}', calls)
    prefix = [
        {"role": "system", "content": "stable-system"},
        {"role": "user", "content": "current-summary:S0"},
        {"role": "assistant", "content": '{"sufficient":false}'},
    ]

    await generate_edit_plan(
        query="问题",
        chapter_title="章节",
        chapter_content="任务",
        base_version=0,
        current_summary="S0",
        gaps=[],
        source_ledger=SourceLedger(),
        new_evidence='[{"url":"https://example.com/source","content":"evidence"}]',
        context_messages=prefix,
    )

    messages = calls[0]["messages"]
    assert messages[: len(prefix)] == prefix
    assert messages[0]["content"] == "stable-system"
    assert "current-summary:S0" in messages[1]["content"]
    assert "https://example.com/source" in messages[-2]["content"]
    assert "R1-S1" not in messages[-2]["content"]
    assert "source_ids" not in messages[-2]["content"]
    assert "章节编辑员" in messages[-1]["content"]
