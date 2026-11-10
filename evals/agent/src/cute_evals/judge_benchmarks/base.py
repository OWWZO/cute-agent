from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Protocol

JUDGE_SCORE_NAMESPACE = "judge-calibration"
LOCAL_RESEARCH_LICENSE_STATUS = "local-research"
RUNNABLE_LICENSE_STATUSES = frozenset({"approved", LOCAL_RESEARCH_LICENSE_STATUS})
VALID_SCORE_LABELS = frozenset(
    {
        "official-full",
        "official-test",
        "official-subset",
        "compatibility-subset",
        "internal-calibration",
        "local-research",
        "partial-not-official",
        "unverified",
        "blocked-license",
    }
)


def judge_usage_policy(license_status: str) -> dict[str, bool | str]:
    local_research = license_status == LOCAL_RESEARCH_LICENSE_STATUS
    approved = license_status == "approved"
    return {
        "mode": (
            LOCAL_RESEARCH_LICENSE_STATUS
            if local_research
            else "official"
            if approved
            else "blocked"
        ),
        "officialScore": approved,
        "publicationAllowed": approved,
        "gateEligible": approved,
    }


def judge_score_label(license_status: str, official_label: str) -> str:
    if license_status == LOCAL_RESEARCH_LICENSE_STATUS:
        return LOCAL_RESEARCH_LICENSE_STATUS
    return official_label


class JudgeOutputKind(str, Enum):
    INVALID_JSON = "invalid_json"
    MISSING_STEPS = "missing_steps"
    UNEXPECTED_STEPS = "unexpected_steps"
    INVALID_LABEL = "invalid_label"
    INVALID_EXPLANATIONS = "invalid_explanations"
    EMPTY_RESPONSE = "empty_response"


class JudgeOutputError(ValueError):
    def __init__(
        self,
        kind: JudgeOutputKind,
        message: str,
        *,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.kind = kind
        self.details = details or {}

    def to_dict(self) -> dict[str, Any]:
        return {
            "kind": self.kind.value,
            "message": str(self),
            "details": self.details,
        }


class LicenseBlockedError(RuntimeError):
    def __init__(self, benchmark: str, status: str, evidence: str | None = None) -> None:
        detail = f" ({evidence})" if evidence else ""
        super().__init__(
            f"{benchmark} license status is {status}; official run is blocked{detail}"
        )
        self.benchmark = benchmark
        self.status = status
        self.evidence = evidence


@dataclass(frozen=True, slots=True)
class JudgeBenchmarkTask:
    id: str
    category: str
    messages: list[dict[str, Any]]
    assistant_message_indices: list[int]
    tools: list[dict[str, Any]]
    raw: dict[str, Any] = field(repr=False)
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class JudgePrediction:
    task_id: str
    step_labels: dict[str, int]
    final_label: int
    explanations: dict[str, Any]
    raw: dict[str, Any] = field(repr=False)

    def to_dict(self) -> dict[str, Any]:
        return {
            "taskId": self.task_id,
            "stepLabels": self.step_labels,
            "finalLabel": self.final_label,
            "explanations": self.explanations,
        }


@dataclass(slots=True)
class JudgeBenchmarkRecord:
    task_id: str
    status: str
    prediction: JudgePrediction | None = None
    raw_response: str | None = None
    error: dict[str, Any] | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "taskId": self.task_id,
            "status": self.status,
            "prediction": (
                self.prediction.to_dict()
                if self.prediction is not None
                else None
            ),
            "rawResponse": self.raw_response,
            "error": self.error,
            "metadata": self.metadata,
        }


class JudgeModel(Protocol):
    model: str

    def complete(
        self,
        messages: list[dict[str, str]],
        *,
        timeout_seconds: int | None = None,
    ) -> str: ...


class JudgeBenchmarkAdapter(Protocol):
    name: str
    version: str
    upstream: str
    score_label: str
    score_namespace: str

    def tasks(self, split: str) -> list[JudgeBenchmarkTask]: ...

    def prompt(self, task: JudgeBenchmarkTask) -> list[dict[str, str]]: ...

    def parse(
        self,
        task: JudgeBenchmarkTask,
        response: str,
    ) -> JudgePrediction: ...

    def score(
        self,
        tasks: list[JudgeBenchmarkTask],
        records: list[JudgeBenchmarkRecord],
    ) -> dict[str, Any]: ...

    def report_metadata(self) -> dict[str, Any]: ...


