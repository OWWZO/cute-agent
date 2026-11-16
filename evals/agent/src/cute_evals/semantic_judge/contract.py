from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any


def extract_json_object(text: str) -> dict[str, Any]:
    stripped = text.strip()
    if not stripped:
        raise ValueError("semantic judge returned an empty response")
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
        payload = json.loads(stripped[start : end + 1])
        if isinstance(payload, dict):
            return payload
    raise ValueError("semantic judge response does not contain a JSON object")


@dataclass(slots=True)
class JudgePacket:
    schema_version: str
    case_id: str
    agent: str
    attempt: int
    query: str
    expected_contract: str | None
    risk: str | None
    final_answer: str
    deterministic_scores: list[dict[str, Any]]
    conversation: list[dict[str, Any]]
    tool_calls: list[dict[str, Any]]
    workspace_files: list[dict[str, Any]]
    metadata: dict[str, Any] = field(default_factory=dict)
    prompt_injection_suspected: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "case": {
                "id": self.case_id,
                "query": self.query,
                "expectedContract": self.expected_contract,
                "risk": self.risk,
            },
            "metadata": {
                "agent": self.agent,
                "attempt": self.attempt,
                **self.metadata,
            },
            "deterministicScores": self.deterministic_scores,
            "finalAnswer": self.final_answer,
            "conversation": self.conversation,
            "toolCalls": self.tool_calls,
            "workspaceFiles": self.workspace_files,
            "promptInjectionSuspected": self.prompt_injection_suspected,
        }

    def canonical_json(self) -> str:
        return json.dumps(
            self.to_dict(),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )


@dataclass(slots=True)
class EvidenceReference:
    kind: str
    evidence_id: str | None = None
    path: str | None = None
    quote: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "kind": self.kind,
            "id": self.evidence_id,
            "path": self.path,
            "quote": self.quote,
        }


@dataclass(slots=True)
class DimensionVerdict:
    id: str
    score: float
    weight: float
    passed: bool
    reason: str
    evidence: list[EvidenceReference] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "score": self.score,
            "weight": self.weight,
            "passed": self.passed,
            "reason": self.reason,
            "evidence": [item.to_dict() for item in self.evidence],
        }


@dataclass(slots=True)
class AssertionVerdict:
    assertion: str
    met: bool
    reason: str
    evidence: list[EvidenceReference] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "assertion": self.assertion,
            "met": self.met,
            "reason": self.reason,
            "evidence": [item.to_dict() for item in self.evidence],
        }


@dataclass(slots=True)
class JudgeVerdict:
    schema_version: str
    rubric_id: str
    rubric_hash: str
    case_id: str
    agent: str
    attempt: int
    status: str
    verdict: str
    confidence: float
    summary: str
    dimensions: list[DimensionVerdict] = field(default_factory=list)
    assertions: list[AssertionVerdict] = field(default_factory=list)
    limitations: list[str] = field(default_factory=list)
    weighted_score: float | None = None
    semantic_pass: bool | None = None
    judge_model: str | None = None
    prompt_hash: str | None = None
    raw_response: str | None = None
    error: dict[str, Any] | None = None
    prompt_injection_suspected: bool = False

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> JudgeVerdict:
        judge = payload.get("judge") or {}
        return cls(
            schema_version=str(payload["schemaVersion"]),
            rubric_id=str(payload["rubricId"]),
            rubric_hash=str(payload["rubricHash"]),
            case_id=str(payload["caseId"]),
            agent=str(payload["agent"]),
            attempt=int(payload["attempt"]),
            status=str(payload["status"]),
            verdict=str(payload["verdict"]),
            confidence=float(payload["confidence"]),
            summary=str(payload["summary"]),
            dimensions=[
                DimensionVerdict(
                    id=str(item["id"]),
                    score=float(item["score"]),
                    weight=float(item["weight"]),
                    passed=bool(item["passed"]),
                    reason=str(item["reason"]),
                    evidence=[
                        EvidenceReference(
                            kind=str(evidence["kind"]),
                            evidence_id=evidence.get("id"),
                            path=evidence.get("path"),
                            quote=str(evidence["quote"]),
                        )
                        for evidence in item.get("evidence") or []
                    ],
                )
                for item in payload.get("dimensions") or []
            ],
            assertions=[
                AssertionVerdict(
                    assertion=str(item["assertion"]),
                    met=bool(item["met"]),
                    reason=str(item["reason"]),
                    evidence=[
                        EvidenceReference(
                            kind=str(evidence["kind"]),
                            evidence_id=evidence.get("id"),
                            path=evidence.get("path"),
                            quote=str(evidence["quote"]),
                        )
                        for evidence in item.get("evidence") or []
                    ],
                )
                for item in payload.get("assertions") or []
            ],
            limitations=[str(item) for item in payload.get("limitations") or []],
            weighted_score=(
                float(payload["weightedScore"])
                if payload.get("weightedScore") is not None
                else None
            ),
            semantic_pass=payload.get("semanticPass"),
            judge_model=judge.get("model"),
            prompt_hash=judge.get("promptHash"),
            raw_response=payload.get("rawResponse"),
            error=payload.get("error"),
            prompt_injection_suspected=bool(
                payload.get("promptInjectionSuspected", False)
            ),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "rubricId": self.rubric_id,
            "rubricHash": self.rubric_hash,
            "caseId": self.case_id,
            "agent": self.agent,
            "attempt": self.attempt,
            "status": self.status,
            "verdict": self.verdict,
            "confidence": self.confidence,
            "summary": self.summary,
            "weightedScore": self.weighted_score,
            "semanticPass": self.semantic_pass,
            "dimensions": [item.to_dict() for item in self.dimensions],
            "assertions": [item.to_dict() for item in self.assertions],
            "limitations": self.limitations,
            "judge": {
                "model": self.judge_model,
                "promptHash": self.prompt_hash,
            },
            "rawResponse": self.raw_response,
            "error": self.error,
            "promptInjectionSuspected": self.prompt_injection_suspected,
        }
