from __future__ import annotations

import math
from dataclasses import dataclass, replace
from typing import Any

from ..models import BudgetSpec, EvalCase


@dataclass(frozen=True, slots=True)
class BudgetPolicy:
    mode: str = "product"
    token_multiplier: float = 8.0
    wall_multiplier: float = 4.0
    tool_multiplier: float = 4.0
    step_multiplier: float = 4.0

    def __post_init__(self) -> None:
        if self.mode not in {"product", "capability"}:
            raise ValueError(f"unsupported budget mode: {self.mode}")
        for name in (
            "token_multiplier",
            "wall_multiplier",
            "tool_multiplier",
            "step_multiplier",
        ):
            if getattr(self, name) <= 0:
                raise ValueError(f"{name} must be > 0")

    def apply(self, case: EvalCase) -> EvalCase:
        original = _budget_payload(case.budgets)
        budgets = replace(case.budgets)
        expected = replace(case.expected)
        if self.mode == "capability":
            budgets.max_total_tokens = _scaled(
                budgets.max_total_tokens,
                self.token_multiplier,
            )
            budgets.max_wall_ms = _scaled(
                budgets.max_wall_ms,
                self.wall_multiplier,
            )
            budgets.max_tool_calls = _scaled(
                budgets.max_tool_calls,
                self.tool_multiplier,
            )
            expected.max_steps = _scaled(
                expected.max_steps,
                self.step_multiplier,
            )
        effective = _budget_payload(budgets)
        return replace(
            case,
            budgets=budgets,
            expected=expected,
            metadata={
                **case.metadata,
                "budgetPolicy": {
                    **self.to_dict(),
                    "originalBudgets": original,
                    "effectiveBudgets": effective,
                    "originalMaxSteps": case.expected.max_steps,
                    "effectiveMaxSteps": expected.max_steps,
                },
            },
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "tokenMultiplier": self.token_multiplier,
            "wallMultiplier": self.wall_multiplier,
            "toolMultiplier": self.tool_multiplier,
            "stepMultiplier": self.step_multiplier,
        }


def policy_from_args(args: Any) -> BudgetPolicy:
    return BudgetPolicy(
        mode=getattr(args, "budget_mode", "product"),
        token_multiplier=float(
            getattr(args, "capability_token_multiplier", 8.0)
        ),
        wall_multiplier=float(
            getattr(args, "capability_wall_multiplier", 4.0)
        ),
        tool_multiplier=float(
            getattr(args, "capability_tool_multiplier", 4.0)
        ),
        step_multiplier=float(
            getattr(args, "capability_step_multiplier", 4.0)
        ),
    )


def _scaled(value: int | None, multiplier: float) -> int | None:
    if value is None:
        return None
    return int(math.ceil(value * multiplier))


def _budget_payload(budget: BudgetSpec) -> dict[str, Any]:
    return {
        "maxTotalTokens": budget.max_total_tokens,
        "maxWallMs": budget.max_wall_ms,
        "maxToolCalls": budget.max_tool_calls,
        "softLimitPercent": budget.soft_limit_percent,
    }
