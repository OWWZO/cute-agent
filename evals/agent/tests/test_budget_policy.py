from cute_evals.benchmarks.budget import BudgetPolicy, policy_from_args
from cute_evals.models import BudgetSpec, EvalCase, ExpectedSpec


def _case() -> EvalCase:
    return EvalCase(
        id="budget.case",
        suite="budget",
        version=1,
        hard_gate=True,
        query="test",
        expected=ExpectedSpec(max_steps=12),
        budgets=BudgetSpec(
            max_total_tokens=80_000,
            max_wall_ms=300_000,
            max_tool_calls=12,
            soft_limit_percent=0.8,
        ),
        script=[],
        source_path="budget.json",
    )


def test_product_budget_policy_preserves_original_limits() -> None:
    case = BudgetPolicy(mode="product").apply(_case())

    assert case.budgets.max_total_tokens == 80_000
    assert case.budgets.max_wall_ms == 300_000
    assert case.budgets.max_tool_calls == 12
    assert case.expected.max_steps == 12
    assert case.metadata["budgetPolicy"]["mode"] == "product"


def test_capability_budget_policy_scales_both_agents_equally() -> None:
    policy = BudgetPolicy(
        mode="capability",
        token_multiplier=8,
        wall_multiplier=4,
        tool_multiplier=4,
        step_multiplier=4,
    )

    case = policy.apply(_case())

    assert case.budgets.max_total_tokens == 640_000
    assert case.budgets.max_wall_ms == 1_200_000
    assert case.budgets.max_tool_calls == 48
    assert case.expected.max_steps == 48
    evidence = case.metadata["budgetPolicy"]
    assert evidence["originalBudgets"]["maxTotalTokens"] == 80_000
    assert evidence["effectiveBudgets"]["maxTotalTokens"] == 640_000


def test_budget_policy_reads_cli_namespace_values() -> None:
    class Args:
        budget_mode = "capability"
        capability_token_multiplier = 2
        capability_wall_multiplier = 3
        capability_tool_multiplier = 4
        capability_step_multiplier = 5

    policy = policy_from_args(Args())

    assert policy.to_dict() == {
        "mode": "capability",
        "tokenMultiplier": 2.0,
        "wallMultiplier": 3.0,
        "toolMultiplier": 4.0,
        "stepMultiplier": 5.0,
    }
