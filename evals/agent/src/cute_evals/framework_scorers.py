from __future__ import annotations

import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Any

from .models import EvalCase, JudgeConfig, RunTrace, ScoreRecord

TRAJECTORY_JSON_INSTRUCTION = (
    "\n\nReturn only one JSON object, no markdown, with a boolean field named "
    "score and a string field named reasoning."
)


def _score(
    case: EvalCase,
    *,
    scorer: str,
    name: str,
    value: float | bool | str | None,
    unit: str,
    passed: bool | None,
    hard_gate: bool,
    status: str,
    evidence: dict[str, Any] | None = None,
) -> ScoreRecord:
    return ScoreRecord(
        case_id=case.id,
        scorer=scorer,
        name=name,
        value=value,
        unit=unit,
        passed=passed,
        hard_gate=hard_gate,
        status=status,
        evidence=evidence or {},
    )


def _trajectory(case: EvalCase, trace: RunTrace, *, actual: bool) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = [{"role": "user", "content": case.query}]
    if actual:
        invocations = sorted(
            trace.tool_invocations,
            key=lambda item: (
                int(item.get("stepNo") or 0),
                int(item.get("dispatchIndex") or 0),
            ),
        )
        for item in invocations:
            messages.append(
                {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                        {
                            "function": {
                                "name": str(item.get("toolName", "")),
                                "arguments": str(item.get("inputJson") or "{}"),
                            }
                        }
                    ],
                }
            )
            messages.append(
                {
                    "role": "tool",
                    "content": str(
                        (
                            item.get("errorMsg")
                            if str(item.get("status", "")).upper() in {"FAILED", "TIMEOUT"}
                            and item.get("errorMsg")
                            else item.get("toolResult")
                        )
                        or item.get("errorMsg")
                        or ""
                    ),
                }
            )
        messages.append({"role": "assistant", "content": trace.final_text})
        return messages

    final_action = next(
        (action for action in reversed(case.script) if action.get("type") == "final"),
        {},
    )
    for action in case.script:
        if action.get("type") != "tool":
            continue
        messages.append(
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {
                        "function": {
                            "name": str(action.get("tool", "")),
                            "arguments": json.dumps(
                                action.get("arguments") or {},
                                ensure_ascii=False,
                                separators=(",", ":"),
                            ),
                        }
                    }
                ],
            }
        )
        messages.append(
            {
                "role": "tool",
                "content": str(action.get("result") or action.get("fault") or ""),
            }
        )
    messages.append(
        {"role": "assistant", "content": str(final_action.get("content") or "")}
    )
    return messages


