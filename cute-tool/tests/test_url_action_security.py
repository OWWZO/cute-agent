# -*- coding: utf-8 -*-
import socket

import pytest

from cute_tool.tool.search_component.url_fetch import (
    UrlSafetyError,
    UrlSafetyValidator,
)


def _record(ip: str):
    return (
        socket.AF_INET,
        socket.SOCK_STREAM,
        6,
        "",
        (ip, 443),
    )


@pytest.mark.asyncio
async def test_url_safety_accepts_public_dns_addresses(monkeypatch):
    monkeypatch.setattr(
        "cute_tool.tool.search_component.url_fetch.socket.getaddrinfo",
        lambda *args, **kwargs: [_record("93.184.216.34")],
    )

    await UrlSafetyValidator().validate("https://example.com/report")


@pytest.mark.parametrize(
    "url,address",
    [
        ("http://localhost/admin", "93.184.216.34"),
        ("http://127.0.0.1/admin", "93.184.216.34"),
        ("http://192.168.1.10/admin", "93.184.216.34"),
        ("http://public.example/admin", "10.0.0.8"),
        ("http://public.example/admin", "::1"),
        ("http://public.example/admin", "fe80::1"),
        ("http://public.example/admin", "169.254.169.254"),
    ],
)
@pytest.mark.asyncio
async def test_url_safety_rejects_local_private_and_link_local_targets(
    monkeypatch, url, address
):
    monkeypatch.setattr(
        "cute_tool.tool.search_component.url_fetch.socket.getaddrinfo",
        lambda *args, **kwargs: [_record(address)],
    )

    with pytest.raises(UrlSafetyError):
        await UrlSafetyValidator().validate(url)


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "javascript:alert(1)",
        "https://user:pass@example.com/report",
    ],
)
@pytest.mark.asyncio
async def test_url_safety_rejects_unsupported_or_credentialed_urls(url):
    with pytest.raises(UrlSafetyError):
        await UrlSafetyValidator().validate(url)
