from __future__ import annotations

import asyncio
from pathlib import Path

from .java_runner import EvalRunnerError, run_java_cases
from .models import CaseResult, EvalCase, JudgeConfig
from .scorers import score_case


def run_inspect_attempt(
    cases: list[EvalCase],
    output_dir: Path,
    *,
    profile: str,
    mode: str,
    attempt: int,
    trace_prefix: str,
    require_judge: bool,
    judge: JudgeConfig | None,
    judge_cache_dir: Path | None,
    timeout_seconds: int,
    build: bool,
    tool_mode: str = "ephemeral",
) -> list[CaseResult]:
    try:
        from inspect_ai import Task
        from inspect_ai import eval as inspect_eval
        from inspect_ai.dataset import Sample
        from inspect_ai.scorer import Score, accuracy, scorer
        from inspect_ai.solver import solver
    except ImportError as exc:
        raise EvalRunnerError(
            "adapter",
            "Inspect AI is not installed; run `uv sync --extra frameworks`",
        ) from exc

    output_dir.mkdir(parents=True, exist_ok=True)
    traces = {}
    captured: list[CaseResult] = []

    @solver
    def cute_agent_solver():
        async def solve(state, generate):
            traces.update(
                await asyncio.to_thread(
                    run_java_cases,
                    cases,
                    output_dir,
                    mode=mode,
                    profile=profile,
                    build=build,
                    timeout_seconds=timeout_seconds,
                    tool_mode=tool_mode,
                )
            )
            state.metadata["cuteAgentEval"] = {
                "profile": profile,
                "mode": mode,
                "caseCount": len(cases),
            }
            return state

        return solve

    @scorer(metrics=[accuracy()])
    def cute_agent_scorer():
        async def score(state, target):
            if not traces:
                return Score(
                    value="I",
                    explanation="Java eval runner produced no traces",
                    metadata={"failureCategory": "adapter"},
                )
            results = []
            for case in cases:
                trace = traces.get(case.id)
                if trace is None:
                    raise EvalRunnerError(
                        "adapter", f"Java runner did not return trace for case {case.id}"
                    )
                results.append(
                    await asyncio.to_thread(
                        score_case,
                        case,
                        trace,
                        f"{trace_prefix}/traces/{case.id}/trace.json",
                        profile=profile,
                        require_judge=require_judge,
                        judge=judge,
                        judge_cache_dir=judge_cache_dir,
                        attempt=attempt,
                    )
                )
            captured.extend(results)
            failed = [result.case.id for result in results if not result.passed]
            unverified = [
                result.case.id for result in results if result.unverified
            ]
            passed = not failed and not unverified
            return Score(
                value="C" if passed else "I",
                explanation=(
                    f"passed={len(results) - len(set(failed + unverified))} "
                    f"failed={len(failed)} unverified={len(unverified)}"
                ),
                metadata={
                    "caseResults": [result.to_dict() for result in results],
                    "failedCases": failed,
                    "unverifiedCases": unverified,
                },
            )

        return score

    suite_id = f"cute-agent-{profile}-{cases[0].suite}"
    task = Task(
        dataset=[
            Sample(
                input=f"Run {len(cases)} Cute-Agent Bench cases with {mode} runtime",
                id=suite_id,
                metadata={"profile": profile, "mode": mode},
            )
        ],
        solver=cute_agent_solver(),
        scorer=cute_agent_scorer(),
        model="mockllm/model",
        name=suite_id,
        version=1,
        metadata={"runner": "cute-evals", "profile": profile, "mode": mode},
    )
    logs = inspect_eval(
        task,
        model="mockllm/model",
        display="none",
        log_dir=str(output_dir / "inspect-logs"),
        fail_on_error=True,
    )
    if not logs or str(logs[0].status) != "success":
        error = logs[0].error if logs else "Inspect AI returned no log"
        raise EvalRunnerError("adapter", f"Inspect AI eval failed: {error}")
    if not captured:
        raise EvalRunnerError(
            "adapter", "Inspect AI completed without returning Cute-Agent scores"
        )
    return captured