def score_agentevals(
    case: EvalCase,
    trace: RunTrace,
    *,
    judge: JudgeConfig | None,
) -> list[ScoreRecord]:
    scores: list[ScoreRecord] = []
    outputs = _trajectory(case, trace, actual=True)
    reference_outputs = _trajectory(case, trace, actual=False)

    try:
        from agentevals.trajectory.match import create_trajectory_match_evaluator

        evaluator = create_trajectory_match_evaluator(
            trajectory_match_mode="strict",
            tool_args_match_mode="superset",
        )
        result = evaluator(outputs=outputs, reference_outputs=reference_outputs)
        passed = bool(result.get("score")) if isinstance(result, dict) else bool(result)
        reasoning = result.get("reasoning") if isinstance(result, dict) else None
        scores.append(
            _score(
                case,
                scorer="agentevals",
                name="trajectory_match",
                value=passed,
                unit="boolean",
                passed=passed,
                hard_gate=case.hard_gate and case.expected.strict_tool_sequence,
                status="verified",
                evidence={
                    "reasoning": reasoning,
                    "trajectoryMatchMode": "strict",
                    "toolArgsMatchMode": "superset",
                },
            )
        )
    except Exception as exc:  # noqa: BLE001 - third-party judge failures are unverified.
        scores.append(
            _score(
                case,
                scorer="agentevals",
                name="trajectory_match",
                value=None,
                unit="boolean",
                passed=None,
                hard_gate=case.hard_gate and case.expected.strict_tool_sequence,
                status="unverified",
                evidence={"reason": f"{type(exc).__name__}: {exc}"},
            )
        )

    if not judge or not judge.configured:
        scores.append(
            _score(
                case,
                scorer="agentevals",
                name="trajectory_judge",
                value=None,
                unit="score",
                passed=None,
                hard_gate=False,
                status="unverified",
                evidence={
                    "reason": "CUTE_EVAL_JUDGE_MODEL / --judge-model is not configured"
                },
            )
        )
        return scores

    try:
        from agentevals.trajectory.llm import (
            TRAJECTORY_ACCURACY_PROMPT_WITH_REFERENCE,
            create_trajectory_llm_as_judge,
        )

        if judge.openai_compatible:
            from openai import OpenAI

            evaluator = create_trajectory_llm_as_judge(
                prompt=(
                    TRAJECTORY_ACCURACY_PROMPT_WITH_REFERENCE
                    + TRAJECTORY_JSON_INSTRUCTION
                ),
                model=judge.model,
                judge=OpenAI(
                    api_key=judge.api_key,
                    base_url=judge.base_url,
                ),
            )
        else:
            evaluator = create_trajectory_llm_as_judge(model=judge.model)
        result = evaluator(outputs=outputs, reference_outputs=reference_outputs)
        passed = bool(result.get("score")) if isinstance(result, dict) else bool(result)
        scores.append(
            _score(
                case,
                scorer="agentevals",
                name="trajectory_judge",
                value=passed,
                unit="score",
                passed=passed,
                hard_gate=False,
                status="verified",
                evidence={
                    "judgeModel": judge.model,
                    "judgeBaseUrl": judge.base_url,
                    "reasoning": (
                        result.get("reasoning") or result.get("comment")
                        if isinstance(result, dict)
                        else None
                    ),
                },
            )
        )
    except Exception as exc:  # noqa: BLE001 - third-party judge failures are unverified.
        scores.append(
            _score(
                case,
                scorer="agentevals",
                name="trajectory_judge",
                value=None,
                unit="score",
                passed=None,
                hard_gate=False,
                status="unverified",
                evidence={
                    "judgeModel": judge.model,
                    "judgeBaseUrl": judge.base_url,
                    "reason": f"{type(exc).__name__}: {exc}",
                },
            )
        )
    return scores


def _judge_cache_path(
    cache_dir: Path | None,
    *,
    case: EvalCase,
    trace: RunTrace,
    judge: JudgeConfig,
) -> Path | None:
    if cache_dir is None:
        return None
    payload = {
        "caseId": case.id,
        "caseVersion": case.version,
        "query": case.query,
        "finalText": trace.final_text,
        "expected": {
            "finalContains": case.expected.final_contains,
            "finalNotContains": case.expected.final_not_contains,
        },
        "judgeModel": judge.model,
        "judgeBaseUrl": judge.base_url,
        "judgeTask": case.judge_task,
        "metric": "geval-final-answer-v2",
        "toolCalls": [
            {
                "toolName": item.get("toolName"),
                "inputJson": item.get("inputJson"),
                "status": item.get("status"),
                "toolResult": item.get("toolResult"),
                "errorMsg": item.get("errorMsg"),
            }
            for item in trace.tool_invocations
        ],
    }
    digest = hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ).hexdigest()
    return cache_dir / "deepeval" / f"{digest}.json"


