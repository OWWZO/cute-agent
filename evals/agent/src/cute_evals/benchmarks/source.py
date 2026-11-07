from __future__ import annotations

import os
import urllib.request


def download_official_archive(url: str, *, timeout: int = 300) -> bytes:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "cute-evals",
    }
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()