def _extract_json_object(text: str) -> dict[str, Any]:
    stripped = text.strip()
    if not stripped:
        raise JudgeOutputError(
            JudgeOutputKind.EMPTY_RESPONSE,
            "judge returned an empty response",
        )
    fenced = re.findall(
        r"```(?:json)?\s*([\s\S]*?)\s*```",
        stripped,
        flags=re.IGNORECASE,
    )
    candidates = [fenced[-1]] if fenced else []
    candidates.append(stripped)
    for candidate in candidates:
        try:
            payload = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict):
            return payload

    start = stripped.find("{")
    end = stripped.rfind("}")
    if start >= 0 and end > start:
        try:
            payload = json.loads(stripped[start : end + 1])
        except json.JSONDecodeError as exc:
            raise JudgeOutputError(
                JudgeOutputKind.INVALID_JSON,
                f"judge response is not valid JSON: {exc}",
            ) from exc
        if isinstance(payload, dict):
            return payload
    raise JudgeOutputError(
        JudgeOutputKind.INVALID_JSON,
        "judge response does not contain a JSON object",
    )


def _label(value: Any, accepted: set[int]) -> int:
    if isinstance(value, bool):
        raise JudgeOutputError(
            JudgeOutputKind.INVALID_LABEL,
            "boolean is not a valid step label",
        )
    if isinstance(value, int):
        parsed = value
    elif (
        isinstance(value, str)
        and value.strip().lstrip("-+").isdigit()
    ):
        parsed = int(value.strip())
    else:
        raise JudgeOutputError(
            JudgeOutputKind.INVALID_LABEL,
            f"invalid label: {value!r}",
        )
    if parsed not in accepted:
        raise JudgeOutputError(
            JudgeOutputKind.INVALID_LABEL,
            f"label {parsed} is not in {sorted(accepted)}",
        )
    return parsed


def _normalize_step_labels(
    value: Any,
    expected_indices: list[int],
) -> dict[str, int]:
    if not isinstance(value, dict):
        raise JudgeOutputError(
            JudgeOutputKind.INVALID_LABEL,
            "step_labels must be an object",
        )
    normalized: dict[str, int] = {}
    for key, label in value.items():
        key_text = str(key)
        if not key_text.lstrip("-").isdigit():
            raise JudgeOutputError(
                JudgeOutputKind.INVALID_LABEL,
                f"step label key must be an integer index: {key!r}",
            )
        normalized[str(int(key_text))] = _label(label, {-1, 0, 1})
    expected = {str(index) for index in expected_indices}
    missing = sorted(expected - set(normalized), key=int)
    unexpected = sorted(set(normalized) - expected, key=int)
    if missing:
        raise JudgeOutputError(
            JudgeOutputKind.MISSING_STEPS,
            f"judge omitted steps: {', '.join(missing)}",
            details={"missing": missing},
        )
    if unexpected:
        raise JudgeOutputError(
            JudgeOutputKind.UNEXPECTED_STEPS,
            f"judge returned unexpected steps: {', '.join(unexpected)}",
            details={"unexpected": unexpected},
        )
    return normalized


def _normalize_explanations(
    value: Any,
    expected_indices: list[int],
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise JudgeOutputError(
            JudgeOutputKind.INVALID_EXPLANATIONS,
            "explanations must be an object",
        )
    steps = value.get("steps")
    if not isinstance(steps, dict):
        raise JudgeOutputError(
            JudgeOutputKind.INVALID_EXPLANATIONS,
            "explanations.steps must be an object",
        )
    expected = {str(index) for index in expected_indices}
    missing = sorted(expected - set(steps), key=int)
    if missing:
        raise JudgeOutputError(
            JudgeOutputKind.INVALID_EXPLANATIONS,
            f"judge omitted step explanations: {', '.join(missing)}",
            details={"missing": missing},
        )
    return value


def parse_judge_prediction(
    task: JudgeBenchmarkTask,
    response: str | dict[str, Any],
    *,
    final_labels: set[int] | None = None,
    require_explanations: bool = True,
) -> JudgePrediction:
    payload = (
        response
        if isinstance(response, dict)
        else _extract_json_object(response)
    )
    accepted_final = final_labels or {-1, 1}
    step_labels = _normalize_step_labels(
        payload.get("step_labels"),
        task.assistant_message_indices,
    )
    final_label = _label(payload.get("final_label"), accepted_final)
    explanations = payload.get("explanations")
    if require_explanations:
        explanations = _normalize_explanations(
            explanations,
            task.assistant_message_indices,
        )
    elif not isinstance(explanations, dict):
        explanations = {}
    return JudgePrediction(
        task_id=task.id,
        step_labels=step_labels,
        final_label=final_label,
        explanations=explanations,
        raw=payload,
    )
