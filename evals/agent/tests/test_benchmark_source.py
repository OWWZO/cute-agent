from __future__ import annotations

from cute_evals.benchmarks import source


class _Response:
    def __enter__(self) -> _Response:
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None

    def read(self) -> bytes:
        return b"archive"


def test_download_only_sends_github_token_to_github_hosts(monkeypatch) -> None:
    requests = []

    def fake_urlopen(request, timeout):
        requests.append(request)
        return _Response()

    monkeypatch.setenv("GITHUB_TOKEN", "secret-token")
    monkeypatch.delenv("GH_TOKEN", raising=False)
    monkeypatch.setattr(source.urllib.request, "urlopen", fake_urlopen)

    source.download_official_archive("https://github.com/example/archive.tar.gz")
    source.download_official_archive("https://example.com/archive.tar.gz")
    source.download_official_archive("https://github.com.evil.example/archive.tar.gz")

    assert requests[0].get_header("Authorization") == "Bearer secret-token"
    assert requests[1].get_header("Authorization") is None
    assert requests[2].get_header("Authorization") is None
