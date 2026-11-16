# -*- coding: utf-8 -*-
import json

from cute_tool.model.document import Doc
from cute_tool.tool.search_component.source_ledger import SourceLedger


def test_source_ledger_deduplicates_url_and_content_hash():
    ledger = SourceLedger()
    docs = [
        Doc(
            doc_type="web_page",
            title="first",
            link="https://example.com/a/#section",
            content="same content",
            data={"search_query": "query-a"},
        ),
        Doc(
            doc_type="web_page",
            title="same url",
            link="https://example.com/a",
            content="different content",
        ),
        Doc(
            doc_type="web_page",
            title="same hash",
            link="https://example.com/b",
            content="same content",
        ),
        Doc(
            doc_type="web_page",
            title="new",
            link="https://example.com/c",
            content="new content",
        ),
    ]

    records = ledger.add_documents(docs, round_no=1, default_query="fallback")

    assert [record.source_id for record in records] == ["R1-S1", "R1-S2"]
    assert records[0].query == "query-a"
    assert records[1].query == "fallback"
    assert docs[0].data["source_id"] == "R1-S1"


def test_evidence_context_is_bounded_without_exposing_internal_source_id():
    ledger = SourceLedger()
    records = ledger.add_documents(
        [
            Doc(
                doc_type="web_page",
                title="source",
                link="https://example.com/source",
                content="x" * 100,
            )
        ],
        round_no=1,
    )

    payload = json.loads(
        ledger.build_evidence_context(
            records,
            max_total_chars=20,
            max_source_chars=20,
        )
    )

    assert "source_id" not in payload[0]
    assert "content_hash" not in payload[0]
    assert payload[0]["url"] == "https://example.com/source"
    assert len(payload[0]["content"]) == 20


def test_source_index_and_url_matching_do_not_expose_internal_source_id():
    ledger = SourceLedger()
    ledger.add_documents(
        [
            Doc(
                doc_type="web_page",
                title="source",
                link="https://example.com/source/#section",
                content="source content",
            )
        ],
        round_no=1,
    )

    assert "source_id" not in ledger.source_index()[0]
    used_ids = ledger.mark_used_by_urls(
        "引用：[[1]](https://example.com/source/#section)"
    )

    assert used_ids == ["R1-S1"]
    assert ledger.records[0].used is True


def test_drop_unused_only_marks_current_round():
    ledger = SourceLedger()
    initial = ledger.add_documents(
        [
            Doc(
                doc_type="web_page",
                title="initial",
                link="https://example.com/initial",
                content="initial",
            )
        ],
        round_no=0,
    )
    current = ledger.add_documents(
        [
            Doc(
                doc_type="web_page",
                title="current",
                link="https://example.com/current",
                content="current",
            )
        ],
        round_no=1,
    )

    ledger.drop_unused(1)

    assert initial[0].dropped_reason == ""
    assert current[0].dropped_reason == "NOT_REFERENCED_BY_EDIT"


def test_source_record_preserves_url_origin_and_depth_metadata():
    ledger = SourceLedger()
    records = ledger.add_documents(
        [
            Doc(
                doc_type="web_page",
                title="linked page",
                link="https://example.com/child",
                content="child content",
                data={
                    "origin": "link",
                    "depth": 1,
                    "parent_url": "https://example.com/parent",
                    "original_url": "https://example.com/child?from=parent",
                },
            )
        ],
        round_no=2,
    )

    assert records[0].origin == "link"
    assert records[0].depth == 1
    assert records[0].parent_url == "https://example.com/parent"
    assert records[0].original_url == "https://example.com/child?from=parent"
