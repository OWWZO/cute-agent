from __future__ import annotations

import os
import urllib.request
from urllib.parse import urlsplit


def download_official_archive(url: str, *, timeout: int = 300) -> bytes:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "cute-evals",
    }
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    host = (urlsplit(url).hostname or "").lower()
    github_host = host in {
        "github.com",
        "api.github.com",
        "codeload.github.com",
    } or host.endswith((".github.com", ".githubusercontent.com"))
    if token and github_host:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()
