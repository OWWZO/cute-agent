from __future__ import annotations

from cute_evals.models import RunTrace


def test_cache_efficiency_excludes_cold_start_and_keeps_unverified_explicit() -> None:
    trace = RunTrace.from_dict(
        {
            "run": {},
            "llmInvocations": [
                {
                    "promptTokens": 1000,
                    "cachedPromptTokens": 0,
                    "cacheStatus": "OK",
                },
                {
                    "promptTokens": 1200,
                    "cachedPromptTokens": 900,
                    "cacheStatus": "OK",
                },
                {
                    "promptTokens": 1300,
                    "cachedPromptTokens": 1000,
                    "cacheStatus": "RISK",
                },
                {
                    "promptTokens": 1400,
                    "cachedPromptTokens": None,
                    "cacheStatus": "OK",
                },
            ],
            "toolInvocations": [],
            "artifacts": [],
        }
    )

    metrics = trace.cache_efficiency_metrics()

    assert metrics["sampledInvocations"] == 3
    assert metrics["eligibleInvocations"] == 2
    assert metrics["promptTokens"] == 3500
    assert metrics["cachedPromptTokens"] == 1900
    assert metrics["eligiblePromptTokens"] == 2500
    assert metrics["eligibleCachedPromptTokens"] == 1900
    assert metrics["eligibleHitRatio"] == 1900 / 2500
    assert metrics["requestHitRate"] == 1.0
    assert metrics["coldStartMisses"] == 1
    assert metrics["cacheFalseOkRate"] == 1 / 3
    assert metrics["cacheRiskRate"] == 1 / 3
    assert metrics["unverifiedInvocations"] == 1
    assert metrics["nullCachedPromptTokens"] == 1
