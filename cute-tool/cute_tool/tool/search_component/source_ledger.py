# -*- coding: utf-8 -*-
"""DeepSearch 来源账本与迭代证据上下文。"""

import hashlib
import json
import re
from typing import Iterable
from urllib.parse import urlsplit, urlunsplit

from cute_tool.model.document import Doc
from cute_tool.model.research import SourceRecord


_URL_PATTERN = re.compile(r"https?://[^\s<>'\"\]\)}]+", re.IGNORECASE)
_URL_TRAILING_PUNCTUATION = ".,;:!?，。；：！？"
_INTERNAL_SOURCE_ID_PATTERN = re.compile(
    r"\[\[?R\d+-S\d+\]?\](?:\([^)]*\))?"
    r"|(?<![A-Za-z0-9_/:?&=#.-])R\d+-S\d+(?![A-Za-z0-9_/.-])",
    re.IGNORECASE,
)


def normalize_url(url: str) -> str:
    """规范化 URL，避免同一页面因 fragment 或末尾斜杠重复计数。"""
    value = (url or "").strip()
    if not value:
        return ""
    try:
        parsed = urlsplit(value)
    except ValueError:
        return value
    path = parsed.path.rstrip("/") or "/"
    return urlunsplit(
        (
            parsed.scheme.lower(),
            parsed.netloc.lower(),
            path,
            parsed.query,
            "",
        )
    )


def content_hash(content: str) -> str:
    """返回正文的稳定 hash。"""
    return hashlib.sha256((content or "").strip().encode("utf-8")).hexdigest()


class SourceLedger:
    """记录来源发现、去重、使用和丢弃状态。"""

    def __init__(self) -> None:
        self.records: list[SourceRecord] = []
        self._seen_urls: set[str] = set()
        self._seen_hashes: set[str] = set()

    def add_documents(
        self,
        docs: Iterable[Doc],
        *,
        round_no: int,
        default_query: str = "",
    ) -> list[SourceRecord]:
        """加入本轮搜索结果，跳过 URL 或正文重复项。"""
        round_count = sum(1 for record in self.records if record.round == round_no)
        added: list[SourceRecord] = []
        for doc in docs:
            content = (doc.content or "").strip()
            if not content:
                continue
            url = normalize_url(doc.link)
            digest = content_hash(content)
            if url and url in self._seen_urls:
                continue
            if digest in self._seen_hashes:
                continue

            round_count += 1
            source_id = f"R{round_no}-S{round_count}"
            data = doc.data if isinstance(doc.data, dict) else {}
            query = str(data.get("search_query") or default_query or "").strip()
            record = SourceRecord(
                source_id=source_id,
                round=round_no,
                url=url or str(doc.link or "").strip(),
                title=str(doc.title or "").strip(),
                query=query,
                content_hash=digest,
                content=content,
                origin=str(data.get("origin") or "").strip(),
                depth=int(data.get("depth", 0) or 0),
                parent_url=normalize_url(data.get("parent_url", "")),
                original_url=normalize_url(data.get("original_url", "")),
            )
            if isinstance(doc.data, dict):
                doc.data["source_id"] = source_id
            self.records.append(record)
            if url:
                self._seen_urls.add(url)
            self._seen_hashes.add(digest)
            added.append(record)
        return added

    def has_source(self, source_id: str) -> bool:
        """判断来源 ID 是否存在。"""
        return any(record.source_id == source_id for record in self.records)

    def has_url(self, url: str) -> bool:
        """判断规范化 URL 是否已经进入当前章节来源账本。"""
        normalized = normalize_url(url)
        return bool(normalized) and any(
            normalize_url(record.url) == normalized for record in self.records
        )

    def mark_used_by_urls(self, text: str) -> list[str]:
        """根据正文中的 URL 反查来源，避免把内部来源编号交给模型。"""
        cited_urls = {
            normalize_url(match.rstrip(_URL_TRAILING_PUNCTUATION))
            for match in _URL_PATTERN.findall(text or "")
        }
        cited_urls.discard("")
        if not cited_urls:
            return []

        used_source_ids = []
        for record in self.records:
            if normalize_url(record.url) not in cited_urls:
                continue
            record.used = True
            record.dropped_reason = ""
            used_source_ids.append(record.source_id)
        return used_source_ids

    def drop_unused(
        self,
        round_no: int,
        *,
        reason: str = "NOT_REFERENCED_BY_EDIT",
    ) -> None:
        """记录本轮未被编辑显式引用的来源。"""
        for record in self.records:
            if record.round == round_no and not record.used and not record.dropped_reason:
                record.dropped_reason = reason

    def source_index(self) -> list[dict]:
        """返回给 Reviewer/Editor 的轻量来源索引。"""
        return [
            {
                "round": record.round,
                "title": record.title,
                "url": record.url,
                "used": record.used,
                "dropped_reason": record.dropped_reason,
            }
            for record in self.records
        ]

    @property
    def source_ids(self) -> list[str]:
        return [record.source_id for record in self.records]

    @property
    def used_source_ids(self) -> list[str]:
        return [record.source_id for record in self.records if record.used]

    @property
    def dropped_source_ids(self) -> list[str]:
        return [record.source_id for record in self.records if record.dropped_reason]

    def build_evidence_context(
        self,
        records: Iterable[SourceRecord],
        *,
        max_total_chars: int = 16000,
        max_source_chars: int = 4000,
    ) -> str:
        """把本轮新来源整理成有边界的 JSON 证据包。"""
        remaining = max(0, max_total_chars)
        evidence = []
        for record in records:
            if remaining <= 0:
                break
            content_limit = min(max_source_chars, remaining)
            content = record.content[:content_limit]
            remaining -= len(content)
            evidence.append(
                {
                    "url": record.url,
                    "title": record.title,
                    "query": record.query,
                    "content": content,
                }
            )
        return json.dumps(evidence, ensure_ascii=False, indent=2)


def strip_internal_source_ids(text: str) -> str:
    """移除模型可能残留的内部来源编号，正文只保留可点击 URL。"""
    return _INTERNAL_SOURCE_ID_PATTERN.sub("", text or "")
