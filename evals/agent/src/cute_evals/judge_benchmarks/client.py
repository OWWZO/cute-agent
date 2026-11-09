from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass


class JudgeRequestError(RuntimeError):
    def __init__(self, kind: str, message: str) -> None:
        super().__init__(message)
        self.kind = kind


@dataclass(frozen=True, slots=True)
class OpenAICompatibleJudge:
    model: str
    base_url: str
    api_key: str
    temperature: float = 0.0
    top_p: float = 1.0
    max_tokens: int = 8192
    timeout_seconds: int = 300

    def complete(
        self,
        messages: list[dict[str, str]],
        *,
        timeout_seconds: int | None = None,
    ) -> str:
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": self.temperature,
            "top_p": self.top_p,
            "max_tokens": self.max_tokens,
        }
        request = urllib.request.Request(
            self.base_url.rstrip("/") + "/chat/completions",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(
                request,
                timeout=timeout_seconds or self.timeout_seconds,
            ) as response:
                data = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            kind = "rate_limited" if exc.code == 429 else "http_error"
            raise JudgeRequestError(
                kind,
                f"judge request failed with HTTP {exc.code}",
            ) from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise JudgeRequestError(
                "timeout" if isinstance(exc, TimeoutError) else "network_error",
                f"judge request failed: {exc}",
            ) from exc
        except json.JSONDecodeError as exc:
            raise JudgeRequestError(
                "invalid_json",
                "judge endpoint returned invalid JSON",
            ) from exc
        try:
            content = data["choices"][0]["message"].get("content")
        except (KeyError, IndexError, TypeError) as exc:
            raise JudgeRequestError(
                "invalid_response",
                "judge response is missing choices[0].message.content",
            ) from exc
        if isinstance(content, list):
            content = "".join(
                str(item.get("text") or "")
                for item in content
                if isinstance(item, dict)
            )
        if not isinstance(content, str) or not content.strip():
            raise JudgeRequestError(
                "empty_response",
                "judge returned an empty response",
            )
        return content
