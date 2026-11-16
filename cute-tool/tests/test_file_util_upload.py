# -*- coding: utf-8 -*-
import asyncio
import os
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from cute_tool.util.file_util import (
    _get_file_storage_target,
    upload_file,
    upload_file_by_path,
)


class _FakeResponse:
    def __init__(self, status: int, body: str):
        self.status = status
        self.body = body

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return False

    async def text(self):
        return self.body


class _FakeSession:
    def __init__(self, response):
        self.response = response
        self.posts = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return False

    def post(self, url, **kwargs):
        self.posts.append((url, kwargs))
        return self.response


class _TimeoutSession(_FakeSession):
    def post(self, url, **kwargs):
        self.posts.append((url, kwargs))
        raise asyncio.TimeoutError()


class FileUtilUploadTest(unittest.IsolatedAsyncioTestCase):
    async def test_should_prefer_internal_file_server_for_container_uploads(self):
        with patch.dict(
            os.environ,
            {
                "FILE_SERVER_URL": "https://public.example/tool/v1/file_tool",
                "FILE_SERVER_INTERNAL_URL": "http://cute-tool:1601/v1/file_tool",
            },
            clear=False,
        ):
            self.assertEqual(
                _get_file_storage_target(),
                "http://cute-tool:1601/v1/file_tool",
            )

    async def test_should_write_utf8_long_file_name_to_local_storage(self):
        with tempfile.TemporaryDirectory(prefix="file-util-upload-") as storage_root:
            with patch.dict(os.environ, {"FILE_SERVER_URL": storage_root}, clear=False):
                result = await upload_file(
                    content="report",
                    file_name="测" * 120 + ".txt",
                    file_type="txt",
                    request_id="request-1",
                )

            target = Path(result["downloadUrl"])
            self.assertTrue(target.is_file())
            self.assertLessEqual(len(target.name.encode("utf-8")), 240)
            self.assertTrue(target.name.endswith(".txt"))

    async def test_should_report_http_error_before_parsing_empty_response(self):
        session = _FakeSession(_FakeResponse(500, ""))
        with (
            patch.dict(
                os.environ, {"FILE_SERVER_URL": "http://file-service"}, clear=False
            ),
            patch(
                "cute_tool.util.file_util.aiohttp.ClientSession",
                return_value=session,
            ),
        ):
            with self.assertRaisesRegex(
                RuntimeError, "text file upload failed with HTTP 500"
            ):
                await upload_file(
                    content="report",
                    file_name="analysis.md",
                    file_type="md",
                    request_id="request-1",
                )

    async def test_shared_workspace_file_should_register_without_reuploading(self):
        with tempfile.TemporaryDirectory(prefix="file-util-shared-") as storage_root:
            source = Path(storage_root) / "nested" / "report.txt"
            source.parent.mkdir()
            source.write_text("report", encoding="utf-8")
            expected_size = source.stat().st_size
            session = _FakeSession(
                _FakeResponse(
                    200,
                    json.dumps(
                        {
                            "ossUrl": "http://file-service/download/report.txt",
                            "downloadUrl": "http://file-service/download/report.txt",
                            "domainUrl": "http://file-service/preview/report.txt",
                        }
                    ),
                )
            )
            with (
                patch.dict(
                    os.environ,
                    {
                        "FILE_SERVER_INTERNAL_URL": "",
                        "FILE_SERVER_URL": "http://file-service/v1/file_tool",
                        "FILE_SAVE_PATH": storage_root,
                    },
                    clear=False,
                ),
                patch(
                    "cute_tool.util.file_util.aiohttp.ClientSession",
                    return_value=session,
                ),
            ):
                result = await upload_file_by_path(
                    str(source),
                    "request-1",
                    relative_path="nested/report.txt",
                )

        self.assertEqual(1, len(session.posts))
        url, kwargs = session.posts[0]
        self.assertTrue(url.endswith("/register_file"))
        self.assertEqual("request-1", kwargs["json"]["requestId"])
        self.assertEqual("nested/report.txt", kwargs["json"]["fileName"])
        self.assertEqual(str(source.resolve()), kwargs["json"]["localPath"])
        self.assertEqual("nested/report.txt", result["relativePath"])
        self.assertEqual(expected_size, result["fileSize"])

    async def test_external_file_should_still_upload_content(self):
        with tempfile.TemporaryDirectory(prefix="file-util-root-") as storage_root:
            with tempfile.TemporaryDirectory(
                prefix="file-util-external-"
            ) as external_root:
                source = Path(external_root) / "report.txt"
                source.write_text("report", encoding="utf-8")
                session = _FakeSession(
                    _FakeResponse(
                        200,
                        json.dumps(
                            {
                                "downloadUrl": "http://file-service/download/report.txt",
                                "domainUrl": "http://file-service/preview/report.txt",
                                "relativePath": "report.txt",
                            }
                        ),
                    )
                )
                with (
                    patch.dict(
                        os.environ,
                        {
                            "FILE_SERVER_INTERNAL_URL": "",
                            "FILE_SERVER_URL": "http://file-service/v1/file_tool",
                            "FILE_SAVE_PATH": storage_root,
                        },
                        clear=False,
                    ),
                    patch(
                        "cute_tool.util.file_util.aiohttp.ClientSession",
                        return_value=session,
                    ),
                ):
                    result = await upload_file_by_path(source.as_posix(), "request-1")

        self.assertEqual(1, len(session.posts))
        url, kwargs = session.posts[0]
        self.assertTrue(url.endswith("/upload_file_data"))
        self.assertLessEqual(kwargs["timeout"].total, 60)
        self.assertEqual("report.txt", result["relativePath"])

    async def test_shared_file_registration_should_report_http_error(self):
        with tempfile.TemporaryDirectory(prefix="file-util-shared-error-") as storage_root:
            source = Path(storage_root) / "report.txt"
            source.write_text("report", encoding="utf-8")
            session = _FakeSession(_FakeResponse(500, "registration failed"))
            with (
                patch.dict(
                    os.environ,
                    {
                        "FILE_SERVER_INTERNAL_URL": "",
                        "FILE_SERVER_URL": "http://file-service/v1/file_tool",
                        "FILE_SAVE_PATH": storage_root,
                    },
                    clear=False,
                ),
                patch(
                    "cute_tool.util.file_util.aiohttp.ClientSession",
                    return_value=session,
                ),
            ):
                with self.assertRaisesRegex(
                    RuntimeError,
                    "binary file registration failed with HTTP 500",
                ):
                    await upload_file_by_path(source.as_posix(), "request-1")

    async def test_binary_upload_should_time_out_with_explicit_error(self):
        with tempfile.TemporaryDirectory(prefix="file-util-root-") as storage_root:
            with tempfile.TemporaryDirectory(
                prefix="file-util-timeout-"
            ) as external_root:
                source = Path(external_root) / "report.txt"
                source.write_text("report", encoding="utf-8")
                session = _TimeoutSession(_FakeResponse(200, "{}"))
                with (
                    patch.dict(
                        os.environ,
                        {
                            "FILE_SERVER_INTERNAL_URL": "",
                            "FILE_SERVER_URL": "http://file-service/v1/file_tool",
                            "FILE_SAVE_PATH": storage_root,
                        },
                        clear=False,
                    ),
                    patch(
                        "cute_tool.util.file_util.aiohttp.ClientSession",
                        return_value=session,
                    ),
                ):
                    with self.assertRaisesRegex(
                        TimeoutError,
                        "binary file upload timed out after 60s",
                    ):
                        await upload_file_by_path(source.as_posix(), "request-1")


if __name__ == "__main__":
    unittest.main()
