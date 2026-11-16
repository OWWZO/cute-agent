# -*- coding: utf-8 -*-
"""DeepSearch URL 安全校验与运行级抓取缓存。"""

import asyncio
import copy
import ipaddress
import socket
from typing import Any
from urllib.parse import urlsplit

from cute_tool.model.document import Doc
from cute_tool.model.protocal import WebFetchRequest
from cute_tool.tool.search_component.source_ledger import normalize_url
from cute_tool.tool.web_fetcher import WebFetcher


class UrlSafetyError(ValueError):
    """URL 不符合 DeepSearch 的公开网页访问约束。"""


class UrlSafetyValidator:
    """只允许解析到公网地址的 http(s) URL。"""

    async def validate(self, url: str) -> None:
        parsed = urlsplit((url or "").strip())
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
            raise UrlSafetyError("URL 仅支持公开的 http 或 https 地址")
        if parsed.username or parsed.password:
            raise UrlSafetyError("URL 不允许包含用户名或密码")

        host = parsed.hostname.strip().lower()
        if host == "localhost" or host.endswith(".localhost"):
            raise UrlSafetyError("URL 不允许访问本机地址")
        try:
            port = parsed.port or (443 if parsed.scheme.lower() == "https" else 80)
        except ValueError as exc:
            raise UrlSafetyError("URL 端口不合法") from exc
        addresses = await asyncio.to_thread(self._resolve_addresses, host, port)
        if not addresses or any(not address.is_global for address in addresses):
            raise UrlSafetyError("URL 不允许访问私网、回环、链路本地或保留地址")

    @staticmethod
    def _resolve_addresses(host: str, port: int) -> list[Any]:
        try:
            return [ipaddress.ip_address(host.strip("[]"))]
        except ValueError:
            pass

        addresses = []
        try:
            records = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        except OSError as exc:
            raise UrlSafetyError(f"URL 域名解析失败: {exc}") from exc
        for record in records:
            address = ipaddress.ip_address(record[4][0])
            if address not in addresses:
                addresses.append(address)
        return addresses


class UrlFetchCache:
    """在一个 DeepSearch 运行内缓存 URL 成功和失败结果。"""

    def __init__(
        self,
        *,
        timeout_seconds: int = 30,
        validator: UrlSafetyValidator | None = None,
    ) -> None:
        self.validator = validator or UrlSafetyValidator()
        self.fetcher = WebFetcher(url_validator=self.validator)
        self.timeout_seconds = timeout_seconds
        self._documents: dict[str, Doc] = {}
        self._failures: dict[str, str] = {}
        self._locks: dict[str, asyncio.Lock] = {}

    async def get(
        self,
        url: str,
        *,
        request_id: str,
        origin: str,
        depth: int,
        parent_url: str = "",
    ) -> Doc:
        normalized = normalize_url(url)
        if not normalized:
            raise UrlSafetyError("URL 不能为空")

        cached = self._documents.get(normalized)
        if cached is not None:
            return self._decorate(copy.deepcopy(cached), origin, depth, parent_url)
        if normalized in self._failures:
            raise UrlSafetyError(self._failures[normalized])

        lock = self._locks.setdefault(normalized, asyncio.Lock())
        async with lock:
            cached = self._documents.get(normalized)
            if cached is not None:
                return self._decorate(copy.deepcopy(cached), origin, depth, parent_url)
            if normalized in self._failures:
                raise UrlSafetyError(self._failures[normalized])

            try:
                result = await self.fetcher.fetch(
                    WebFetchRequest(
                        requestId=request_id,
                        url=normalized,
                        timeoutSeconds=self.timeout_seconds,
                    )
                )
            except Exception as exc:
                self._failures[normalized] = str(exc)
                raise UrlSafetyError(str(exc)) from exc

            final_url = normalize_url(result.final_url)
            doc = Doc(
                doc_type="web_page",
                content=result.content,
                title=result.title,
                link=final_url,
                data={
                    "origin": origin,
                    "depth": depth,
                    "original_url": normalized,
                    "final_url": final_url,
                    "parent_url": normalize_url(parent_url),
                    "link_candidates": [
                        candidate.to_dict()
                        for candidate in result.link_candidates
                    ],
                },
            )
            self._documents[normalized] = doc
            if final_url and final_url != normalized:
                self._documents[final_url] = doc
            return self._decorate(copy.deepcopy(doc), origin, depth, parent_url)

    def cached(self, url: str) -> Doc | None:
        doc = self._documents.get(normalize_url(url))
        return copy.deepcopy(doc) if doc is not None else None

    def has_any(self, url: str) -> bool:
        normalized = normalize_url(url)
        return normalized in self._documents or normalized in self._failures

    @property
    def fetched_urls(self) -> list[str]:
        return list(self._documents)

    @property
    def failures(self) -> dict[str, str]:
        return dict(self._failures)

    @staticmethod
    def _decorate(
        doc: Doc, origin: str, depth: int, parent_url: str
    ) -> Doc:
        doc.data = dict(doc.data or {})
        doc.data.update(
            {
                "origin": origin,
                "depth": depth,
                "parent_url": normalize_url(parent_url),
            }
        )
        return doc
