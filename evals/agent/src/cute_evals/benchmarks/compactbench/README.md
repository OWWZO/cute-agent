# CompactBench Adapter

This adapter runs Cute-agent's production Java compactor as a CompactBench
method. It does not copy the compaction algorithm and never modifies the
official cases, probes, reader, or scorer.

## Pinned Source

CompactBench 0.3 is not yet published on PyPI. PyPI 0.1.0 uses the obsolete
`compact(transcript, ...)` protocol. Use the source snapshot recorded in
`manifest.yaml`:

```text
https://codeload.github.com/compactbench/compactbench/zip/refs/heads/main
sha256: 8fd94d802cd8f44897541a39fe8b83697e496466bc96cf361b10f3ad5f086eae
```

## Model Configuration

The Java compactor uses the Cute-agent LLM configuration. For an isolated
evaluation endpoint, set:

```powershell
$env:CUTE_EVAL_COMPACT_MODEL = "group/deepseek"
$env:CUTE_EVAL_COMPACT_BASE_URL = "http://127.0.0.1:3425"
$env:CUTE_EVAL_COMPACT_API_KEY = "<key>"
```

`CUTE_EVAL_COMPACT_BASE_URL` is the Cute-agent base URL. CompactBench's reader
uses the OpenAI-compatible `/v1` base URL:

```powershell
$env:COMPACTBENCH_OPENAI_API_KEY = $env:CUTE_EVAL_COMPACT_API_KEY
$env:COMPACTBENCH_OPENAI_BASE_URL = "http://127.0.0.1:3425/v1"
```

## Official Smoke

The following command uses the official `starter` suite with one case per
family and no drift cycles. It is an `official-subset`, not a leaderboard
submission.

```powershell
uv run --with "<compactbench-source>" --with "openai>=3.42" `
  python -m compactbench run `
  --method "evals/agent/src/cute_evals/benchmarks/compactbench/java_method.py:CuteAgentCompactor" `
  --suite starter `
  --provider mock --model mock-deterministic `
  --reader-provider openai --reader-model group/deepseek `
  --budget 2048 --cases 1 --drift-cycles 0 --controls --concurrency 1 `
  --output "<run-dir>/results.jsonl" `
  --traces "<run-dir>/traces.jsonl"
```

## Verified Smoke

On 2026-11-08, the official `starter@2.0.1` subset completed 8 cases with the
official scorer `3.0.0`:

```text
score               0.828
compression ratio   1.7x
budget violations   0
reader errors       0
trap rate           0%
hallucination rate  0%
stale-answer rate   33.3%
drift cycles        0
```

`reference_resolution` scored `0.000`, `task_continuity` scored `0.750`, and
`entity_confusion` scored `0.875`; these are the first concrete failure
families to inspect. `task_continuity` used Java `local-fallback`, so that
case measured the fallback path rather than the full-LLM summarizer. Because
`--drift-cycles 0` was used, the reported `drift resistance` is not evidence
of multi-cycle stability.

Method-side token usage is reported as zero by CompactBench because the
in-process `Compactor` contract has no usage return channel. The Java response
still carries `beforeTokens`, `afterTokens`, `summaryHash`, strategy, and event
ID in artifact `methodMetadata`.