def score_deepeval(
    case: EvalCase,
    trace: RunTrace,
    *,
    judge: JudgeConfig | None,
    cache_dir: Path | None,
    require_judge: bool,
) -> ScoreRecord:
    hard_gate = require_judge and case.hard_gate
    if not judge or not judge.configured:
        return _score(
            case,
            scorer="deepeval",
            name="task_completion",
            value=None,
            unit="score",
            passed=None,
            hard_gate=hard_gate,
            status="unverified",
            evidence={
                "reason": "CUTE_EVAL_JUDGE_MODEL / --judge-model is not configured"
            },
        )

    cache_path = _judge_cache_path(
        cache_dir, case=case, trace=trace, judge=judge
    )
    if cache_path and cache_path.exists():
        try:
            cached = json.loads(cache_path.read_text(encoding="utf-8"))
            return _score(
                case,
                scorer="deepeval",
                name="task_completion",
                value=cached["value"],
                unit="score",
                passed=cached["passed"],
                hard_gate=hard_gate,
                status="verified",
                evidence={**cached.get("evidence", {}), "cacheHit": True},
            )
        except (OSError, json.JSONDecodeError, KeyError, TypeError):
            pass

    try:
        from deepeval.metrics import GEval
        from deepeval.test_case import LLMTestCase, SingleTurnParams

        if judge.openai_compatible:
            from deepeval.models.llms.openai_model import GPTModel

            metric_model = GPTModel(
                model=judge.model,
                api_key=judge.api_key,
                base_url=judge.base_url,
                temperature=0.0,
                cost_per_input_token=0.0,
                cost_per_output_token=0.0,
            )
        else:
            metric_model = judge.model
        criteria = (
            "Evaluate only the final answer against the user's request. "
            "Do not require tool names, tool order, or hidden process details; "
            "those are verified separately by deterministic and trajectory scorers."
        )
        if case.expected.final_contains:
            criteria += (
                "; the actual output must contain all expected values: "
                + ", ".join(case.expected.final_contains)
            )
        if case.expected.final_not_contains:
            criteria += (
                "; the actual output must not contain: "
                + ", ".join(case.expected.final_not_contains)
            )
        metric = GEval(
            name="Task Completion",
            evaluation_params=[
                SingleTurnParams.INPUT,
                SingleTurnParams.ACTUAL_OUTPUT,
                SingleTurnParams.EXPECTED_OUTPUT,
            ],
            criteria=criteria,
            threshold=0.8,
            model=metric_model,
            async_mode=False,
        )
        expected_output = "\n".join(case.expected.final_contains)
        metric.measure(
            LLMTestCase(
                input=case.query,
                actual_output=trace.final_text,
                expected_output=expected_output,
            )
        )
        value = float(metric.score)
        passed = bool(metric.success)
        evidence = {
            "judgeModel": judge.model,
            "judgeBaseUrl": judge.base_url,
            "judgeTask": case.judge_task,
            "criteria": criteria,
            "threshold": metric.threshold,
            "reason": getattr(metric, "reason", None),
        }
        if cache_path:
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=cache_path.parent,
                prefix=f".{cache_path.name}.",
                suffix=".tmp",
                delete=False,
            ) as handle:
                temporary = Path(handle.name)
                try:
                    json.dump(
                        {
                            "value": value,
                            "passed": passed,
                            "evidence": evidence,
                        },
                        handle,
                        ensure_ascii=False,
                        indent=2,
                    )
                    handle.write("\n")
                except BaseException:
                    handle.close()
                    temporary.unlink(missing_ok=True)
                    raise
            os.replace(temporary, cache_path)
        return _score(
            case,
            scorer="deepeval",
            name="task_completion",
            value=value,
            unit="score",
            passed=passed,
            hard_gate=hard_gate,
            status="verified",
            evidence=evidence,
        )
    except Exception as exc:  # noqa: BLE001 - DeepEval failures are unverified.
        return _score(
            case,
            scorer="deepeval",
            name="task_completion",
            value=None,
            unit="score",
            passed=None,
            hard_gate=hard_gate,
            status="unverified",
            evidence={
                "judgeModel": judge.model,
                "judgeBaseUrl": judge.base_url,
                "reason": f"{type(exc).__name__}: {exc}",
            },
        )


