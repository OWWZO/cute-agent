from __future__ import annotations

from pathlib import Path
from typing import Any

from ..models import CaseResult


def run_compare_inspect(
    *,
    agent: str,
    suite: str,
    case_queries: dict[str, str],
    results: list[CaseResult],
    output_dir: Path,
    mode: str,
) -> Path:
    from inspect_ai import Task
    from inspect_ai import eval as inspect_eval
    from inspect_ai.dataset import Sample
    from inspect_ai.scorer import Score, accuracy, scorer
    from inspect_ai.solver import solver

    grouped: dict[str, list[CaseResult]] = {}
    for result in results:
        grouped.setdefault(result.case.id, []).append(result)
    samples = []
    for case_id, query in case_queries.items():
        attempts = sorted(
            grouped.get(case_id, []),
            key=lambda item: item.attempt,
        )
        samples.append(
            Sample(
                input=query,
                id=case_id,
                metadata={
                    "agent": agent,
                    "caseId": case_id,
                    "mode": mode,
                    "attempts": [item.to_dict() for item in attempts],
                },
            )
        )

    @solver
    def materialized_solver():
        async def solve(state, generate):
            attempts = list(state.metadata.get("attempts") or [])
            if attempts:
                state.metadata["finalText"] = attempts[-1].get("finalText")
            return state

        return solve

    @scorer(metrics=[accuracy()])
    def comparison_scorer():
        async def score(state, target):
            attempts = list(state.metadata.get("attempts") or [])
            passed = bool(attempts) and all(
                item.get("passed") is True and item.get("status") == "passed"
                for item in attempts
            )
            unverified = any(
                item.get("status") == "unverified" for item in attempts
            )
            return Score(
                value="C" if passed else "I",
                explanation=(
                    f"agent={agent} attempts={len(attempts)} "
                    f"passed={sum(item.get('passed') is True for item in attempts)} "
                    f"unverified={unverified}"
                ),
                metadata={
                    "agent": agent,
                    "mode": mode,
                    "caseResults": attempts,
                },
            )

        return score

    task_id = f"compare-{mode}-{agent}-{suite}"
    task = Task(
        dataset=samples,
        solver=materialized_solver(),
        scorer=comparison_scorer(),
        model="mockllm/model",
        name=task_id,
        version=1,
        metadata={
            "runner": "cute-evals-compare",
            "agent": agent,
            "mode": mode,
            "suite": suite,
        },
    )
    log_dir = output_dir / "inspect-logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    logs = inspect_eval(
        task,
        model="mockllm/model",
        display="none",
        log_dir=str(log_dir),
        fail_on_error=True,
    )
    if not logs or str(logs[0].status) != "success":
        error = logs[0].error if logs else "Inspect AI returned no log"
        raise RuntimeError(f"Inspect compare eval failed for {agent}: {error}")
    return log_dir
