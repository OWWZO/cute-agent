# -*- coding: utf-8 -*-
"""单网页抓取与正文提取能力。

下载 HTML → trafilatura/BeautifulSoup 抽正文 → 将完整内容返回给调用方。
"""

import asyncio
import os
import re
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import unquote, urljoin, urlparse, urlunparse

import aiohttp
import trafilatura
from bs4 import BeautifulSoup

from cute_tool.model.protocal import WebFetchRequest


DEFAULT_TIMEOUT_SECONDS = 30
DEFAULT_USER_AGENT = "CuteToolWebFetch/1.0"
DEFAULT_MAX_LINK_CANDIDATES = 20
DEFAULT_MAX_RESPONSE_BYTES = 2_000_000
MAX_REDIRECTS = 5
_STATIC_RESOURCE_SUFFIXES = {
    ".7z",
    ".avi",
    ".css",
    ".csv",
    ".doc",
    ".docx",
    ".gif",
    ".gz",
    ".ico",
    ".jpeg",
    ".jpg",
    ".js",
    ".json",
    ".m4a",
    ".mov",
    ".mp3",
    ".mp4",
    ".pdf",
    ".png",
    ".ppt",
    ".pptx",
    ".rar",
    ".svg",
    ".tar",
    ".webm",
    ".webp",
    ".woff",
    ".woff2",
    ".xls",
    ".xlsx",
    ".xml",
    ".zip",
}


class WebFetchError(ValueError):
    """带阶段、错误码和 HTTP 状态的可诊断抓取异常。"""

    def __init__(
        self,
        message: str,
        *,
        status_code: int,
        error_code: str,
        stage: str,
        upstream_status: int | None = None,
    ):
        super().__init__(message)
        self.status_code = status_code
        self.error_code = error_code
        self.stage = stage
        self.upstream_status = upstream_status


def _http_error_code(status_code: int) -> str:
    return {
        401: "HTTP_UNAUTHORIZED",
        403: "HTTP_FORBIDDEN",
        404: "HTTP_NOT_FOUND",
        408: "HTTP_TIMEOUT",
        429: "HTTP_RATE_LIMITED",
    }.get(
        status_code,
        "UPSTREAM_HTTP_ERROR" if status_code >= 500 else "HTTP_ERROR",
    )


@dataclass
class DownloadedPage:
    """下载后的网页原始信息。"""

    final_url: str
    raw_content: str
    content_type: str
    status_code: int = 200
    status_text: str = ""


@dataclass
class ExtractedContent:
    """正文提取结果。"""

    title: str
    content: str
    content_format: str
    content_source: str
    metadata: dict[str, Any]
    link_candidates: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class LinkCandidate:
    """已抓页面中发现的一个可继续下钻链接。"""

    url: str
    title: str
    anchor: str
    parent_url: str
    depth: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "url": self.url,
            "title": self.title,
            "anchor": self.anchor,
            "parent_url": self.parent_url,
            "depth": self.depth,
        }


@dataclass
class WebFetchResult:
    """web_fetch 返回给 API 层的标准结果。"""

    title: str
    final_url: str
    content: str  # 完整正文，由 Java 侧结合 prompt 提取
    content_format: str
    word_count: int
    content_source: str
    metadata: dict[str, Any]
    status_code: int
    status_text: str
    link_candidates: list[LinkCandidate] = field(default_factory=list)

    def to_response_data(self) -> dict[str, Any]:
        """组装只包含网页内容的响应，避免把抓取结果落为文件产物。"""
        return {
            "title": self.title,
            "finalUrl": self.final_url,
            "content": self.content,
            "contentFormat": self.content_format,
            "wordCount": self.word_count,
            "contentSource": self.content_source,
            "metadata": self.metadata,
            "statusCode": self.status_code,
            "statusText": self.status_text,
            "linkCandidates": [
                candidate.to_dict() for candidate in self.link_candidates
            ],
        }


