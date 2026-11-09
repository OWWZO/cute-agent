"""InfBench summarization scoring using the pinned official judge prompts."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from ...judge_benchmarks.client import OpenAICompatibleJudge
from ...models import JudgeConfig

PROMPTS_PATH = Path(__file__).with_name("official_summarization_prompts.json")


def _prompts() -> dict[str, str]:
    payload = json.loads(PROMPTS_PATH.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise TypeError(f"invalid InfBench prompt resource: {PROMPTS_PATH}")
    return {str(key): str(value) for key, value in payload.items()}


def parse_judge_json(content: str) -> dict[str, Any]:
    """Return the last valid JSON object embedded in a judge response."""
    decoder = json.JSONDecoder()
    parsed: dict[str, Any] | None = None
    for match in re.finditer(r"\{", content or ""):
        try:
            value, _ = decoder.raw_decode(content[match.start() :])
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            parsed = value
    if parsed is None:
        raise ValueError("InfBench judge response did not contain a JSON object")
    return parsed


def _judge_json(
    judge: JudgeConfig,
    prompt: str,
    *,
    timeout_seconds: int,
) -> tuple[dict[str, Any], str]:
    if not judge.model or not judge.base_url or not judge.api_key:
        raise ValueError("InfBench requires a configured OpenAI-compatible judge")
    client = OpenAICompatibleJudge(
        model=judge.model,
        base_url=judge.base_url,
        api_key=judge.api_key,
        temperature=0.1,
        max_tokens=4096,
        timeout_seconds=timeout_seconds,
    )
    raw = client.complete([{"role": "user", "content": prompt}])
    return parse_judge_json(raw), raw


def score_infbench_summary(
    *,
    judge: JudgeConfig,
    summary: str,
    expert_summary: str,
    keypoints: list[str],
    timeout_seconds: int = 180,
) -> dict[str, Any]:
    """Run the three official InfBench judge calls and combine them into F1."""
    prompts = _prompts()
    keypoint_text = "\n".join(
        f"{index + 1}. {keypoint}"
        for index, keypoint in enumerate(keypoints)
    )
    fluency_prompt = prompts["fluency_prompt_book"].format(
        text=summary.strip(),
    )
    recall_prompt = prompts["recall_prompt_book"].format(
        keypoints=keypoint_text,
        summary=summary.strip(),
    )
    precision_prompt = prompts["precision_prompt_book"].format(
        expert_summary=expert_summary,
        summary=summary.strip(),
    )
    fluency, fluency_raw = _judge_json(
        judge,
        fluency_prompt,
        timeout_seconds=timeout_seconds,
    )
    recall, recall_raw = _judge_json(
        judge,
        recall_prompt,
        timeout_seconds=timeout_seconds,
    )
    precision, precision_raw = _judge_json(
        judge,
        precision_prompt,
        timeout_seconds=timeout_seconds,
    )

    recall_found = int(recall.get("recall"))
    precision_found = int(precision.get("precision"))
    sentence_count = int(precision.get("sentence_count"))
    if recall_found < 0 or recall_found > len(keypoints):
        raise ValueError("InfBench recall judge returned an invalid count")
    if (
        precision_found < 0
        or sentence_count < 0
        or precision_found > sentence_count
    ):
        raise ValueError("InfBench precision judge returned an invalid count")

    recall_score = recall_found / len(keypoints) if keypoints else 0.0
    precision_score = (
        precision_found / sentence_count
        if sentence_count
        else 0.0
    )
    fluency_score = float(fluency.get("fluency"))
    f1 = (
        fluency_score
        * 2
        * precision_score
        * recall_score
        / (precision_score + recall_score)
        if precision_score + recall_score > 0
        else 0.0
    )
    return {
        "fluency": fluency_score,
        "recallFound": recall_found,
        "recallTotal": len(keypoints),
        "recall": recall_score,
        "precisionFound": precision_found,
        "precisionTotal": sentence_count,
        "precision": precision_score,
        "f1": f1,
        "fluencyRaw": fluency_raw,
        "recallRaw": recall_raw,
        "precisionRaw": precision_raw,
    }
