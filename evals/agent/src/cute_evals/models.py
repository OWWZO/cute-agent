from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any


SLO_SCORE_NAMES = frozenset({"max_steps", "token_budget", "wall_time"})


def parse_time(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(value)


@dataclass(slots=True)
class ExpectedSpec:
    final_contains: list[str] = field(default_factory=list)
    final_not_contains: list[str] = field(default_factory=list)
    max_steps: int | None = None
    required_tools: list[str] = field(default_factory=list)
    required_tool_sequence: list[str] = field(default_factory=list)
    strict_tool_sequence: bool = False
    reference_tool_sequence: list[str] = field(default_factory=list)
    subgoals: list[dict[str, Any]] = field(default_factory=list)
    forbidden_tools: list[str] = field(default_factory=list)
    require_tool_recovery: bool = False
    min_tool_success_rate: float | None = None
    required_skill: str | None = None
    forbidden_skills: list[str] = field(default_factory=list)
    max_skill_loads: int | None = None
    required_citations: list[str] = field(default_factory=list)
    no_evidence: bool = False
    min_artifacts: int = 0
    sql_oracle: dict[str, Any] | None = None


@dataclass(slots=True)
class BudgetSpec:
    max_total_tokens: int | None = None
    max_wall_ms: int | None = None
    max_tool_calls: int | None = None


@dataclass(frozen=True, slots=True)
class JudgeConfig:
    model: str | None = None
    base_url: str | None = None
    api_key: str | None = None

    @property
    def configured(self) -> bool:
        return bool(self.model)

    @property
    def openai_compatible(self) -> bool:
        return bool(self.base_url and self.api_key)


@dataclass(slots=True)
class EvalCase:
    id: str
    suite: str
    version: int
    hard_gate: bool
    query: str
    expected: ExpectedSpec
    budgets: BudgetSpec
    script: list[dict[str, Any]]
    source_path: str
    judge_task: str | None = None
    baseline_trace: str | None = None
    tools: list[dict[str, Any]] = field(default_factory=list)
    answer_contract: dict[str, Any] | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(
        cls,
        raw: dict[str, Any],
        source_path: str,
        *,
        require_script: bool = True,
    ) -> EvalCase:
        input_spec = raw.get("input") or {}
        expected = raw.get("expected") or {}
        budgets = raw.get("budgets") or {}
        case = cls(
            id=str(raw["id"]),
            suite=str(raw.get("suite", "react-core")),
            version=int(raw.get("version", 1)),
            hard_gate=bool(raw.get("hardGate", False)),
            query=str(input_spec.get("message", "")),
            expected=ExpectedSpec(
                final_contains=list(expected.get("finalContains") or []),
                final_not_contains=list(expected.get("finalNotContains") or []),
                max_steps=expected.get("maxSteps"),
                required_tools=list(expected.get("requiredTools") or []),
                required_tool_sequence=list(expected.get("requiredToolSequence") or []),
                strict_tool_sequence=bool(expected.get("strictToolSequence", False)),
                reference_tool_sequence=list(
                    expected.get("referenceToolSequence") or []
                ),
                subgoals=list(expected.get("subgoals") or []),
                forbidden_tools=list(expected.get("forbiddenTools") or []),
                require_tool_recovery=bool(expected.get("requireToolRecovery", False)),
                min_tool_success_rate=expected.get("minToolSuccessRate"),
                required_skill=(
                    str(expected["requiredSkill"])
                    if expected.get("requiredSkill") is not None
                    else None
                ),
                forbidden_skills=list(expected.get("forbiddenSkills") or []),
                max_skill_loads=expected.get("maxSkillLoads"),
                required_citations=list(expected.get("requiredCitations") or []),
                no_evidence=bool(expected.get("noEvidence", False)),
                min_artifacts=int(expected.get("minArtifacts", 0)),
                sql_oracle=expected.get("sqlOracle"),
            ),
            budgets=BudgetSpec(
                max_total_tokens=budgets.get("maxTotalTokens"),
                max_wall_ms=budgets.get("maxWallMs"),
                max_tool_calls=budgets.get("maxToolCalls"),
            ),
            script=list(raw.get("script") or []),
            source_path=source_path,
            judge_task=raw.get("judgeTask"),
            baseline_trace=(raw.get("replay") or {}).get("baselineTrace"),
            tools=list(raw.get("tools") or []),
            answer_contract=raw.get("answerContract"),
            metadata=dict(raw.get("metadata") or {}),
        )
        if not case.id or not case.query or (require_script and not case.script):
            raise ValueError(f"invalid eval case: {source_path}")
        return case


@dataclass(slots=True)
class RunTrace:
    case_id: str
    request_id: str
    status: str
    final_text: str
    total_tokens: int | None
    duration_ms: int | None
    llm_invocations: list[dict[str, Any]]
    tool_invocations: list[dict[str, Any]]
    artifacts: list[dict[str, Any]]
    raw: dict[str, Any]
    conversation: list[dict[str, Any]] = field(default_factory=list)
    environment_events: list[dict[str, Any]] = field(default_factory=list)
    environment_snapshots: list[dict[str, Any]] = field(default_factory=list)

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> RunTrace:
        run = raw.get("run") or {}
        return cls(
            case_id=str(run.get("caseId", "")),
            request_id=str(run.get("requestId", "")),
            status=str(run.get("status", "UNKNOWN")),
            final_text=str(run.get("finalSummaryText") or ""),
            total_tokens=run.get("totalTokens"),
            duration_ms=run.get("durationMs"),
            llm_invocations=list(raw.get("llmInvocations") or []),
            tool_invocations=list(raw.get("toolInvocations") or []),
            artifacts=list(raw.get("artifacts") or []),
            raw=raw,
            conversation=list(raw.get("conversation") or []),
            environment_events=list(raw.get("environmentEvents") or []),
            environment_snapshots=list(raw.get("environmentSnapshots") or []),
        )

    def tool_names(self) -> list[str]:
        return [str(item.get("toolName", "")) for item in self.tool_invocations]

    def successful_tool_names(self) -> list[str]:
        return [
            str(item.get("toolName", ""))
            for item in self.tool_invocations
            if str(item.get("status", "")).upper() == "SUCCESS"
        ]

    def first_token_samples(self) -> list[float]:
        samples: list[float] = []
        for invocation in self.llm_invocations:
            started = parse_time(invocation.get("startedAt"))
            first = parse_time(invocation.get("firstTokenAt"))
            if started and first:
                samples.append(max(0.0, (first - started).total_seconds() * 1000))
        return samples

    def first_token_ms(self) -> float | None:
        samples = self.first_token_samples()
        if not samples:
            return None
        return sum(samples) / len(samples)

    def tool_success_rate(self) -> float | None:
        if not self.tool_invocations:
            return None
        success = sum(
            1 for item in self.tool_invocations
            if str(item.get("status", "")).upper() == "SUCCESS"
        )
        return success / len(self.tool_invocations)

    def recovered_after_tool_failure(self) -> bool:
        failed = False
        for item in self.tool_invocations:
            status = str(item.get("status", "")).upper()
            if status in {"FAILED", "TIMEOUT"}:
                failed = True
            elif failed and status == "SUCCESS":
                return True
        return False

    def repeated_tool_calls(self) -> int:
        seen: dict[tuple[str, str], int] = {}
        repeated = 0
        for item in self.tool_invocations:
            key = (
                str(item.get("toolName", "")),
                str(item.get("inputJson", "")),
            )
            count = seen.get(key, 0) + 1
            seen[key] = count
            if count > 1:
                repeated += 1
        return repeated

    def max_step_no(self) -> int:
        steps = [
            int(item["stepNo"])
            for item in self.llm_invocations
            if item.get("stepNo") is not None
        ]
        return max(steps, default=0)

    def turn_count(self) -> int:
        return sum(
            1
            for item in self.conversation
            if item.get("role") == "assistant"
        )


@dataclass(slots=True)
class ScoreRecord:
    case_id: str
    scorer: str
    name: str
    value: float | bool | str | None
    unit: str
    passed: bool | None
    hard_gate: bool
    status: str
    evidence: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "caseId": self.case_id,
            "scorer": self.scorer,
            "name": self.name,
            "value": self.value,
            "unit": self.unit,
            "pass": self.passed,
            "hardGate": self.hard_gate,
            "status": self.status,
            "evidence": self.evidence,
        }