class WebFetcher:
    """抓取单个网页并提取正文。"""

    def __init__(self, url_validator=None):
        self.proxy = os.getenv("PROXY", "").strip()
        self.url_validator = url_validator

    async def fetch(self, request: WebFetchRequest) -> WebFetchResult:
        """抓取网页、提取正文，并生成统一返回结果。"""
        # Python 只负责抓取和提取；正文完整返回，由 Java 侧结合 prompt 分析。
        try:
            page = await self._download_page(
                request.url, request.timeout_seconds
            )
        except WebFetchError:
            raise
        except (asyncio.TimeoutError, TimeoutError) as exc:
            raise WebFetchError(
                "网页下载超时",
                status_code=504,
                error_code="DOWNLOAD_TIMEOUT",
                stage="download",
            ) from exc
        except aiohttp.ClientError as exc:
            raise WebFetchError(
                "网页下载失败",
                status_code=502,
                error_code="DOWNLOAD_FAILED",
                stage="download",
            ) from exc
        extracted = self._extract_content(page)
        if not extracted.content:
            raise WebFetchError(
                "网页正文提取失败",
                status_code=422,
                error_code="CONTENT_EXTRACTION_FAILED",
                stage="extract",
            )

        normalized_title = extracted.title or self._build_title_from_url(page.final_url)
        return WebFetchResult(
            title=normalized_title,
            final_url=page.final_url,
            content=extracted.content,
            content_format=extracted.content_format,
            word_count=self._count_words(extracted.content),
            content_source=extracted.content_source,
            metadata=extracted.metadata,
            status_code=page.status_code,
            status_text=page.status_text,
            link_candidates=[
                LinkCandidate(**item)
                for item in extracted.link_candidates
            ],
        )

    async def _download_page(self, url: str, timeout_seconds: int) -> DownloadedPage:
        """下载网页或文本内容，供后续按内容类型分流处理。"""
        if self.url_validator is not None:
            return await self._download_validated_page(url, timeout_seconds)

        # 下载阶段只负责状态码、内容类型和空响应校验，不在这里混入 HTML/Markdown 的正文提取规则。
        client_timeout = aiohttp.ClientTimeout(
            total=timeout_seconds or DEFAULT_TIMEOUT_SECONDS
        )
        headers = {"User-Agent": DEFAULT_USER_AGENT}
        async with aiohttp.ClientSession(
            timeout=client_timeout, headers=headers
        ) as session:
            request_kwargs: dict[str, Any] = {"allow_redirects": True}
            if self.proxy:
                request_kwargs["proxy"] = self.proxy
            async with session.get(url, **request_kwargs) as response:
                self._raise_for_status(response)
                content_type = (response.headers.get("Content-Type") or "").lower()
                if not self._is_supported_content_type(content_type):
                    raise WebFetchError(
                        "web_fetch 仅支持 HTML、Markdown 或纯文本内容",
                        status_code=415,
                        error_code="CONTENT_TYPE_UNSUPPORTED",
                        stage="download",
                    )
                raw_content = await self._read_response_text(response)
                if not raw_content.strip():
                    raise WebFetchError(
                        "网页内容为空",
                        status_code=422,
                        error_code="CONTENT_EMPTY",
                        stage="download",
                    )
                return DownloadedPage(
                    final_url=str(response.url),
                    raw_content=raw_content,
                    content_type=content_type,
                    status_code=getattr(response, "status", 200),
                    status_text=str(getattr(response, "reason", "") or ""),
                )

    async def _download_validated_page(
        self, url: str, timeout_seconds: int
    ) -> DownloadedPage:
        """逐跳校验重定向目标后下载，供 DeepSearch 的公开 URL 抓取使用。"""
        client_timeout = aiohttp.ClientTimeout(
            total=timeout_seconds or DEFAULT_TIMEOUT_SECONDS
        )
        headers = {"User-Agent": DEFAULT_USER_AGENT}
        current_url = url
        async with aiohttp.ClientSession(
            timeout=client_timeout, headers=headers
        ) as session:
            for redirect_count in range(MAX_REDIRECTS + 1):
                await self.url_validator.validate(current_url)
                request_kwargs: dict[str, Any] = {"allow_redirects": False}
                if self.proxy:
                    request_kwargs["proxy"] = self.proxy
                async with session.get(current_url, **request_kwargs) as response:
                    status_code = int(getattr(response, "status", 200))
                    if 300 <= status_code < 400:
                        if redirect_count >= MAX_REDIRECTS:
                            raise WebFetchError(
                                "web_fetch 重定向次数超过限制",
                                status_code=502,
                                error_code="UPSTREAM_REDIRECT_LIMIT",
                                stage="download",
                                upstream_status=status_code,
                            )
                        location = (response.headers.get("Location") or "").strip()
                        if not location:
                            raise WebFetchError(
                                "web_fetch 收到无 Location 的重定向",
                                status_code=502,
                                error_code="UPSTREAM_REDIRECT_INVALID",
                                stage="download",
                                upstream_status=status_code,
                            )
                        current_url = urljoin(current_url, location)
                        continue

                    self._raise_for_status(response)
                    content_type = (
                        response.headers.get("Content-Type") or ""
                    ).lower()
                    if not self._is_supported_content_type(content_type):
                        raise WebFetchError(
                            "web_fetch 仅支持 HTML、Markdown 或纯文本内容",
                            status_code=415,
                            error_code="CONTENT_TYPE_UNSUPPORTED",
                            stage="download",
                        )
                    raw_content = await self._read_response_text(response)
                    if not raw_content.strip():
                        raise WebFetchError(
                            "网页内容为空",
                            status_code=422,
                            error_code="CONTENT_EMPTY",
                            stage="download",
                        )
                    return DownloadedPage(
                        final_url=str(response.url),
                        raw_content=raw_content,
                        content_type=content_type,
                        status_code=status_code,
                        status_text=str(getattr(response, "reason", "") or ""),
                    )
        raise WebFetchError(
            "web_fetch 重定向次数超过限制",
            status_code=502,
            error_code="UPSTREAM_REDIRECT_LIMIT",
            stage="download",
        )

    def _raise_for_status(self, response) -> None:
        """保留目标网站的 HTTP 状态，避免 403/404/429 被统一改写成 502。"""
        try:
            response.raise_for_status()
        except aiohttp.ClientResponseError as exc:
            status_code = int(
                getattr(exc, "status", None)
                or getattr(response, "status", 502)
            )
            reason = str(
                getattr(exc, "message", "")
                or getattr(response, "reason", "")
                or ""
            ).strip()
            message = f"目标网页返回 HTTP {status_code}"
            if reason:
                message = f"{message} {reason}"
            raise WebFetchError(
                message,
                status_code=status_code,
                error_code=_http_error_code(status_code),
                stage="download",
                upstream_status=status_code,
            ) from exc

    async def _read_response_text(self, response) -> str:
        """限制单页响应体大小，避免异常大页面占满内存和上下文。"""
        max_bytes = max(
            1,
            int(
                os.getenv(
                    "WEB_FETCH_MAX_RESPONSE_BYTES",
                    str(DEFAULT_MAX_RESPONSE_BYTES),
                )
            ),
        )
        content_length = (response.headers.get("Content-Length") or "").strip()
        if content_length.isdigit() and int(content_length) > max_bytes:
            raise WebFetchError(
                "网页响应体超过大小限制",
                status_code=413,
                error_code="RESPONSE_TOO_LARGE",
                stage="download",
            )

        content = getattr(response, "content", None)
        if content is not None and hasattr(content, "read"):
            raw_bytes = await content.read(max_bytes + 1)
            if len(raw_bytes) > max_bytes:
                raise WebFetchError(
                    "网页响应体超过大小限制",
                    status_code=413,
                    error_code="RESPONSE_TOO_LARGE",
                    stage="download",
                )
            encoding = getattr(response, "charset", None) or "utf-8"
            try:
                return raw_bytes.decode(encoding, errors="ignore")
            except LookupError:
                return raw_bytes.decode("utf-8", errors="ignore")
        return await response.text(errors="ignore")

    def _extract_content(self, page: DownloadedPage) -> ExtractedContent:
        """按响应类型选择提取策略，HTML 走正文提取，文本直接复用原文。"""
        # 内容类型决定解析器：HTML 需要去导航噪声，纯文本/Markdown 应保留原始语义和格式。
        if self._is_html_content_type(page.content_type):
            return self._extract_html_content(page.raw_content, page.final_url)
        return self._extract_text_content(
            page.raw_content, page.final_url, page.content_type
        )

    def _extract_html_content(self, html: str, final_url: str) -> ExtractedContent:
        """优先使用 trafilatura 提取 HTML 正文，失败时回退到 BeautifulSoup。"""
        soup = BeautifulSoup(html, "html.parser")
        title = self._extract_title(soup)
        metadata = self._extract_metadata(soup)

        content = trafilatura.extract(
            html,
            url=final_url,
            output_format="markdown",
            include_comments=False,
            include_tables=True,
            include_links=True,
        )
        normalized_content = self._normalize_content(content)
        link_candidates = self._extract_link_candidates(soup, final_url)
        if normalized_content:
            return ExtractedContent(
                title=title,
                content=normalized_content,
                content_format="markdown",
                content_source="trafilatura",
                metadata=metadata,
                link_candidates=link_candidates,
            )

        # trafilatura 没有提取到正文时再走 BeautifulSoup，保证简单页面仍可返回可读内容。
        fallback_content = self._extract_with_beautifulsoup(soup)
        return ExtractedContent(
            title=title,
            content=fallback_content,
            content_format="markdown",
            content_source="beautifulsoup",
            metadata=metadata,
            link_candidates=link_candidates,
        )

    def _extract_text_content(
        self, raw_content: str, final_url: str, content_type: str
    ) -> ExtractedContent:
        """处理 Markdown 与纯文本响应，避免 raw 文件和 Reader 文本链路被误判为异常。"""
        normalized_content = self._normalize_content(raw_content)
        title = self._build_title_from_url(final_url)
        return ExtractedContent(
            title=title,
            content=normalized_content,
            content_format="markdown" if "markdown" in content_type else "text",
            content_source="plain_text",
            metadata={
                "description": "",
                "siteName": urlparse(final_url).netloc,
            },
        )

    def _extract_with_beautifulsoup(self, soup: BeautifulSoup) -> str:
        """在主提取器失败时，尽量保留可读文本作为兜底。"""
        for tag in soup(["script", "style", "noscript", "svg"]):
            tag.decompose()
        text = soup.get_text("\n")
        return self._normalize_content(text)

    def _extract_title(self, soup: BeautifulSoup) -> str:
        """优先取 OpenGraph 标题，其次取 HTML title。"""
        og_title = soup.find("meta", attrs={"property": "og:title"})
        if og_title and og_title.get("content"):
            return og_title["content"].strip()
        if soup.title and soup.title.string:
            return soup.title.string.strip()
        h1 = soup.find("h1")
        return h1.get_text(strip=True) if h1 else ""

    def _extract_metadata(self, soup: BeautifulSoup) -> dict[str, Any]:
        """提取最小 metadata，供 Java 侧调试和展示。"""
        return {
            "description": self._extract_meta_content(soup, "name", "description"),
            "siteName": self._extract_meta_content(soup, "property", "og:site_name"),
        }

    def _extract_link_candidates(
        self, soup: BeautifulSoup, final_url: str
    ) -> list[dict[str, Any]]:
        """从 HTML 中提取少量去重链接，供 Reviewer 明确选择下钻目标。"""
        limit = max(
            0,
            int(
                os.getenv(
                    "WEB_FETCH_MAX_LINK_CANDIDATES",
                    str(DEFAULT_MAX_LINK_CANDIDATES),
                )
            ),
        )
        if limit == 0:
            return []

        candidates = []
        seen = set()
        for anchor_tag in soup.find_all("a", href=True):
            absolute_url = self._normalize_link_candidate(
                urljoin(final_url, anchor_tag["href"].strip())
            )
            if not absolute_url or absolute_url in seen:
                continue
            parsed = urlparse(absolute_url)
            if parsed.scheme not in {"http", "https"} or not parsed.netloc:
                continue
            path_suffix = os.path.splitext(parsed.path.lower())[1]
            if path_suffix in _STATIC_RESOURCE_SUFFIXES:
                continue
            anchor = " ".join(anchor_tag.get_text(" ", strip=True).split())[:200]
            title = (anchor_tag.get("title") or anchor or absolute_url).strip()[:200]
            candidates.append(
                LinkCandidate(
                    url=absolute_url,
                    title=title,
                    anchor=anchor,
                    parent_url=final_url,
                    depth=1,
                ).to_dict()
            )
            seen.add(absolute_url)
            if len(candidates) >= limit:
                break
        return candidates

    def _normalize_link_candidate(self, url: str) -> str:
        """清除 fragment，并去掉纯锚点和非 http(s) 链接。"""
        value = (url or "").strip()
        if not value:
            return ""
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            return ""
        return urlunparse(
            (
                parsed.scheme.lower(),
                parsed.netloc.lower(),
                parsed.path,
                parsed.params,
                parsed.query,
                "",
            )
        )

    def _extract_meta_content(
        self, soup: BeautifulSoup, attr_name: str, attr_value: str
    ) -> str:
        tag = soup.find("meta", attrs={attr_name: attr_value})
        content = tag.get("content") if tag else ""
        return content.strip() if content else ""

    def _normalize_content(self, content: str | None) -> str:
        """统一清洗换行和空白，保证正文能稳定传给调用方。"""
        if not content:
            return ""
        normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        normalized = re.sub(r"\n{3,}", "\n\n", normalized)
        normalized = re.sub(r"[ \t]+\n", "\n", normalized)
        return normalized.strip()

    def _is_supported_content_type(self, content_type: str) -> bool:
        """仅允许 HTML、Markdown 和纯文本，避免误抓二进制。"""
        if not content_type:
            return True
        return self._is_html_content_type(content_type) or any(
            marker in content_type
            for marker in ("text/plain", "text/markdown", "text/x-markdown")
        )

    def _is_html_content_type(self, content_type: str) -> bool:
        """识别可走正文提取器的 HTML 响应。"""
        return "html" in content_type or "xhtml" in content_type

    def _count_words(self, content: str) -> int:
        """近似统计正文词数，兼容中英文混排。"""
        tokens = re.findall(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]", content or "")
        return len(tokens)

    def _build_title_from_url(self, url: str) -> str:
        """标题缺失时回退到 URL slug，避免文件名不可读。"""
        parsed = urlparse(url)
        path_segments = [segment for segment in parsed.path.split("/") if segment]
        if path_segments:
            candidate = unquote(path_segments[-1]).rsplit(".", 1)[0]
            sanitized = self._sanitize_title_candidate(candidate)
            if sanitized:
                return sanitized
        sanitized_host = self._sanitize_title_candidate(parsed.netloc.replace(":", "_"))
        return sanitized_host or "web_fetch_result"

    def _sanitize_title_candidate(self, value: str) -> str:
        """清洗 URL 回退标题中的非法字符。"""
        cleaned = re.sub(r"[<>:\"/\\\\|?*\x00-\x1f]", " ", value or "")
        cleaned = re.sub(r"\s+", "_", cleaned).strip("._ ")
        return cleaned[:80]
