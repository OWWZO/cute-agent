from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.request

from ...models import JudgeConfig
from .official_prompt import get_anscheck_prompt, official_label


def _label_from_content(content: object) -> str | None:
    """Return a valid judge label, rejecting empty and sentinel responses."""
    if not isinstance(content, str):
        return None
    value = content.strip()
    if not value or value.casefold() in {"none", "null"}:
        return None
    match = re.search(r"\b(yes|no)\b", value.casefold())
    return match.group(1) if match else None


def judge_longmemeval_answer(
    *,
    judge: JudgeConfig,
    task: str,
    question: str,
    answer: str,
    response: str,
    abstention: bool,
    timeout_seconds: int = 45,
    max_attempts: int = 5,
) -> dict:
    if not judge.model or not judge.base_url or not judge.api_key:
        raise ValueError(
            "LongMemEval requires a configured OpenAI-compatible judge"
        )
    prompt = get_anscheck_prompt(
        task,
        question,
        answer,
        response,
        abstention=abstention,
    )
    payload = {
        "model": judge.model,
        "messages": [{"role": "user", "content": prompt}],
        "n": 1,
        "temperature": 0,
        # The compatibility judge sometimes spends the whole completion budget on
        # hidden reasoning; leave enough room for both reasoning and yes/no.
        "max_tokens": 512,
    }
    last_raw_label: str | None = None
    for attempt in range(max(1, max_attempts)):
        time.sleep(0.2)
        request = urllib.request.Request(
            judge.base_url.rstrip("/") + "/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {judge.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(
                request,
                timeout=timeout_seconds,
            ) as response_stream:
                body = json.loads(response_stream.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")
            raise RuntimeError(
                f"LongMemEval judge returned HTTP {exc.code}: {detail[:1000]}"
            ) from exc
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            raise RuntimeError(f"LongMemEval judge request failed: {exc}") from exc
        try:
            raw_label = body["choices"][0]["message"].get("content")
        except (KeyError, IndexError, TypeError) as exc:
            raise RuntimeError(
                f"LongMemEval judge returned an invalid response: {body}"
            ) from exc
        label = _label_from_content(raw_label)
        if label is not None:
            return {
                "label": official_label(label),
                "rawLabel": str(raw_label),
                "prompt": prompt,
                "model": judge.model,
            }
        last_raw_label = str(raw_label)
        time.sleep(min(10.0, 2.0 ** attempt))
    raise RuntimeError(
        "LongMemEval judge did not return a yes/no label after retries: "
        f"{last_raw_label!r}"
    )
