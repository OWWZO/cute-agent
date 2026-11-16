# -*- coding: utf-8 -*-
import pytest

from cute_tool.model.document import Doc
from cute_tool.model.research import EditOp
from cute_tool.tool.search_component.source_ledger import SourceLedger
from cute_tool.tool.search_component.summary_edit import (
    EditApplyError,
    apply_edit_plan,
)


def _ledger() -> SourceLedger:
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
    return ledger


def test_applies_multiple_edits_against_same_snapshot():
    ledger = _ledger()
    result = apply_edit_plan(
        "A\nB\nC",
        base_version=0,
        current_version=0,
        ops=[
            EditOp(
                old_text="A",
                new_text="A1 [[1]](https://example.com/source)",
            ),
            EditOp(old_text="C", new_text="C1"),
        ],
        source_ledger=ledger,
    )

    assert result.summary == "A1 [[1]](https://example.com/source)\nB\nC1"
    assert result.summary_version == 1
    assert result.used_source_ids == ["R1-S1"]
    assert ledger.records[0].used is True


def test_delete_uses_empty_new_text():
    result = apply_edit_plan(
        "keep\nremove\nkeep",
        base_version=2,
        current_version=2,
        ops=[EditOp(old_text="remove\n", new_text="")],
        source_ledger=_ledger(),
    )

    assert result.summary == "keep\nkeep"


def test_insert_keeps_anchor_and_adds_content():
    result = apply_edit_plan(
        "anchor",
        base_version=0,
        current_version=0,
        ops=[EditOp(old_text="anchor", new_text="anchor\nnew fact")],
        source_ledger=_ledger(),
    )

    assert result.summary == "anchor\nnew fact"


@pytest.mark.parametrize(
    "summary,ops,message",
    [
        ("abc", [EditOp(old_text="missing", new_text="x")], "not found"),
        ("a a", [EditOp(old_text="a", new_text="x")], "not unique"),
        ("abc", [EditOp(old_text="", new_text="x")], "must not be empty"),
        (
            "abcdef",
            [
                EditOp(old_text="abcd", new_text="x"),
                EditOp(old_text="cdef", new_text="y"),
            ],
            "overlap",
        ),
    ],
)
def test_rejects_invalid_edit_without_mutating_summary(summary, ops, message):
    with pytest.raises(EditApplyError, match=message):
        apply_edit_plan(
            summary,
            base_version=0,
            current_version=0,
            ops=ops,
            source_ledger=_ledger(),
        )


def test_rejects_stale_version():
    with pytest.raises(EditApplyError, match="base_version"):
        apply_edit_plan(
            "abc",
            base_version=1,
            current_version=2,
            ops=[EditOp(old_text="a", new_text="x")],
            source_ledger=_ledger(),
        )


def test_marks_sources_by_url_and_ignores_unknown_urls():
    ledger = _ledger()
    result = apply_edit_plan(
        "abc",
        base_version=0,
        current_version=0,
        ops=[EditOp(old_text="a", new_text="x [[1]](https://unknown.example)")],
        source_ledger=ledger,
    )

    assert result.used_source_ids == []
    assert ledger.records[0].used is False


def test_allows_edit_without_url():
    result = apply_edit_plan(
        "abc",
        base_version=0,
        current_version=0,
        ops=[EditOp(old_text="a", new_text="x")],
        source_ledger=_ledger(),
    )

    assert result.summary == "xbc"
    assert result.used_source_ids == []


def test_strips_internal_source_ids_from_edited_summary():
    result = apply_edit_plan(
        "abc",
        base_version=0,
        current_version=0,
        ops=[EditOp(old_text="a", new_text="x [R1-S1]")],
        source_ledger=_ledger(),
    )

    assert "R1-S1" not in result.summary


def test_allows_old_text_over_200_characters():
    op = EditOp(old_text="x" * 201, new_text="y")

    assert len(op.old_text) == 201


def test_applies_edit_with_old_text_over_200_when_match_is_valid():
    old_text = "x" * 201
    result = apply_edit_plan(
        old_text + "\nkeep",
        base_version=0,
        current_version=0,
        ops=[EditOp(old_text=old_text, new_text="updated")],
        source_ledger=_ledger(),
    )

    assert result.summary == "updated\nkeep"