@dataclass(slots=True)
class CaseResult:
    case: EvalCase
    trace: RunTrace
    scores: list[ScoreRecord]
    trace_path: str
    attempt: int = 1

    @property
    def budget_exceeded(self) -> bool:
        error_code = str(
            (self.trace.raw.get("run") or {}).get("errorCode") or ""
        )
        return error_code == "EVAL_BUDGET_EXCEEDED"

    @property
    def quality_passed(self) -> bool:
        return all(
            score.passed is not False
            for score in self.scores
            if score.hard_gate and score.name not in SLO_SCORE_NAMES
        )

    @property
    def slo_passed(self) -> bool:
        return (
            not self.budget_exceeded
            and str(self.trace.status).upper() == "SUCCESS"
            and all(
                score.passed is not False
                for score in self.scores
                if score.name in SLO_SCORE_NAMES
            )
        )

    @property
    def passed(self) -> bool:
        return self.quality_passed and self.slo_passed

    @property
    def unverified(self) -> bool:
        return any(
            score.status == "unverified"
            for score in self.scores
            if score.hard_gate
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "caseId": self.case.id,
            "suite": self.case.suite,
            "metadata": self.case.metadata,
            "attempt": self.attempt,
            "status": "unverified" if self.unverified else ("passed" if self.passed else "failed"),
            "qualityStatus": (
                "unverified"
                if self.unverified
                else ("passed" if self.quality_passed else "failed")
            ),
            "sloStatus": "passed" if self.slo_passed else "failed",
            "tracePath": self.trace_path,
            "runStatus": self.trace.status,
            "finalText": self.trace.final_text,
            "totalTokens": self.trace.total_tokens,
            "durationMs": self.trace.duration_ms,
            "firstTokenMs": self.trace.first_token_ms(),
            "scores": [score.to_dict() for score in self.scores],
        }
