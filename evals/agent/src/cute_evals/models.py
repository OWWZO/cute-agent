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
    final_contains_any: list[str] = field(default_factory=list)
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
    soft_limit_percent: float | None = None


@dataclass(frozen=True, slots=True)
class JudgeConfig:
    model: str | None = None
    base_url: str | None = None
    api_key: str | None = None
    timeout_seconds: int = 120

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
    compaction: dict[str, Any] | None = None
    user_id: str | None = None
    memory_seed: dict[str, Any] | None = None

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
                final_contains_any=list(expected.get("finalContainsAny") or []),
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
                soft_limit_percent=budgets.get("softLimitPercent"),
            ),
            script=list(raw.get("script") or []),
            source_path=source_path,
            judge_task=raw.get("judgeTask"),
            baseline_trace=(raw.get("replay") or {}).get("baselineTrace"),
            tools=list(raw.get("tools") or []),
            answer_contract=raw.get("answerContract"),
            metadata=dict(raw.get("metadata") or {}),
            compaction=raw.get("compaction"),
            user_id=(
                str(raw["userId"]).strip()
                if raw.get("userId") is not None and str(raw["userId"]).strip()
                else None
            ),
            memory_seed=(
                dict(raw["memorySeed"])
                if isinstance(raw.get("memorySeed"), dict)
                else None
            ),
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
    run_uid: str | None
    user_id_hash: str | None
    llm_invocations: list[dict[str, Any]]
    tool_invocations: list[dict[str, Any]]
    artifacts: list[dict[str, Any]]
    raw: dict[str, Any]
    conversation: list[dict[str, Any]] = field(default_factory=list)
    conversation_source: str | None = None
    context_events: list[dict[str, Any]] = field(default_factory=list)
    memory_events: list[dict[str, Any]] = field(default_factory=list)
    environment_events: list[dict[str, Any]] = field(default_factory=list)
    environment_source: str | None = None
    environment_snapshots: list[dict[str, Any]] = field(default_factory=list)
    snapshot_source: str | None = None

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
            run_uid=(
                str(run["runUid"])
                if run.get("runUid") is not None
                else None
            ),
            user_id_hash=(
                str(run["userIdHash"])
                if run.get("userIdHash") is not None
                else None
            ),
            llm_invocations=list(raw.get("llmInvocations") or []),
            tool_invocations=list(raw.get("toolInvocations") or []),
            artifacts=list(raw.get("artifacts") or []),
            raw=raw,
            conversation=list(raw.get("conversation") or []),
            conversation_source=(
                str(raw["conversationSource"])
                if raw.get("conversationSource") is not None
                else None
            ),
            context_events=list(raw.get("contextEvents") or []),
            memory_events=list(raw.get("memoryEvents") or []),
            environment_events=list(raw.get("environmentEvents") or []),
            environment_source=(
                str(raw["environmentSource"])
                if raw.get("environmentSource") is not None
                else None
            ),
            environment_snapshots=list(raw.get("environmentSnapshots") or []),
            snapshot_source=(
                str(raw["snapshotSource"])
                if raw.get("snapshotSource") is not None
                else None
            ),
        )

    def tool_names(self) -> list[str]:
        return [str(item.get("toolName", "")) for item in self.tool_invocations]

    def successful_tool_names(self) -> list[str]:
        from .trajectory_quality import _tool_failure_signal

        return [
            str(item.get("toolName", ""))
            for item in self.tool_invocations
            if str(item.get("status", "")).upper() == "SUCCESS"
            and isinstance(item, dict)
            and _tool_failure_signal(item) is None
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
        from .trajectory_quality import _tool_failure_signal

        if not self.tool_invocations:
            return None
        success = sum(
            1 for item in self.tool_invocations
            if str(item.get("status", "")).upper() == "SUCCESS"
            and isinstance(item, dict)
            and _tool_failure_signal(item) is None
        )
        return success / len(self.tool_invocations)

    def cache_hit_metrics(self) -> dict[str, int | float | None]:
        sampled_prompt_tokens = 0
        sampled_cached_tokens = 0
        sampled_invocations = 0
        unverified_invocations = 0
        missing_cached_tokens = 0
        null_cached_tokens = 0
        invalid_prompt_tokens = 0
        for invocation in self.llm_invocations:
            if "cachedPromptTokens" not in invocation:
                missing_cached_tokens += 1
                unverified_invocations += 1
                continue
            prompt_tokens = invocation.get("promptTokens")
            cached_tokens = invocation.get("cachedPromptTokens")
            if cached_tokens is None:
                null_cached_tokens += 1
                unverified_invocations += 1
                continue
            if (
                not isinstance(prompt_tokens, int)
                or prompt_tokens <= 0
                or not isinstance(cached_tokens, int)
            ):
                invalid_prompt_tokens += 1
                unverified_invocations += 1
                continue
            sampled_invocations += 1
            sampled_prompt_tokens += prompt_tokens
            sampled_cached_tokens += cached_tokens
        return {
            "sampledInvocations": sampled_invocations,
            "unverifiedInvocations": unverified_invocations,
            "missingCachedPromptTokens": missing_cached_tokens,
            "nullCachedPromptTokens": null_cached_tokens,
            "invalidPromptTokens": invalid_prompt_tokens,
            "promptTokens": sampled_prompt_tokens,
            "cachedPromptTokens": sampled_cached_tokens,
            "hitRatio": (
                sampled_cached_tokens / sampled_prompt_tokens
                if sampled_prompt_tokens
                else None
            ),
        }

    def cache_efficiency_metrics(self) -> dict[str, int | float | None]:
        """Return provider-backed cache metrics and explicit unverified counts."""
        sampled: list[tuple[int, int, str | None]] = []
        unverified_invocations = 0
        missing_cached_tokens = 0
        null_cached_tokens = 0
        invalid_prompt_tokens = 0
        false_ok = 0
        risk = 0
        for invocation in self.llm_invocations:
            if "cachedPromptTokens" not in invocation:
                missing_cached_tokens += 1
                unverified_invocations += 1
                continue
            prompt_tokens = invocation.get("promptTokens")
            cached_tokens = invocation.get("cachedPromptTokens")
            if cached_tokens is None:
                null_cached_tokens += 1
                unverified_invocations += 1
                continue
            if (
                not isinstance(prompt_tokens, int)
                or prompt_tokens <= 0
                or not isinstance(cached_tokens, int)
            ):
                invalid_prompt_tokens += 1
                unverified_invocations += 1
                continue
            cache_status = str(invocation.get("cacheStatus") or "").upper()
            if cache_status == "OK" and cached_tokens == 0:
                false_ok += 1
            if cache_status in {"RISK", "WARN", "INVALID"}:
                risk += 1
            sampled.append(
                (
                    prompt_tokens,
                    cached_tokens,
                    cache_status or None,
                )
            )

        eligible = sampled[1:]
        prompt_tokens = sum(item[0] for item in sampled)
        cached_tokens = sum(item[1] for item in sampled)
        eligible_prompt_tokens = sum(item[0] for item in eligible)
        eligible_cached_tokens = sum(item[1] for item in eligible)
        return {
            "sampledInvocations": len(sampled),
            "eligibleInvocations": len(eligible),
            "unverifiedInvocations": unverified_invocations,
            "missingCachedPromptTokens": missing_cached_tokens,
            "nullCachedPromptTokens": null_cached_tokens,
            "invalidPromptTokens": invalid_prompt_tokens,
            "promptTokens": prompt_tokens,
            "cachedPromptTokens": cached_tokens,
            "hitRatio": (
                cached_tokens / prompt_tokens
                if prompt_tokens
                else None
            ),
            "eligiblePromptTokens": eligible_prompt_tokens,
            "eligibleCachedPromptTokens": eligible_cached_tokens,
            "eligibleHitRatio": (
                eligible_cached_tokens / eligible_prompt_tokens
                if eligible_prompt_tokens
                else None
            ),
            "requestHitRate": (
                sum(1 for item in eligible if item[1] > 0) / len(eligible)
                if eligible
                else None
            ),
            "coldStartMisses": (
                sum(1 for item in sampled[:1] if item[1] == 0)
                if sampled
                else None
            ),
            "cacheFalseOkRate": (
                false_ok / len(sampled)
                if sampled
                else None
            ),
            "cacheRiskRate": (
                risk / len(sampled)
                if sampled
                else None
            ),
        }

    def recovered_after_tool_failure(self) -> bool:
        from .trajectory_quality import _tool_failure_signal

        failed = False
        for item in self.tool_invocations:
            if isinstance(item, dict) and _tool_failure_signal(item) is not None:
                failed = True
            elif failed and str(item.get("status", "")).upper() == "SUCCESS":
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

    @property
    def hard_gate_failed(self) -> bool:
        return not self.passed and any(
            score.passed is False for score in self.scores if score.hard_gate
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "caseId": self.case.id,
            "suite": self.case.suite,
            "metadata": self.case.metadata,
            "attempt": self.attempt,
            "passed": self.passed,
            "status": (
                "failed"
                if self.hard_gate_failed
                else "unverified"
                if self.unverified
                else ("passed" if self.passed else "failed")
            ),
            "qualityStatus": (
                "failed"
                if self.hard_gate_failed
                else "unverified"
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
