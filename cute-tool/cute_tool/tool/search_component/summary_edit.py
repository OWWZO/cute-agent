# -*- coding: utf-8 -*-
"""DeepSearch 章节总结的确定性编辑执行器。"""

from dataclasses import dataclass

from cute_tool.model.research import EditOp
from cute_tool.tool.search_component.source_ledger import (
    SourceLedger,
    strip_internal_source_ids,
)


class EditApplyError(ValueError):
    """编辑计划无法安全应用。"""


@dataclass
class EditApplyResult:
    summary: str
    summary_version: int
    used_source_ids: list[str]


def apply_edit_plan(
    summary: str,
    *,
    base_version: int,
    current_version: int,
    ops: list[EditOp],
    source_ledger: SourceLedger,
) -> EditApplyResult:
    """校验并原子应用一批 `old_text/new_text` 编辑。"""
    if base_version != current_version:
        raise EditApplyError(
            f"base_version={base_version} does not match current_version={current_version}"
        )
    if not ops:
        return EditApplyResult(summary, current_version, [])

    spans: list[tuple[int, int, EditOp]] = []
    for op in ops:
        if not op.old_text:
            raise EditApplyError("old_text must not be empty")
        occurrences = summary.count(op.old_text)
        if occurrences == 0:
            raise EditApplyError(f"old_text not found: {op.old_text[:80]!r}")
        if occurrences > 1:
            raise EditApplyError(f"old_text is not unique: {op.old_text[:80]!r}")

        start = summary.find(op.old_text)
        spans.append((start, start + len(op.old_text), op))

    ordered_spans = sorted(spans, key=lambda item: item[0])
    for previous, current in zip(ordered_spans, ordered_spans[1:]):
        if current[0] < previous[1]:
            raise EditApplyError("edit operations overlap")

    updated = summary
    for start, end, op in sorted(spans, key=lambda item: item[0], reverse=True):
        updated = updated[:start] + op.new_text + updated[end:]

    updated = strip_internal_source_ids(updated)
    used_source_ids = source_ledger.mark_used_by_urls(updated)
    return EditApplyResult(updated, current_version + 1, used_source_ids)
