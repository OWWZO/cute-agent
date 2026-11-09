"""Official MemoryAgentBench text metrics.

The normalization, exact-match, and substring-match functions are copied from
`utils/eval_other_utils.py` at the pinned upstream revision in manifest.yaml.
"""

from __future__ import annotations

import re
import string
from typing import Iterable


def normalize_answer(answer_text: str) -> str:
    text = answer_text.lower()
    text = "".join(char for char in text if char not in string.punctuation)
    text = re.sub(r"\b(a|an|the)\b", " ", text)
    return " ".join(text.split())


def exact_match(prediction: str, ground_truth: str) -> bool:
    return normalize_answer(prediction) == normalize_answer(ground_truth)


def substring_exact_match(prediction: str, ground_truth: str) -> bool:
    return normalize_answer(ground_truth) in normalize_answer(prediction)


def max_over_ground_truths(
    metric,
    prediction: str,
    ground_truths: str | Iterable[str] | Iterable[Iterable[str]],
) -> bool:
    if isinstance(ground_truths, str):
        answers = [ground_truths]
    else:
        answers = list(ground_truths)
    if answers and isinstance(answers[0], (list, tuple)):
        answers = [
            answer
            for group in answers
            for answer in group
        ]
    if not answers:
        return False
    return max(bool(metric(prediction, str(answer))) for answer in answers)


def score_answer(
    metric_name: str,
    prediction: str,
    ground_truths: str | Iterable[str] | Iterable[Iterable[str]],
) -> bool:
    if metric_name == "exact_match":
        metric = exact_match
    elif metric_name == "substring_exact_match":
        metric = substring_exact_match
    else:
        raise ValueError(f"unsupported MemoryAgentBench metric: {metric_name}")
    return max_over_ground_truths(metric, prediction, ground_truths)


def eventqa_recall(prediction: str, answers: Iterable[str]) -> bool:
    values = [str(answer) for answer in answers]
    if not values:
        return False
    matched = sum(
        value.lower() in prediction.lower()
        for value in values
    )
    return matched == len(values)


def ruler_recall(prediction: str, answers: Iterable[str]) -> float:
    values = [str(answer) for answer in answers]
    if not values:
        return 0.0
    return sum(
        value.lower() in prediction.lower()
        for value in values
    ) / len(values)