def score_ragas(
    case: EvalCase,
    trace: RunTrace,
    *,
    judge: JudgeConfig | None,
    require_judge: bool,
) -> list[ScoreRecord]:
    if case.suite not in {"rag-core", "rag-python"}:
        return []

    contexts: list[str] = []
    for item in trace.tool_invocations:
        if item.get("toolName") != "mrag_query":
            continue
        retrieved_contexts = item.get("retrievedContexts")
        if isinstance(retrieved_contexts, list) and retrieved_contexts:
            contexts.extend(
                str(context)
                for context in retrieved_contexts
                if str(context).strip()
            )
            continue
        context = str(item.get("toolResult") or item.get("llmObservation") or "")
        if context:
            contexts.append(context)
    contexts = [context for context in contexts if context]
    required = case.expected.required_citations
    retrieved = "\n".join(contexts)
    missing = [citation for citation in required if citation not in retrieved]
    if required:
        scores = [
            _score(
                case,
                scorer="mrag",
                name="citation_recall",
                value=(len(required) - len(missing)) / len(required),
                unit="ratio",
                passed=not missing,
                hard_gate=case.hard_gate,
                status="verified",
                evidence={"requiredCitations": required, "missingCitations": missing},
            )
        ]
    else:
        scores = [
            _score(
                case,
                scorer="mrag",
                name="citation_recall",
                value=None,
                unit="ratio",
                passed=None,
                hard_gate=False,
                status="verified",
                evidence={
                    "requiredCitations": [],
                    "reason": (
                        "case declares no evidence"
                        if case.expected.no_evidence
                        else "no required citations"
                    ),
                },
            )
        ]

    hard_gate = require_judge and case.hard_gate
    if not judge or not judge.configured:
        scores.append(
            _score(
                case,
                scorer="ragas",
                name="faithfulness",
                value=None,
                unit="score",
                passed=None,
                hard_gate=hard_gate,
                status="unverified",
                evidence={
                    "reason": "CUTE_EVAL_JUDGE_MODEL / --judge-model is not configured"
                },
            )
        )
        return scores
    if not contexts:
        scores.append(
            _score(
                case,
                scorer="ragas",
                name="faithfulness",
                value=None,
                unit="score",
                passed=None,
                hard_gate=hard_gate,
                status="unverified",
                evidence={"reason": "mrag_query returned no retrieved context"},
            )
        )
        return scores

    try:
        from ragas.llms import llm_factory
        from ragas.metrics.collections import Faithfulness

        if judge.openai_compatible:
            from openai import AsyncOpenAI

            client = AsyncOpenAI(
                api_key=judge.api_key,
                base_url=judge.base_url,
            )
        else:
            from openai import AsyncOpenAI

            client = AsyncOpenAI()
        metric = Faithfulness(
            llm=llm_factory(judge.model, client=client, provider="openai")
        )
        result = metric.score(
            user_input=case.query,
            response=trace.final_text,
            retrieved_contexts=contexts,
        )
        value = float(result.value)
        scores.append(
            _score(
                case,
                scorer="ragas",
                name="faithfulness",
                value=value,
                unit="score",
                passed=value >= 0.7,
                hard_gate=hard_gate,
                status="verified",
                evidence={
                    "judgeModel": judge.model,
                    "judgeBaseUrl": judge.base_url,
                    "reason": result.reason,
                },
            )
        )
    except Exception as exc:  # noqa: BLE001 - Ragas failures are unverified.
        scores.append(
            _score(
                case,
                scorer="ragas",
                name="faithfulness",
                value=None,
                unit="score",
                passed=None,
                hard_gate=hard_gate,
                status="unverified",
                evidence={
                    "judgeModel": judge.model,
                    "judgeBaseUrl": judge.base_url,
                    "reason": f"{type(exc).__name__}: {exc}",
                },
            )
        )
    return scores
