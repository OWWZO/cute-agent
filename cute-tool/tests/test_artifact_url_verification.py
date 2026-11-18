from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from cute_tool.util.file_util import verify_uploaded_file_url


@pytest.mark.asyncio
async def test_verify_uploaded_file_url_rejects_http_error(monkeypatch):
    monkeypatch.setenv("FILE_SERVER_URL", "https://files.example.com")
    response = MagicMock()
    response.status = 404
    session = MagicMock()
    session.get.return_value.__aenter__ = AsyncMock(return_value=response)
    session.get.return_value.__aexit__ = AsyncMock(return_value=None)
    client = MagicMock()
    client.__aenter__ = AsyncMock(return_value=session)
    client.__aexit__ = AsyncMock(return_value=None)

    with patch("cute_tool.util.file_util.aiohttp.ClientSession", return_value=client):
        with pytest.raises(RuntimeError, match="HTTP 404"):
            await verify_uploaded_file_url("download", "https://files.example.com/download/req/a.txt")


@pytest.mark.asyncio
async def test_verify_uploaded_file_url_accepts_preview(monkeypatch):
    monkeypatch.setenv("FILE_SERVER_URL", "https://files.example.com")
    response = MagicMock()
    response.status = 200
    session = MagicMock()
    session.get.return_value.__aenter__ = AsyncMock(return_value=response)
    session.get.return_value.__aexit__ = AsyncMock(return_value=None)
    client = MagicMock()
    client.__aenter__ = AsyncMock(return_value=session)
    client.__aexit__ = AsyncMock(return_value=None)

    with patch("cute_tool.util.file_util.aiohttp.ClientSession", return_value=client):
        await verify_uploaded_file_url("preview", "https://files.example.com/preview/req/a.txt")

    session.get.assert_called_once()
