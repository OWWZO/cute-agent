# -*- coding: utf-8 -*-
import unittest
from unittest.mock import AsyncMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from cute_tool.api.tool import router
from cute_tool.tool.web_fetcher import WebFetchError, WebFetchResult


class WebFetchApiTest(unittest.TestCase):
    def setUp(self):
        app = FastAPI()
        app.include_router(router, prefix="/v1/tool")
        self.client = TestClient(app)

    def test_should_return_complete_content_without_file_info(self):
        fetch_result = WebFetchResult(
            title="测试网页",
            final_url="https://example.com/final",
            content="# 标题\n\n完整正文",
            content_format="markdown",
            word_count=4,
            content_source="trafilatura",
            metadata={"description": "页面描述", "siteName": "Example"},
            status_code=200,
            status_text="OK",
        )

        with patch(
            "cute_tool.api.tool.WebFetcher.fetch",
            new=AsyncMock(return_value=fetch_result),
        ):
            with patch(
                "cute_tool.api.tool.upload_file", new=AsyncMock()
            ) as upload_mock:
                response = self.client.post(
                    "/v1/tool/web_fetch",
                    json={
                        "requestId": "req-web-api-001",
                        "url": "https://example.com/page",
                    },
                )

        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual(200, body["code"])
        self.assertEqual("req-web-api-001", body["requestId"])
        self.assertEqual("测试网页", body["data"]["title"])
        self.assertEqual("https://example.com/final", body["data"]["finalUrl"])
        self.assertEqual("markdown", body["data"]["contentFormat"])
        self.assertEqual("# 标题\n\n完整正文", body["data"]["content"])
        self.assertNotIn("fileInfo", body)
        upload_mock.assert_not_awaited()

    def test_should_return_400_when_fetcher_rejects_request(self):
        with patch(
            "cute_tool.api.tool.WebFetcher.fetch",
            new=AsyncMock(
                side_effect=ValueError("web_fetch 仅支持 HTML、Markdown 或纯文本内容")
            ),
        ):
            response = self.client.post(
                "/v1/tool/web_fetch",
                json={
                    "requestId": "req-web-api-002",
                    "url": "https://example.com/file.pdf",
                },
            )

        self.assertEqual(400, response.status_code)
        body = response.json()
        self.assertEqual(400, body["code"])
        self.assertEqual(
            "web_fetch 仅支持 HTML、Markdown 或纯文本内容", body["message"]
        )
        self.assertEqual(
            "INVALID_WEB_FETCH_REQUEST", body["errorCode"]
        )

    def test_should_preserve_upstream_http_status_and_error_code(self):
        cases = (
            (403, "HTTP_FORBIDDEN"),
            (404, "HTTP_NOT_FOUND"),
            (429, "HTTP_RATE_LIMITED"),
        )
        for status_code, error_code in cases:
            with self.subTest(status_code=status_code):
                with patch(
                    "cute_tool.api.tool.WebFetcher.fetch",
                    new=AsyncMock(
                        side_effect=WebFetchError(
                            f"目标网页返回 HTTP {status_code}",
                            status_code=status_code,
                            error_code=error_code,
                            stage="download",
                            upstream_status=status_code,
                        )
                    ),
                ):
                    response = self.client.post(
                        "/v1/tool/web_fetch",
                        json={
                            "requestId": f"req-web-api-{status_code}",
                            "url": "https://example.com/page",
                        },
                    )

                self.assertEqual(status_code, response.status_code)
                body = response.json()
                self.assertEqual(status_code, body["code"])
                self.assertEqual(error_code, body["errorCode"])
                self.assertEqual("download", body["stage"])
                self.assertEqual(
                    status_code, body["upstreamStatus"]
                )

    def test_should_return_distinct_extraction_error(self):
        with patch(
            "cute_tool.api.tool.WebFetcher.fetch",
            new=AsyncMock(
                side_effect=WebFetchError(
                    "网页正文提取失败",
                    status_code=422,
                    error_code="CONTENT_EXTRACTION_FAILED",
                    stage="extract",
                )
            ),
        ):
            response = self.client.post(
                "/v1/tool/web_fetch",
                json={
                    "requestId": "req-web-api-extract",
                    "url": "https://example.com/script-only",
                },
            )

        self.assertEqual(422, response.status_code)
        body = response.json()
        self.assertEqual("CONTENT_EXTRACTION_FAILED", body["errorCode"])
        self.assertEqual("extract", body["stage"])


if __name__ == "__main__":
    unittest.main()
