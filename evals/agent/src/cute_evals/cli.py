from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from dataclasses import asdict
from datetime import datetime, timedelta
from importlib.util import find_spec
from pathlib import Path
from uuid import uuid4

from .ab_runner import (
    audit_cell,
    exit_code_for,
    report_experiment,
    resume_experiment,
    run_experiment,
)
from .benchmarks.bird import BirdAdapter
from .benchmarks.compactbench import CompactBenchAdapter
from .benchmarks.deepresearch import DeepResearchAdapter
from .benchmarks.deepresearch.runner import run_deepsearch
from .benchmarks.gaia import GAIAAdapter
from .benchmarks.longmemeval import LongMemEvalAdapter
from .benchmarks.memoryagentbench import MemoryAgentBenchAdapter
from .benchmarks.marble import MarbleAdapter
from .benchmarks.marble.runner import (
    MarbleRunBlocked,
    MarbleRunConfig,
    run_marble,
)
from .benchmarks.rag import RagAdapter, run_mrag
from .benchmarks.spider2 import Spider2Adapter
from .benchmarks.tau2 import Tau2Adapter, Tau2UserSimulator
from .case_loader import load_case_dirs
from .data_dataset import load_data_dataset_manifest
from .gate import evaluate_gate
from .inspect_runner import run_inspect_attempt
from .judge_benchmarks.agent_reward_bench import AgentRewardBenchAdapter
from .judge_benchmarks.agent_process_bench import AgentProcessBenchAdapter
from .judge_benchmarks.client import OpenAICompatibleJudge
from .judge_benchmarks.trail import TrailAdapter
from .java_runner import (
    compile_java_fixture_runner,
    export_ledger_traces,
    run_java_cases,
    run_java_conversation,
)
from .models import CaseResult, JudgeConfig, RunTrace
from .multi_agent_view import project_multi_agent_view
from .ops import (
    build_matrix_report,
    build_trend_report,
    record_release,
    record_report,
    trend_blocks,
)
from .ops_sample import (
    load_sample_config,
    prune_sample_runs,
    sample_export,
    validate_sample_config,
)
from .ops_schedule import (
    parse_daily_time,
    require_eval_db_env,
    run_daily_scheduler,
)
from .overfit import build_overfit_report, overfit_blocks
from .rag_dataset import load_rag_dataset_manifest
from .report import build_baseline, build_report
from .process_view import project_ledger_process_view, project_process_view
from .runtime import (
    ConversationEntry,
    EnvironmentEvent,
    UserSimulatorServer,
    merge_trace,
    snapshot_payload,
)
from .scorers import score_case
from .trajectory_quality import (
    QualityJudgeConfig,
    resolve_trace_paths,
    run_trajectory_quality,
)
from .trace_tools import (
    approve_case_draft,
    counterfactual_replay,
    replay_trace,
    replay_trace_set,
    write_case_draft,
)

PROFILE_REPEATS = {
    "pr": 1,
    "nightly": 3,
    "release": 3,
}
AGENT_ID = "cute-agent"
SUITE_DIRS = {
    "react-core": ("react", "core"),
    "rag-core": ("rag", "core"),
    "data-core": ("data", "core"),
    "production-core": ("production", "core"),
    "skill-sim": ("skill", "core"),
    "private-badcase": ("private", "badcase"),
    "holdout-core": ("private", "holdout"),
}
AB_SUITES = (
    "hostile-files",
    "tool-discovery",
    "session-recall",
    "compaction-recall",
    "compaction-carry-forward",
    "memory-isolation",
    "memory-update",
    "cache-efficiency",
    "clarify-multiturn",
)


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[4]


def _default_baseline_path(root: Path, profile: str, suite: str) -> Path:
    return root / "evals" / "agent" / "baselines" / f"{profile}-{suite}.json"


def _case_dirs(root: Path, suite: str) -> list[Path]:
    if suite == "all":
        return [
            root / "evals" / "agent" / "cases" / domain / "core"
            for domain in ("react", "rag", "data", "production")
        ]
    if suite in AB_SUITES:
        return [root / "evals" / "agent" / "cases" / "ab" / suite]
    domain, group = SUITE_DIRS[suite]
    return [root / "evals" / "agent" / "cases" / domain / group]


def _resolve_engine(requested: str) -> str:
    if requested != "auto":
        return requested
    return "inspect" if find_spec("inspect_ai") is not None else "native"


def _judge_from_args(args: argparse.Namespace) -> JudgeConfig:
    return JudgeConfig(
        model=args.judge_model or os.environ.get("CUTE_EVAL_JUDGE_MODEL"),
        base_url=args.judge_base_url or os.environ.get("CUTE_EVAL_JUDGE_BASE_URL"),
        api_key=args.judge_api_key or os.environ.get("CUTE_EVAL_JUDGE_API_KEY"),
    )


def _run_native_attempt(
    cases,
    attempt_dir: Path,
    *,
    profile: str,
    mode: str,
    attempt: int,
    trace_prefix: str,
    require_judge: bool,
    judge: JudgeConfig | None,
    judge_cache_dir: Path | None,
    timeout_seconds: int,
    tool_mode: str,
) -> list[CaseResult]:
    traces = run_java_cases(
        cases,
        attempt_dir,
        mode=mode,
        profile=profile,
        build=False,
        timeout_seconds=timeout_seconds,
        tool_mode=tool_mode,
    )
    return [
        score_case(
            case,
            traces[case.id],
            f"{trace_prefix}/traces/{case.id}/trace.json",
            profile=profile,
            require_judge=require_judge,
            judge=judge,
            judge_cache_dir=judge_cache_dir,
            attempt=attempt,
        )
        for case in cases
    ]


def _read_baseline(path: Path | None) -> dict | None:
    if path is None or not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _dataset_hash(cases) -> str:
    payload = [
        {
            "id": case.id,
            "suite": case.suite,
            "version": case.version,
            "query": case.query,
            "judgeTask": case.judge_task,
            "expected": asdict(case.expected),
            "budgets": asdict(case.budgets),
            "script": case.script,
            "metadata": case.metadata,
            "hardGate": case.hard_gate,
            "tools": case.tools,
            "answerContract": case.answer_contract,
            "baselineTrace": case.baseline_trace,
            "compaction": case.compaction,
            "userId": case.user_id,
            "memorySeed": case.memory_seed,
        }
        for case in cases
    ]
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


def _case_batch_timeout_seconds(cases, configured_timeout: int) -> int:
    budget_ms = sum(
        case.budgets.max_wall_ms or 0
        for case in cases
    )
    return max(configured_timeout, (budget_ms + 999) // 1000 + 120)


def run_command(args: argparse.Namespace) -> int:
    root = _repo_root()
    case_dirs = (
        [Path(args.cases_dir).resolve()]
        if args.cases_dir
        else _case_dirs(root, args.suite)
    )
    rag_dataset = None
    data_dataset = None
    for case_dir in case_dirs:
        if case_dir.parent.name == "rag":
            rag_dataset = load_rag_dataset_manifest(case_dir)
        elif case_dir.parent.name == "data":
            data_dataset = load_data_dataset_manifest(case_dir)
    cases = load_case_dirs(
        case_dirs,
        suite=None if args.suite == "all" else args.suite,
    )
    if not cases:
        raise ValueError("evaluation selected no cases")

    mode = args.java_mode
    if mode == "auto":
        mode = "fixture" if args.profile == "pr" else "live"
    if args.profile == "pr" and mode != "fixture":
        raise ValueError("PR profile must use deterministic fixture mode")

    repeat = args.repeat or PROFILE_REPEATS[args.profile]
    if repeat < 1:
        raise ValueError("--repeat must be >= 1")
    engine = _resolve_engine(args.engine)
    output_dir = Path(args.out).resolve() if args.out else (
        root / ".eval-runs" / f"{args.profile}-{args.suite}"
    )
    judge = _judge_from_args(args)
    judge_cache_dir = (
        Path(args.judge_cache_dir).resolve()
        if args.judge_cache_dir
        else root / "evals" / "agent" / ".judge-cache"
    )

    if not args.skip_build:
        compile_java_fixture_runner()

    timeout_seconds = _case_batch_timeout_seconds(cases, args.timeout)
    tool_mode = "production" if mode == "benchmark" else "ephemeral"
    results: list[CaseResult] = []
    for attempt in range(1, repeat + 1):
        attempt_relative = f"attempts/{attempt:02d}"
        attempt_dir = output_dir / attempt_relative
        if engine == "inspect":
            attempt_results = run_inspect_attempt(
                cases,
                attempt_dir,
                profile=args.profile,
                mode=mode,
                attempt=attempt,
                trace_prefix=attempt_relative,
                require_judge=args.require_judge,
                judge=judge,
                judge_cache_dir=judge_cache_dir,
                timeout_seconds=timeout_seconds,
                build=False,
                tool_mode=tool_mode,
            )
        else:
            attempt_results = _run_native_attempt(
                cases,
                attempt_dir,
                profile=args.profile,
                mode=mode,
                attempt=attempt,
                trace_prefix=attempt_relative,
                require_judge=args.require_judge,
                judge=judge,
                judge_cache_dir=judge_cache_dir,
                timeout_seconds=timeout_seconds,
                tool_mode=tool_mode,
            )
        results.extend(attempt_results)

    baseline_path = (
        Path(args.baseline).resolve()
        if args.baseline
        else _default_baseline_path(root, args.profile, args.suite)
    )
    baseline = None if args.update_baseline else _read_baseline(baseline_path)
    report = build_report(
        profile=args.profile,
        agent=AGENT_ID,
        cases=results,
        output_dir=output_dir,
        baseline=baseline,
        inspect_log=(
            str(output_dir / "attempts" / "01" / "inspect-logs")
            if engine == "inspect"
            else None
        ),
        environment={
            "engine": engine,
            "javaMode": mode,
            "repeat": repeat,
            "judgeModel": judge.model,
            "judgeBaseUrl": judge.base_url,
            "requireJudge": args.require_judge,
            "suite": args.suite,
            "datasetHash": _dataset_hash(cases),
            "ragDataset": rag_dataset,
            "dataDataset": data_dataset,
        },
    )
    if args.update_baseline:
        summary = report["summary"]
        if summary["failed"] or summary["unstable"] or (
            args.require_judge and summary["unverified"]
        ):
            print("refusing to update baseline from a failing evaluation")
            return 1
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(
            json.dumps(build_baseline(report), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"baseline: {baseline_path}")

    summary = report["summary"]
    print(
        f"cute-evals: cases={summary['caseCount']} "
        f"attempts={summary['attemptCount']} passed={summary['passed']} "
        f"failed={summary['failed']} unstable={summary['unstable']} "
        f"unverified={summary['unverified']} "
        f"quality@1={summary['qualityPassAt1']:.2%} "
        f"slo@1={summary['sloPassAt1']:.2%} "
        f"pass@1={summary['passAt1']:.2%} pass^k={summary['passAtK']:.2%}"
    )
    print(f"engine: {engine} mode: {mode} repeat: {repeat}")
    print(f"report: {output_dir / 'report.html'}")

    baseline_diff = report.get("baselineDiff") or {}
    regressions = baseline_diff.get("regressions") or []
    if regressions:
        print(f"baseline regressions: {', '.join(regressions)}")

    if summary["failed"] > 0:
        return 1
    if args.profile != "pr" and summary["unstable"] > 0:
        return 1
    if args.require_judge and summary["unverified"] > 0:
        return 1
    if regressions:
        return 1
    return 0


def case_draft_command(args: argparse.Namespace) -> int:
    trace_path = Path(args.trace).resolve()
    output_path = Path(args.out).resolve()
    draft = write_case_draft(
        trace_path,
        output_path,
        failure_class=args.failure_class,
    )
    print(f"case draft: {output_path}")
    print(f"failure class: {draft['source']['failureClass']}")
    print("oracle: unverified until a human fills expected")
    return 0


def case_approve_command(args: argparse.Namespace) -> int:
    approved = approve_case_draft(
        Path(args.draft).resolve(),
        Path(args.out).resolve(),
        reviewer=args.reviewer,
        notes=args.notes,
        hard_gate=not args.advisory,
        force=args.force,
    )
    print(f"approved badcase: {Path(args.out).resolve()}")
    print(f"hard gate: {approved['hardGate']}")
    return 0


def _release_metadata(args: argparse.Namespace) -> dict | None:
    values = {
        "version": args.release_version,
        "commit": args.release_commit,
        "model": args.release_model,
    }
    if not any(values.values()):
        return None
    missing = [key for key, value in values.items() if not value]
    if missing:
        raise ValueError(
            "release metadata requires version, commit and model; "
            f"missing: {', '.join(missing)}"
        )
    return values


def ops_record_command(args: argparse.Namespace) -> int:
    history_path = Path(args.history).resolve() if args.history else (
        _repo_root() / ".eval-runs" / "ops" / "history.jsonl"
    )
    record, created = record_report(
        Path(args.report).resolve(),
        history_path,
        series=args.series,
        variant=args.variant,
        release=_release_metadata(args),
        notes=args.notes,
        repo_root=_repo_root(),
    )
    print(
        f"ops record: {'created' if created else 'existing'} "
        f"key={record['recordKey']} series={record['series']} "
        f"variant={record['variant']}"
    )
    print(f"history: {history_path}")
    return 0


def ops_trend_command(args: argparse.Namespace) -> int:
    history_path = Path(args.history).resolve() if args.history else (
        _repo_root() / ".eval-runs" / "ops" / "history.jsonl"
    )
    output_dir = Path(args.out).resolve() if args.out else (
        _repo_root() / ".eval-runs" / "ops" / "trend"
    )
    report = build_trend_report(
        history_path,
        output_dir,
        series=args.series,
        limit=args.limit,
        max_token_increase=args.max_token_increase,
        max_duration_increase=args.max_duration_increase,
    )
    print(
        f"ops trend: records={report['recordCount']} "
        f"groups={report['summary']['groupCount']} "
        f"statuses={report['summary']['statusCounts']}"
    )
    print(f"report: {output_dir / 'trend.html'}")
    if args.fail_on_regression and trend_blocks(report):
        return 1
    return 0


def ops_matrix_command(args: argparse.Namespace) -> int:
    output_dir = Path(args.out).resolve()
    report = build_matrix_report(
        [Path(path) for path in args.reports],
        output_dir,
    )
    print(
        f"ops matrix: experiments={report['summary']['experimentCount']} "
        f"recommendations={report['summary']['recommendations']}"
    )
    print(f"report: {output_dir / 'matrix.html'}")
    if args.fail_on_block and report["summary"]["hasBlock"]:
        return 1
    return 0


def ops_release_command(args: argparse.Namespace) -> int:
    output_dir = Path(args.out).resolve()
    record = record_release(
        Path(args.report).resolve(),
        output_dir,
        version=args.version,
        commit=args.commit,
        model=args.model,
        min_pass_at_k=args.min_pass_at_k,
    )
    if args.history:
        record_report(
            Path(args.report).resolve(),
            Path(args.history).resolve(),
            series=args.series,
            variant=args.variant,
            release={
                "version": args.version,
                "commit": args.commit,
                "model": args.model,
                "decision": record["decision"],
            },
            notes=f"release record: {output_dir}",
            repo_root=_repo_root(),
        )
    print(
        f"ops release: version={args.version} decision={record['decision']} "
        f"checks={sum(1 for item in record['checks'] if item['passed'])}"
        f"/{len(record['checks'])}"
    )
    print(f"record: {output_dir / 'release-record.json'}")
    return 0 if record["decision"] == "ship" else 1


def ops_gate_command(args: argparse.Namespace) -> int:
    output_dir = Path(args.out).resolve()
    payload = evaluate_gate(
        [Path(item) for item in args.reports],
        output_dir,
        min_pass_at_k=args.min_pass_at_k,
        require_ship_ab=args.require_ship_ab,
    )
    print(
        f"cute-evals gate: {'pass' if payload['passed'] else 'block'} "
        f"reports={len(payload['reports'])}"
    )
    print(f"report: {output_dir / 'gate-report.md'}")
    return 0 if payload["passed"] else 1


def ops_overfit_command(args: argparse.Namespace) -> int:
    root = _repo_root()
    validation_dir = (
        Path(args.validation_cases).resolve()
        if args.validation_cases
        else root / "evals" / "agent" / "cases" / "production" / "core"
    )
    holdout_dir = (
        Path(args.holdout_cases).resolve()
        if args.holdout_cases
        else root / "evals" / "agent" / "cases" / "private" / "holdout"
    )
    validation_cases = load_case_dirs(
        [validation_dir],
        suite="production-core",
    )
    holdout_cases = load_case_dirs(
        [holdout_dir],
        suite="holdout-core",
    )
    output_dir = Path(args.out).resolve() if args.out else (
        root / ".eval-runs" / "ops" / "overfit"
    )
    report = build_overfit_report(
        validation_report_path=Path(args.validation_report).resolve(),
        holdout_report_path=Path(args.holdout_report).resolve(),
        validation_cases=validation_cases,
        holdout_cases=holdout_cases,
        output_dir=output_dir,
    )
    print(
        f"ops overfit: verdict={report['verdict']} "
        f"validation={report['validation']['caseCount']} "
        f"holdout={report['holdout']['caseCount']} "
        f"passAtKgap={report['gaps']['passAtK']:.2%}"
    )
    print(f"report: {output_dir / 'overfit-report.html'}")
    if args.fail_on_risk and overfit_blocks(report):
        return 1
    return 0


def _sample_config(args: argparse.Namespace) -> dict:
    config = load_sample_config(
        Path(args.config).resolve() if args.config else None
    )
    overrides = {
        "maxCandidates": args.max_candidates,
        "failureQuota": args.failure_quota,
        "healthQuota": args.health_quota,
        "seed": args.seed,
        "retentionDays": args.retention_days,
    }
    for key, value in overrides.items():
        if value is not None:
            config[key] = value
    if args.status:
        config["statuses"] = [
            item.strip().upper()
            for item in args.status.split(",")
            if item.strip()
        ]
    validate_sample_config(config)
    return config


def ops_sample_command(args: argparse.Namespace) -> int:
    config = _sample_config(args)

    if (args.from_time is None) != (args.to_time is None):
        raise ValueError("--from and --to must be provided together")
    if args.from_time and args.to_time:
        from_time = datetime.fromisoformat(args.from_time)
        to_time = datetime.fromisoformat(args.to_time)
    else:
        to_time = datetime.now().replace(microsecond=0)
        from_time = to_time - timedelta(hours=int(config["lookbackHours"]))
    if from_time >= to_time:
        raise ValueError("--from must be earlier than --to")

    output_dir = Path(args.out).resolve()
    export_dir = output_dir / "export"
    export_manifest = export_ledger_traces(
        export_dir,
        from_time=from_time.isoformat(timespec="seconds"),
        to_time=to_time.isoformat(timespec="seconds"),
        limit=int(config["maxCandidates"]),
        statuses=list(config["statuses"]),
        spring_profile=args.spring_profile,
        build=not args.skip_build,
        timeout_seconds=args.timeout,
    )
    manifest = sample_export(
        export_dir,
        output_dir,
        config=config,
        from_time=from_time,
        to_time=to_time,
    )
    removed = prune_sample_runs(
        output_dir.parent,
        retention_days=int(config["retentionDays"]),
        keep=output_dir,
    )
    summary = manifest["summary"]
    print(
        f"ops sample: exported={export_manifest['exportedTraces']} "
        f"scanned={summary['scanned']} problems={summary['problemCount']} "
        f"selected={summary['selectedTotal']} drafts={len(manifest['drafts'])}"
    )
    print(f"samples: {output_dir / 'samples'}")
    print(f"audit: {output_dir / 'audit-report.html'}")
    print(f"manifest: {output_dir / 'sample-manifest.json'}")
    print("approval: required; generated drafts are not regression cases")
    if removed:
        print(f"retention removed: {len(removed)} old sample run(s)")
    return 0


def ops_schedule_command(args: argparse.Namespace) -> int:
    config = _sample_config(args)
    enabled = bool(config["schedule"]["enabled"])
    if args.enable:
        enabled = True
    if args.disable:
        enabled = False
    if not enabled:
        print(
            "ops schedule: disabled by default; "
            "pass --enable to start the Python scheduler"
        )
        return 0
    at = parse_daily_time(args.at or str(config["schedule"]["at"]))
    timezone = args.timezone or str(config["schedule"]["timezone"])
    require_eval_db_env()
    out_root = (
        Path(args.out_root).resolve()
        if args.out_root
        else _repo_root() / ".eval-runs" / "ops" / "daily"
    )

    def run_once(output_dir: Path) -> dict:
        print(
            f"scheduled ops sample started: {datetime.now().isoformat(timespec='seconds')} "
            f"out={output_dir}",
            flush=True,
        )
        sample_args = argparse.Namespace(
            config=args.config,
            from_time=None,
            to_time=None,
            out=str(output_dir),
            max_candidates=args.max_candidates,
            failure_quota=args.failure_quota,
            health_quota=args.health_quota,
            seed=args.seed,
            status=args.status,
            retention_days=args.retention_days,
            spring_profile=args.spring_profile,
            timeout=args.timeout,
            skip_build=args.skip_build,
        )
        code = ops_sample_command(sample_args)
        if code != 0:
            raise RuntimeError(f"ops sample exited with code {code}")
        manifest_path = output_dir / "sample-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        return {
            "scanned": manifest["summary"]["scanned"],
            "problems": manifest["summary"]["problemCount"],
            "selected": manifest["summary"]["selectedTotal"],
            "audit": str(output_dir / "audit-report.html"),
        }

    print(
        f"ops schedule: at={at.isoformat(timespec='minutes')} "
        f"timezone={timezone} out={out_root}",
        flush=True,
    )
    print("scheduler: Ctrl+C to stop; one process should own one scheduler", flush=True)
    run_daily_scheduler(
        at=at,
        timezone=timezone,
        out_root=out_root,
        run_once=run_once,
        run_now=args.run_now,
    )
    return 0


def trace_replay_command(args: argparse.Namespace) -> int:
    report = replay_trace(
        Path(args.case).resolve(),
        Path(args.trace).resolve(),
        Path(args.out).resolve(),
        profile=args.profile,
        judge=_judge_from_args(args),
        require_judge=args.require_judge,
    )
    summary = report["summary"]
    print(
        f"trace-replay: cases={summary['caseCount']} "
        f"passed={summary['passed']} failed={summary['failed']} "
        f"unverified={summary['unverified']}"
    )
    print(f"report: {Path(args.out).resolve() / 'report.html'}")
    return 0 if summary["failed"] == 0 else 1


def private_replay_command(args: argparse.Namespace) -> int:
    root = _repo_root()
    case_dirs = (
        [Path(args.cases_dir).resolve()]
        if args.cases_dir
        else _case_dirs(root, "private-badcase")
    )
    cases = load_case_dirs(case_dirs, suite="private-badcase")
    output_dir = (
        Path(args.out).resolve()
        if args.out
        else root / ".eval-runs" / "private-badcase"
    )
    report = replay_trace_set(
        cases,
        root / "evals" / "agent",
        output_dir,
        judge=_judge_from_args(args),
        require_judge=args.require_judge,
    )
    summary = report["summary"]
    print(
        f"private-replay: cases={summary['caseCount']} "
        f"passed={summary['passed']} failed={summary['failed']} "
        f"unverified={summary['unverified']}"
    )
    print(f"report: {output_dir / 'report.html'}")
    return 0 if summary["failed"] == 0 else 1


def counterfactual_command(args: argparse.Namespace) -> int:
    comparison = counterfactual_replay(
        Path(args.case).resolve(),
        Path(args.baseline_trace).resolve(),
        Path(args.out).resolve(),
        mode=args.java_mode,
        timeout_seconds=args.timeout,
    )
    print(f"case: {comparison['caseId']}")
    print(f"baseline status: {comparison['baseline']['status']}")
    print(f"candidate status: {comparison['candidate']['status']}")
    print(f"tool sequence changed: {comparison['changed']['toolSequence']}")
    print(f"report: {Path(args.out).resolve() / 'counterfactual.json'}")
    return 0


def ledger_export_command(args: argparse.Namespace) -> int:
    manifest = export_ledger_traces(
        Path(args.out).resolve(),
        run_id=args.run_id,
        session_id=args.session_id,
        from_time=args.from_time,
        to_time=args.to_time,
        limit=args.limit,
        statuses=args.status.split(",") if args.status else None,
        spring_profile=args.spring_profile,
    )
    print(f"exported traces: {manifest['exportedTraces']}")
    print(f"manifest: {Path(args.out).resolve() / 'export-manifest.json'}")
    return 0


def _benchmark_settings(args: argparse.Namespace) -> tuple[str, int, Path]:
    mode = args.java_mode
    if mode == "auto":
        mode = "fixture" if args.profile == "pr" else "live"
    if args.profile == "pr" and mode != "fixture":
        raise ValueError("PR profile must use deterministic fixture mode")
    repeat = args.repeat or PROFILE_REPEATS[args.profile]
    if repeat < 1:
        raise ValueError("--repeat must be >= 1")
    output_dir = Path(args.out).resolve() if args.out else (
        _repo_root()
        / ".eval-runs"
        / f"benchmark-{args.name}-{args.split}-{args.profile}"
    )
    return mode, repeat, output_dir


def _report_benchmark(
    *,
    adapter,
    args: argparse.Namespace,
    results: list[CaseResult],
    output_dir: Path,
    mode: str,
    repeat: int,
    require_official: bool = False,
) -> int:
    metadata = adapter.report_metadata()
    report = build_report(
        profile=args.profile,
        agent=AGENT_ID,
        cases=results,
        output_dir=output_dir,
        environment={
            "engine": f"{adapter.name}-adapter",
            "javaMode": (
                mode
                if mode in {"fixture", "live", "benchmark"}
                else None
            ),
            "runnerMode": mode,
            "repeat": repeat,
            "sampling": {
                "temperature": 0.0,
                "mode": "deterministic",
                "source": "application-eval.yml",
            },
            "suite": f"{adapter.name}-{metadata['split']}",
            "datasetHash": metadata["datasetHash"],
            "scoreLabel": metadata["scoreLabel"],
            "upstreamVersion": metadata["version"],
        },
        benchmark=metadata,
    )
    summary = report["summary"]
    print(
        f"cute-evals benchmark={adapter.name} version={adapter.version} "
        f"cases={summary['caseCount']} attempts={summary['attemptCount']} "
        f"passed={summary['passed']} failed={summary['failed']} "
        f"unstable={summary['unstable']} unverified={summary['unverified']} "
        f"quality@1={summary['qualityPassAt1']:.2%} "
        f"slo@1={summary['sloPassAt1']:.2%}"
    )
    print(f"score-label: {metadata['scoreLabel']} mode: {mode}")
    official = metadata.get("officialEvaluation")
    if isinstance(official, dict):
        race = official.get("race") or {}
        fact = official.get("fact") or {}
        if race or fact:
            print(
                "official-evaluation: "
                f"status={official.get('status')} "
                f"race-overall={race.get('Overall Score')} "
                f"fact-valid-rate={fact.get('valid_rate')}"
            )
        elif official.get("metric"):
            print(
                "official-evaluation: "
                f"status={official.get('status')} "
                f"metric={official.get('metric')}"
            )
    print(f"report: {output_dir / 'report.html'}")
    blocked = summary["failed"] or summary["unstable"]
    require_unverified = require_official or getattr(args, "require_judge", False)
    if require_unverified and summary["unverified"]:
        blocked = True
    return 1 if blocked else 0


def _run_gaia_benchmark(args: argparse.Namespace) -> int:
    adapter = GAIAAdapter(
        Path(args.manifest).resolve()
        if args.manifest
        else Path(__file__).resolve().parent
        / "benchmarks"
        / "gaia"
        / "manifest.yaml",
        data_home=Path(args.data_home).resolve() if args.data_home else None,
    )
    tasks = adapter.tasks(args.split)
    mode, repeat, output_dir = _benchmark_settings(args)
    if not args.skip_build:
        compile_java_fixture_runner()
    results: list[CaseResult] = []
    for attempt in range(1, repeat + 1):
        attempt_relative = f"attempts/{attempt:02d}"
        attempt_dir = output_dir / attempt_relative
        prepared = [
            adapter.prepare(task, attempt_dir / "workspace" / task.id)
            for task in tasks
        ]
        cases = [adapter.case_for_task(item.task) for item in prepared]
        timeout_seconds = _case_batch_timeout_seconds(cases, args.timeout)
        traces = run_java_cases(
            cases,
            attempt_dir,
            mode=mode,
            profile=args.profile,
            build=False,
            timeout_seconds=timeout_seconds,
            runner_class=(
                "org.wwz.ai.test.domain.EvalBenchmarkLiveRunnerCli"
                if mode == "live"
                else None
            ),
            workspace_roots={item.task.id: item.workspace for item in prepared},
            tool_mode="production" if mode == "live" else "ephemeral",
        )
        for item, case in zip(prepared, cases, strict=True):
            trace = traces.get(item.task.id)
            if trace is None:
                raise ValueError(f"Java runner returned no trace for {item.task.id}")
            run = adapter.collect(
                item.task,
                trace,
                f"{attempt_relative}/traces/{item.task.id}/trace.json",
                prepared=item,
            )
            result = adapter.score(run)
            result.attempt = attempt
            results.append(result)
    return _report_benchmark(
        adapter=adapter,
        args=args,
        results=results,
        output_dir=output_dir,
        mode=mode,
        repeat=repeat,
        require_official=mode == "live",
    )


def _run_deepresearch_benchmark(args: argparse.Namespace) -> int:
    if args.profile == "pr":
        raise ValueError("DeepResearch Bench requires nightly or release")
    if args.split != "official-full":
        raise ValueError(
            "DeepResearch Bench requires --split official-full; "
            "use --limit for a non-official smoke subset"
        )
    adapter = DeepResearchAdapter(
        Path(args.manifest).resolve()
        if args.manifest
        else Path(__file__).resolve().parent
        / "benchmarks"
        / "deepresearch"
        / "manifest.yaml",
        data_home=Path(args.data_home).resolve() if args.data_home else None,
        source_dir=Path(args.source_dir).resolve() if args.source_dir else None,
        target_model=args.target_model,
        limit=args.limit,
    )
    tasks = adapter.tasks("official-full")
    _, _, output_dir = _benchmark_settings(args)
    repeat = args.repeat or 1
    search_engines = [
        item.strip()
        for item in (args.search_engines or "").split(",")
        if item.strip()
    ]
    results: list[CaseResult] = []
    for attempt in range(1, repeat + 1):
        attempt_relative = f"attempts/{attempt:02d}"
        attempt_dir = output_dir / attempt_relative
        attempt_runs = []
        attempt_results: list[CaseResult] = []
        for task in tasks:
            prepared = adapter.prepare(task, attempt_dir / "workspace" / task.id)
            deepsearch = run_deepsearch(
                base_url=args.deepsearch_url,
                query=task.question,
                task_id=task.id,
                timeout_seconds=args.deepsearch_timeout,
                search_engines=search_engines,
                max_loop=args.deepsearch_max_loop,
            )
            deepsearch.save_artifacts(prepared.workspace)
            trace_relative = (
                f"{attempt_relative}/tasks/{task.id}/trace.json"
            )
            trace_path = output_dir / trace_relative
            trace_path.parent.mkdir(parents=True, exist_ok=True)
            trace_payload = deepsearch.trace_payload(trace_path=trace_relative)
            trace_path.write_text(
                json.dumps(trace_payload, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            trace = RunTrace.from_dict(trace_payload)
            run = adapter.collect(
                prepared.task,
                trace,
                trace_relative,
                prepared=prepared,
            )
            attempt_runs.append(run)
            result = adapter.score(run)
            result.attempt = attempt
            attempt_results.append(result)

        submission_path = adapter.write_submission(
            attempt_runs,
            attempt_dir / "submission" / f"{adapter.target_model}.jsonl",
        )
        all_success = all(
            run.trace.status == "SUCCESS" and bool(run.trace.final_text.strip())
            for run in attempt_runs
        )
        if args.skip_official_eval or not all_success:
            adapter.record_official_result(
                {
                    "status": "unverified",
                    "reason": (
                        "official evaluation skipped"
                        if args.skip_official_eval
                        else "one or more DeepSearch tasks failed or returned no report"
                    ),
                    "submission": str(submission_path),
                }
            )
        else:
            openrouter = bool(
                (
                    args.judge_base_url
                    or os.environ.get("OPENROUTER_BASE_URL")
                    or ""
                ).rstrip("/").endswith("openrouter.ai/api/v1")
            )
            official_result = adapter.evaluate_official(
                raw_data_path=submission_path,
                output_dir=attempt_dir / "official",
                race_model=(
                    args.judge_model
                    or ("openai/gpt-5.5" if openrouter else None)
                ),
                clean_model=(
                    args.clean_model
                    or ("openai/gpt-5.6-luna" if openrouter else None)
                ),
                fact_model=(
                    args.fact_model
                    or ("openai/gpt-5.4-mini" if openrouter else None)
                ),
                base_url=(
                    args.judge_base_url
                    or os.environ.get("OPENAI_BASE_URL")
                    or os.environ.get("OPENROUTER_BASE_URL")
                    or (
                        "https://openrouter.ai/api/v1"
                        if os.environ.get("OPENROUTER_API_KEY")
                        and not os.environ.get("OPENAI_API_KEY")
                        else None
                    )
                ),
                api_key=(
                    args.judge_api_key
                    or os.environ.get("OPENAI_API_KEY")
                    or os.environ.get("OPENROUTER_API_KEY")
                ),
                jina_api_key=(
                    args.jina_api_key
                    or os.environ.get("JINA_API_KEY")
                ),
                max_workers=args.official_max_workers,
            )
            print(
                "deepresearch official: "
                f"status={official_result.get('status')} "
                f"submission={submission_path}"
            )
        adapter.apply_official_results(attempt_results)
        results.extend(attempt_results)

    return _report_benchmark(
        adapter=adapter,
        args=args,
        results=results,
        output_dir=output_dir,
        mode="python-direct",
        repeat=repeat,
        require_official=not args.skip_official_eval,
    )


def _run_rag_python_benchmark(args: argparse.Namespace) -> int:
    if args.profile == "pr":
        raise ValueError("RAG Python requires nightly or release")
    if args.split != "internal":
        raise ValueError("RAG Python requires --split internal")
    adapter = RagAdapter(
        Path(args.manifest).resolve()
        if args.manifest
        else Path(__file__).resolve().parent
        / "benchmarks"
        / "rag"
        / "manifest.yaml",
        limit=args.limit,
    )
    tasks = adapter.tasks("internal")
    _, repeat, output_dir = _benchmark_settings(args)
    judge = _judge_from_args(args)
    require_judge = bool(getattr(args, "require_judge", False))
    kb_id = args.mrag_kb_id or os.environ.get("CUTE_EVAL_MRAG_KB_ID", "")
    results: list[CaseResult] = []

    for attempt in range(1, repeat + 1):
        attempt_relative = f"attempts/{attempt:02d}"
        attempt_dir = output_dir / attempt_relative
        for task in tasks:
            prepared = adapter.prepare(task, attempt_dir / "workspace" / task.id)
            mrag = run_mrag(
                base_url=args.mrag_url,
                question=task.question,
                task_id=task.id,
                kb_id=kb_id,
                timeout_seconds=args.mrag_timeout,
            )
            mrag.save_artifacts(prepared.workspace)
            trace_relative = f"{attempt_relative}/tasks/{task.id}/trace.json"
            trace_path = output_dir / trace_relative
            trace_path.parent.mkdir(parents=True, exist_ok=True)
            trace_payload = mrag.trace_payload(trace_path=trace_relative)
            trace_path.write_text(
                json.dumps(trace_payload, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            trace = RunTrace.from_dict(trace_payload)
            run = adapter.collect(
                task,
                trace,
                trace_relative,
                prepared=prepared,
                retrieval_trace=mrag.retrieval_trace,
            )
            result = adapter.score(
                run,
                judge=judge,
                require_judge=require_judge,
            )
            result.attempt = attempt
            results.append(result)

    return _report_benchmark(
        adapter=adapter,
        args=args,
        results=results,
        output_dir=output_dir,
        mode="python-direct",
        repeat=repeat,
    )


def _run_tau2_benchmark(args: argparse.Namespace) -> int:
    if args.profile == "pr":
        raise ValueError("tau2 official benchmark requires nightly or release")
    adapter = Tau2Adapter(
        Path(args.manifest).resolve()
        if args.manifest
        else Path(__file__).resolve().parent
        / "benchmarks"
        / "tau2"
        / "manifest.yaml",
        data_home=Path(args.data_home).resolve() if args.data_home else None,
    )
    tasks = adapter.tasks(args.split)
    mode, repeat, output_dir = _benchmark_settings(args)
    if args.user_simulator_model is None:
        raise ValueError("tau2 requires --user-simulator-model")
    if args.user_simulator_base_url is None or args.user_simulator_api_key is None:
        raise ValueError(
            "tau2 requires --user-simulator-base-url and --user-simulator-api-key"
        )
    if not args.skip_build:
        compile_java_fixture_runner()
    results: list[CaseResult] = []
    for attempt in range(1, repeat + 1):
        attempt_relative = f"attempts/{attempt:02d}"
        attempt_dir = output_dir / attempt_relative
        for task in tasks:
            server = adapter.task_server(task).start()
            user_server = None
            try:
                prepared = adapter.prepare(
                    task,
                    attempt_dir / "workspace" / task.id,
                    server=server,
                )
                case = adapter.case_for_task(prepared.task)
                simulator = Tau2UserSimulator(
                    server=server,
                    model=args.user_simulator_model,
                    base_url=args.user_simulator_base_url,
                    api_key=args.user_simulator_api_key,
                )
                user_server = UserSimulatorServer(simulator, prepared.task)
                initial = user_server.start()
                if not initial:
                    raise RuntimeError("user simulator produced no initial message")
                conversation = run_java_conversation(
                    case,
                    [],
                    attempt_dir / "turns" / task.id,
                    mode="live",
                    profile=args.profile,
                    build=False,
                    timeout_seconds=args.timeout,
                    tool_mode="ephemeral",
                    tool_endpoint=server.endpoint,
                    workspace_root=prepared.workspace,
                    user_simulator_endpoint=user_server.endpoint,
                    initial_user_message=initial,
                    max_turns=int(adapter.manifest["budgets"]["maxTurns"]),
                )
                conversation_entries = []
                for index, row in enumerate(conversation.turns, start=1):
                    turn_id = str(row.get("turnId") or f"turn-{index:03d}")
                    conversation_entries.extend(
                        [
                            ConversationEntry(
                                message_id=f"msg-{uuid4().hex[:12]}",
                                conversation_id=conversation.conversation_id,
                                turn_id=turn_id,
                                turn_index=index,
                                role="user",
                                actor="user-simulator",
                                content=str(row.get("query") or ""),
                            ),
                            ConversationEntry(
                                message_id=f"msg-{uuid4().hex[:12]}",
                                conversation_id=conversation.conversation_id,
                                turn_id=turn_id,
                                turn_index=index,
                                role="assistant",
                                actor="cute-agent",
                                content=str(row.get("finalText") or ""),
                            ),
                        ]
                    )
                events = []
                for trace in conversation.traces:
                    turn_id = str(
                        (trace.raw.get("environment") or {}).get("turnId")
                        or "turn-unknown"
                    )
                    for invocation in trace.tool_invocations:
                        events.append(
                            EnvironmentEvent(
                                event_id=f"env-{uuid4().hex[:12]}",
                                conversation_id=conversation.conversation_id,
                                turn_id=turn_id,
                                event_type="tool_call",
                                payload=invocation,
                            ).to_dict()
                        )
                final_snapshot = snapshot_payload(
                    "tau2-db",
                    server.snapshot(),
                    turn_id="final",
                )
                trace = merge_trace(
                    task=prepared.task,
                    conversation_id=conversation.conversation_id,
                    turn_traces=conversation.traces,
                    conversation=[item.to_dict() for item in conversation_entries],
                    environment_events=events,
                    environment_snapshots=[final_snapshot.to_dict()],
                    termination_reason=(
                        "user_stop"
                        if conversation.status == "SUCCESS"
                        else (
                            "user_simulator_error"
                            if simulator.error()
                            else "agent_failure"
                        )
                    ),
                    error=conversation.error,
                )
                trace_path = attempt_dir / "traces" / task.id / "trace.json"
                trace_path.parent.mkdir(parents=True, exist_ok=True)
                trace_path.write_text(
                    json.dumps(trace.raw, ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )
                try:
                    oracle = adapter.evaluate(server, trace)
                except Exception as exc:  # noqa: BLE001 - surface evaluator infrastructure error
                    oracle = {
                        "reward": 0.0,
                        "error": str(exc),
                        "status": "infrastructure_error",
                    }
                run = adapter.collect(
                    prepared.task,
                    trace,
                    f"{attempt_relative}/traces/{task.id}/trace.json",
                    oracle=oracle,
                    prepared=prepared,
                )
                result = adapter.score(run)
                result.attempt = attempt
                results.append(result)
            finally:
                if user_server is not None:
                    user_server.close()
                server.close()
    return _report_benchmark(
        adapter=adapter,
        args=args,
        results=results,
        output_dir=output_dir,
        mode=mode,
        repeat=repeat,
        require_official=True,
    )


def _run_longmemeval_benchmark(args: argparse.Namespace) -> int:
    if args.profile == "pr":
        raise ValueError("LongMemEval requires nightly or release")
    judge = _judge_from_args(args)
    adapter = LongMemEvalAdapter(
        Path(args.manifest).resolve()
        if args.manifest
        else Path(__file__).resolve().parent
        / "benchmarks"
        / "longmemeval"
        / "manifest.yaml",
        data_home=Path(args.data_home).resolve() if args.data_home else None,
        data_path=Path(args.data_file).resolve() if args.data_file else None,
        dataset_file=args.dataset_file,
        judge=judge,
    )
    if not judge.configured or not judge.openai_compatible:
        raise ValueError(
            "LongMemEval requires --judge-model, --judge-base-url "
            "and --judge-api-key"
        )
    tasks = adapter.tasks(args.split)
    mode, repeat, output_dir = _benchmark_settings(args)
    if mode != "live":
        raise ValueError("LongMemEval requires live model mode")
    if not args.skip_build:
        compile_java_fixture_runner()
    results: list[CaseResult] = []
    for attempt in range(1, repeat + 1):
        attempt_relative = f"attempts/{attempt:02d}"
        attempt_dir = output_dir / attempt_relative
        namespaces: dict[str, str] = {}
        prepared = []
        cases = []
        for task in tasks:
            namespace = f"{args.profile}-{attempt:02d}-{uuid4().hex[:10]}"
            namespaces[task.id] = namespace
            item = adapter.prepare(
                task,
                attempt_dir / "workspace" / task.id,
            )
            prepared.append(item)
            cases.append(
                adapter.case_for_task(task, namespace=namespace)
            )
        traces = run_java_cases(
            cases,
            attempt_dir,
            mode="benchmark",
            profile=args.profile,
            build=False,
            timeout_seconds=_case_batch_timeout_seconds(cases, args.timeout),
            runner_class="org.wwz.ai.test.domain.EvalBenchmarkLiveRunnerCli",
            workspace_roots={item.task.id: item.workspace for item in prepared},
            tool_mode="production",
        )
        for item in prepared:
            trace = traces.get(item.task.id)
            if trace is None:
                raise ValueError(
                    f"Java runner returned no trace for {item.task.id}"
                )
            run = adapter.collect(
                item.task,
                trace,
                f"{attempt_relative}/traces/{item.task.id}/trace.json",
                prepared=item,
            )
            run.metadata["namespace"] = namespaces[item.task.id]
            result = adapter.score(run)
            result.attempt = attempt
            results.append(result)
    return _report_benchmark(
        adapter=adapter,
        args=args,
        results=results,
        output_dir=output_dir,
        mode=mode,
        repeat=repeat,
        require_official=True,
    )


def _run_memoryagentbench_benchmark(args: argparse.Namespace) -> int:
    if args.profile == "pr":
        raise ValueError("MemoryAgentBench requires nightly or release")
    judge = _judge_from_args(args)
    if (
        args.split == "official-full"
        and not judge.configured
    ):
        raise ValueError(
            "MemoryAgentBench official-full requires --judge-model"
        )
    if (
        args.split == "official-full"
        and not judge.openai_compatible
    ):
        raise ValueError(
            "MemoryAgentBench official-full requires --judge-base-url "
            "and --judge-api-key"
        )
    adapter = MemoryAgentBenchAdapter(
        Path(args.manifest).resolve()
        if args.manifest
        else Path(__file__).resolve().parent
        / "benchmarks"
        / "memoryagentbench"
        / "manifest.yaml",
        data_home=Path(args.data_home).resolve() if args.data_home else None,
        source_dir=Path(args.source_dir).resolve() if args.source_dir else None,
        judge=judge,
    )
    tasks = adapter.tasks(args.split)
    mode, repeat, output_dir = _benchmark_settings(args)
    if mode != "live":
        raise ValueError("MemoryAgentBench requires live model mode")
    if not args.skip_build:
        compile_java_fixture_runner()

    results: list[CaseResult] = []
    for attempt in range(1, repeat + 1):
        attempt_relative = f"attempts/{attempt:02d}"
        attempt_dir = output_dir / attempt_relative
        for task in tasks:
            namespace = f"{args.profile}-{attempt:02d}-{uuid4().hex[:10]}"
            prepared = adapter.prepare(
                task,
                attempt_dir / "workspace" / task.id,
            )
            case = adapter.case_for_task(
                task,
                namespace=namespace,
                prepared=prepared,
            )
            queries = [str(turn["query"]) for turn in task.turns]
            timeout_seconds = max(
                args.timeout,
                (
                    (case.budgets.max_wall_ms or 0) * len(queries)
                    + 120_000
                )
                // 1000,
            )
            conversation_dir = (
                attempt_dir / "traces" / task.id
            )
            conversation = run_java_conversation(
                case,
                queries,
                conversation_dir,
                mode="live",
                profile=args.profile,
                tool_mode="production",
                workspace_root=prepared.workspace,
                max_turns=len(queries),
                independent_turns=True,
                build=False,
                timeout_seconds=timeout_seconds,
            )
            trace = (
                conversation.traces[-1]
                if conversation.traces
                else RunTrace.from_dict(
                    {
                        "run": {
                            "caseId": task.id,
                            "requestId": task.id,
                            "status": "FAILED",
                            "finalSummaryText": "",
                            "totalTokens": None,
                            "durationMs": None,
                        },
                        "llmInvocations": [],
                        "toolInvocations": [],
                        "artifacts": [],
                    }
                )
            )
            run = adapter.collect(
                task,
                trace,
                f"{attempt_relative}/traces/{task.id}/result.json",
                prepared=prepared,
                turn_results=conversation.turns,
                traces=conversation.traces,
            )
            result = adapter.score(run)
            result.attempt = attempt
            results.append(result)
    return _report_benchmark(
        adapter=adapter,
        args=args,
        results=results,
        output_dir=output_dir,
        mode=mode,
        repeat=repeat,
        require_official=True,
    )


def _run_sqlite_benchmark(args: argparse.Namespace, adapter) -> int:
    if args.profile == "pr":
        raise ValueError(
            f"{adapter.name} official benchmark requires nightly or release"
        )
    tasks = adapter.tasks(args.split)
    mode, repeat, output_dir = _benchmark_settings(args)
    if mode != "live":
        raise ValueError(
            f"{adapter.name} official benchmark requires live model mode"
        )
    if not args.skip_build:
        compile_java_fixture_runner()
    results: list[CaseResult] = []
    for attempt in range(1, repeat + 1):
        attempt_relative = f"attempts/{attempt:02d}"
        attempt_dir = output_dir / attempt_relative
        for task in tasks:
            prepared = adapter.prepare(
                task,
                attempt_dir / "workspace" / task.id,
            )
            server = prepared.metadata["taskServer"]
            try:
                case = adapter.case_for_task(prepared.task)
                traces = run_java_cases(
                    [case],
                    attempt_dir,
                    mode=mode,
                    profile=args.profile,
                    build=False,
                    timeout_seconds=args.timeout,
                    runner_class=(
                        "org.wwz.ai.test.domain.EvalBenchmarkLiveRunnerCli"
                    ),
                    workspace_roots={task.id: prepared.workspace},
                    tool_mode="ephemeral",
                    tool_endpoint=server.endpoint,
                )
                trace = traces.get(task.id)
                if trace is None:
                    raise ValueError(f"Java runner returned no trace for {task.id}")
                run = adapter.collect(
                    prepared.task,
                    trace,
                    f"{attempt_relative}/traces/{task.id}/trace.json",
                    prepared=prepared,
                )
                result = adapter.score(run)
                result.attempt = attempt
                results.append(result)
            finally:
                server.close()
    return _report_benchmark(
        adapter=adapter,
        args=args,
        results=results,
        output_dir=output_dir,
        mode=mode,
        repeat=repeat,
        require_official=True,
    )


def _run_bird_benchmark(args: argparse.Namespace) -> int:
    adapter = BirdAdapter(
        Path(args.manifest).resolve()
        if args.manifest
        else Path(__file__).resolve().parent
        / "benchmarks"
        / "bird"
        / "manifest.yaml",
        data_home=Path(args.data_home).resolve() if args.data_home else None,
    )
    return _run_sqlite_benchmark(args, adapter)


def _run_spider2_benchmark(args: argparse.Namespace) -> int:
    adapter = Spider2Adapter(
        Path(args.manifest).resolve()
        if args.manifest
        else Path(__file__).resolve().parent
        / "benchmarks"
        / "spider2"
        / "manifest.yaml",
        data_home=Path(args.data_home).resolve() if args.data_home else None,
    )
    return _run_sqlite_benchmark(args, adapter)


def benchmark_command(args: argparse.Namespace) -> int:
    if args.name == "gaia":
        return _run_gaia_benchmark(args)
    if args.name == "deepresearch":
        return _run_deepresearch_benchmark(args)
    if args.name == "rag-python":
        return _run_rag_python_benchmark(args)
    if args.name == "tau2":
        return _run_tau2_benchmark(args)
    if args.name == "longmemeval":
        return _run_longmemeval_benchmark(args)
    if args.name == "memoryagentbench":
        return _run_memoryagentbench_benchmark(args)
    if args.name == "bird":
        return _run_bird_benchmark(args)
    if args.name == "spider2":
        return _run_spider2_benchmark(args)
    raise ValueError(f"unsupported benchmark: {args.name}")


def process_view_command(args: argparse.Namespace) -> int:
    trace_path = Path(args.trace).resolve()
    payload = json.loads(trace_path.read_text(encoding="utf-8"))
    source = args.source
    if source == "auto":
        source = "conversation" if payload.get("conversation") else "ledger"
    if source == "conversation":
        view = project_process_view(payload)
    else:
        view = project_ledger_process_view(payload)
    output_path = Path(args.out).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(view.to_dict(), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(
        f"process view: steps={len(view.steps)} "
        f"messages={len(view.messages)}"
    )
    print(f"output: {output_path}")
    return 0


def trajectory_quality_command(args: argparse.Namespace) -> int:
    model = args.judge_model or os.environ.get("CUTE_EVAL_JUDGE_MODEL")
    base_url = args.judge_base_url or os.environ.get("CUTE_EVAL_JUDGE_BASE_URL")
    api_key = args.judge_api_key or os.environ.get("CUTE_EVAL_JUDGE_API_KEY")
    if not model or not base_url or not api_key:
        raise ValueError(
            "trajectory-quality requires judge model, base URL, and API key"
        )
    trace_paths = resolve_trace_paths(
        args.trace,
        Path(args.manifest).resolve() if args.manifest else None,
    )
    apb_source = (
        Path(args.agent_process_bench_source).resolve()
        if args.agent_process_bench_source
        else None
    )
    trail_source = (
        Path(args.trail_source).resolve()
        if args.trail_source
        else None
    )
    report = run_trajectory_quality(
        trace_paths,
        output_dir=Path(args.out).resolve(),
        config=QualityJudgeConfig(
            model=model,
            base_url=base_url,
            api_key=api_key,
            timeout_seconds=args.timeout,
        ),
        apb_source_dir=apb_source,
        trail_source_dir=trail_source,
        concurrency=args.concurrency,
    )
    print(
        f"trajectory quality: runs={report['metrics']['runCount']} "
        f"stepVerified={report['metrics']['stepQualityVerifiedRuns']} "
        f"rootCauseVerified={report['metrics']['rootCauseVerifiedRuns']}"
    )
    print(f"report: {Path(args.out).resolve() / 'report.json'}")
    return 0


def multi_agent_view_command(args: argparse.Namespace) -> int:
    trace_path = Path(args.trace).resolve()
    payload = json.loads(trace_path.read_text(encoding="utf-8"))
    view = project_multi_agent_view(payload)
    output_path = Path(args.out).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(view.to_dict(), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(
        f"multi-agent view: agents={len(view.agents)} "
        f"handoffs={len(view.handoffs)} tools={view.metrics['toolCount']}"
    )
    print(f"output: {output_path}")
    return 0


def multiagent_benchmark_command(args: argparse.Namespace) -> int:
    if args.name != "multiagentbench-marble":
        raise ValueError(f"unsupported multi-agent benchmark: {args.name}")
    source_dir = (
        Path(args.source_dir).resolve()
        if args.source_dir
        else (
            Path(os.environ["MARBLE_SOURCE"]).resolve()
            if os.environ.get("MARBLE_SOURCE")
            else None
        )
    )
    if source_dir is None:
        raise ValueError("MARBLE requires --source-dir or MARBLE_SOURCE")
    adapter = MarbleAdapter(
        source_dir=source_dir,
        manifest_path=(
            Path(args.manifest).resolve()
            if args.manifest
            else Path(__file__).resolve().parent
            / "benchmarks"
            / "marble"
            / "manifest.yaml"
        ),
    )
    assets = adapter.verify_assets()
    tasks = adapter.tasks(args.split)
    output_dir = Path(args.out).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    if args.dry_run:
        report = {
            "schemaVersion": "1.0",
            "subject": {"type": "multi-agent-system", "agent": AGENT_ID},
            "benchmark": {
                **adapter.report_metadata(),
                "datasetHash": adapter.dataset_hash(assets),
            },
            "scoreNamespace": adapter.score_namespace,
            "scoreLabel": adapter.score_label,
            "tasks": [
                {
                    "taskId": task.id,
                    "domain": task.domain,
                    "officialTaskId": task.task_id,
                    "scenario": task.scenario,
                    "agentCount": task.agent_count,
                }
                for task in tasks
            ],
            "metrics": {
                "split": args.split,
                "taskCount": len(tasks),
                "domainCounts": {
                    domain: sum(1 for task in tasks if task.domain == domain)
                    for domain in sorted({task.domain for task in tasks})
                },
            },
            "environment": {"dryRun": True, "assets": assets},
        }
        report_path = output_dir / "dry-run.json"
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(
            f"MARBLE dry-run: tasks={len(tasks)} "
            f"license={adapter.license_status}"
        )
        print(f"report: {report_path}")
        return 0
    python_executable = (
        Path(args.python).resolve()
        if args.python
        else (
            Path(os.environ["MARBLE_PYTHON"]).resolve()
            if os.environ.get("MARBLE_PYTHON")
            else None
        )
    )
    if python_executable is None:
        raise ValueError(
            "MARBLE run requires --python or MARBLE_PYTHON "
            "pointing to an installed MARBLE environment"
        )
    try:
        report = run_marble(
            adapter,
            MarbleRunConfig(
                python_executable=python_executable,
                domain=args.domain,
                task_ids=tuple(args.task_id or [1]),
                llm_model=args.llm_model,
                output_dir=output_dir,
                timeout_seconds=args.timeout,
                compatibility_local=args.compatibility_local,
                repair_evaluator_syntax=args.repair_evaluator_syntax,
                offline_tools=args.offline_tools,
            ),
        )
    except MarbleRunBlocked as exc:
        blocked = {
            "schemaVersion": "1.0",
            "subject": {
                "type": "multi-agent-system",
                "name": "MARBLE BaseAgent",
                "model": args.llm_model,
            },
            "benchmark": adapter.report_metadata(),
            "scoreNamespace": "agent-benchmark",
            "scoreLabel": "blocked-official-source",
            "usagePolicy": {
                "mode": "blocked",
                "officialScore": False,
                "publicationAllowed": False,
                "gateEligible": False,
                "advisory": True,
            },
            "blocked": {"message": str(exc), **exc.details},
        }
        blocked_path = output_dir / "blocked.json"
        blocked_path.write_text(
            json.dumps(blocked, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"MARBLE blocked: {exc}")
        print(f"report: {blocked_path}")
        return 2
    print(
        f"MARBLE {report['scoreLabel']}: "
        f"tasks={report['metrics']['taskCount']} "
        f"verified={report['metrics']['verifiedTasks']} "
        f"unverified={report['metrics']['unverifiedTasks']}"
    )
    print(f"report: {output_dir / 'report.json'}")
    return 0


def judge_benchmark_command(args: argparse.Namespace) -> int:
    if args.name == "trail":
        return _trail_judge_benchmark_command(args)
    if args.name == "agent-reward-bench":
        return _agent_reward_bench_command(args)
    if args.name != "agent-process-bench":
        raise ValueError(f"unsupported judge benchmark: {args.name}")
    source_dir = (
        Path(args.source_dir).resolve()
        if args.source_dir
        else (
            Path(os.environ["AGENT_PROCESS_BENCH_SOURCE"]).resolve()
            if os.environ.get("AGENT_PROCESS_BENCH_SOURCE")
            else None
        )
    )
    if source_dir is None:
        raise ValueError(
            "AgentProcessBench requires --source-dir or "
            "AGENT_PROCESS_BENCH_SOURCE"
        )
    adapter = AgentProcessBenchAdapter(
        source_dir=source_dir,
        manifest_path=(
            Path(args.manifest).resolve()
            if args.manifest
            else Path(__file__).resolve().parent
            / "judge_benchmarks"
            / "agent_process_bench"
            / "manifest.yaml"
        ),
    )
    assets = adapter.verify_assets()
    tasks = adapter.tasks(args.split)
    output_dir = Path(args.out).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    if args.dry_run:
        report = {
            "schemaVersion": "1.0",
            "subject": {"type": "judge", "model": args.judge_model},
            "benchmark": {
                **adapter.report_metadata(),
                "datasetHash": adapter.dataset_hash(assets),
            },
            "scoreNamespace": adapter.score_namespace,
            "scoreLabel": adapter.score_label,
            "tasks": [
                {
                    "taskId": task.id,
                    "category": task.category,
                    "assistantSteps": len(task.assistant_message_indices),
                }
                for task in tasks
            ],
            "metrics": {
                "split": args.split,
                "taskCount": len(tasks),
                "datasetCounts": {
                    dataset: sum(
                        1 for task in tasks if task.category == dataset
                    )
                    for dataset in sorted(
                        {task.category for task in tasks}
                    )
                },
            },
            "environment": {"dryRun": True, "assets": assets},
        }
        report_path = output_dir / "dry-run.json"
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(
            f"judge benchmark dry-run: tasks={len(tasks)} "
            f"license={adapter.license_status}"
        )
        print(f"report: {report_path}")
        return 0

    if not (args.judge_model and args.judge_base_url and args.judge_api_key):
        raise ValueError(
            "judge benchmark requires --judge-model, --judge-base-url "
            "and --judge-api-key"
        )
    report = adapter.run(
        OpenAICompatibleJudge(
            model=args.judge_model,
            base_url=args.judge_base_url,
            api_key=args.judge_api_key,
            max_tokens=65536,
        ),
        split=args.split,
        output_dir=output_dir,
        concurrency=args.concurrency,
        timeout_seconds=args.timeout,
    )
    average = report["metrics"]["datasets"]["AVG"]
    print(
        f"judge benchmark: tasks={len(report['tasks'])} "
        f"step_micro_acc={average['stepMicroAccuracy']} "
        f"firsterroracc={average['firstErrorAccuracy']}"
    )
    print(f"report: {output_dir / 'report.json'}")
    return 0


def _agent_reward_bench_command(args: argparse.Namespace) -> int:
    source_dir = (
        Path(args.source_dir).resolve()
        if args.source_dir
        else (
            Path(os.environ["AGENT_REWARD_BENCH_SOURCE"]).resolve()
            if os.environ.get("AGENT_REWARD_BENCH_SOURCE")
            else None
        )
    )
    if source_dir is None:
        raise ValueError(
            "AgentRewardBench requires --source-dir or "
            "AGENT_REWARD_BENCH_SOURCE"
        )
    trajectory_dir = (
        Path(args.trajectory_dir).resolve()
        if args.trajectory_dir
        else (
            Path(os.environ["AGENT_REWARD_BENCH_TRAJECTORIES"]).resolve()
            if os.environ.get("AGENT_REWARD_BENCH_TRAJECTORIES")
            else None
        )
    )
    adapter = AgentRewardBenchAdapter(
        source_dir=source_dir,
        manifest_path=(
            Path(args.manifest).resolve()
            if args.manifest
            else Path(__file__).resolve().parent
            / "judge_benchmarks"
            / "agent_reward_bench"
            / "manifest.yaml"
        ),
        trajectory_dir=trajectory_dir,
    )
    assets = adapter.verify_assets()
    tasks = adapter.tasks(args.split)
    output_dir = Path(args.out).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    if args.dry_run:
        report = {
            "schemaVersion": "1.0",
            "subject": {"type": "judge", "model": args.judge_model},
            "benchmark": {
                **adapter.report_metadata(),
                "datasetHash": adapter.dataset_hash(assets),
            },
            "scoreNamespace": adapter.score_namespace,
            "scoreLabel": adapter.score_label,
            "tasks": [
                {
                    "taskId": task.id,
                    "benchmark": task.benchmark,
                    "modelName": task.model_name,
                    "task": task.task_id,
                    "annotatorCount": task.annotator_count,
                    "labels": task.labels,
                }
                for task in tasks
            ],
            "metrics": {
                "split": args.split,
                "taskCount": len(tasks),
                "annotationRows": sum(
                    task.annotator_count for task in tasks
                ),
                "benchmarkCounts": {
                    benchmark: sum(
                        1 for task in tasks if task.benchmark == benchmark
                    )
                    for benchmark in sorted(
                        {task.benchmark for task in tasks}
                    )
                },
            },
            "environment": {
                "dryRun": True,
                "assets": assets,
                "trajectoryDir": str(trajectory_dir)
                if trajectory_dir
                else None,
                "judgeVariant": args.judge_variant,
            },
        }
        report_path = output_dir / "dry-run.json"
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(
            f"AgentRewardBench dry-run: tasks={len(tasks)} "
            f"license={adapter.license_status}"
        )
        print(f"report: {report_path}")
        return 0

    if not (args.judge_model and args.judge_base_url and args.judge_api_key):
        raise ValueError(
            "AgentRewardBench requires --judge-model, --judge-base-url "
            "and --judge-api-key"
        )
    if trajectory_dir is None:
        raise ValueError(
            "AgentRewardBench real run requires --trajectory-dir or "
            "AGENT_REWARD_BENCH_TRAJECTORIES"
        )
    report = adapter.run(
        OpenAICompatibleJudge(
            model=args.judge_model,
            base_url=args.judge_base_url,
            api_key=args.judge_api_key,
            max_tokens=65536,
        ),
        split=args.split,
        output_dir=output_dir,
        judge_variant=args.judge_variant,
        concurrency=args.concurrency,
        timeout_seconds=args.timeout,
    )
    print(
        f"AgentRewardBench: tasks={len(report['tasks'])} "
        f"scoreLabel={report['scoreLabel']}"
    )
    print(f"report: {output_dir / 'report.json'}")
    return 0


def _trail_judge_benchmark_command(args: argparse.Namespace) -> int:
    source_dir = (
        Path(args.source_dir).resolve()
        if args.source_dir
        else (
            Path(os.environ["TRAIL_SOURCE"]).resolve()
            if os.environ.get("TRAIL_SOURCE")
            else None
        )
    )
    if source_dir is None:
        raise ValueError("TRAIL requires --source-dir or TRAIL_SOURCE")
    adapter = TrailAdapter(
        source_dir=source_dir,
        manifest_path=(
            Path(args.manifest).resolve()
            if args.manifest
            else Path(__file__).resolve().parent
            / "judge_benchmarks"
            / "trail"
            / "manifest.yaml"
        ),
    )
    assets = adapter.verify_assets()
    tasks = adapter.tasks(args.split)
    output_dir = Path(args.out).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    if args.dry_run:
        report = {
            "schemaVersion": "1.0",
            "subject": {"type": "judge", "model": args.judge_model},
            "benchmark": {
                **adapter.report_metadata(),
                "datasetHash": adapter.dataset_hash(assets),
            },
            "scoreNamespace": adapter.score_namespace,
            "scoreLabel": adapter.score_label,
            "tasks": [
                {
                    "taskId": task.id,
                    "domain": task.domain,
                }
                for task in tasks
            ],
            "metrics": {
                "split": args.split,
                "taskCount": len(tasks),
                "domainCounts": {
                    domain: sum(1 for task in tasks if task.domain == domain)
                    for domain in sorted({task.domain for task in tasks})
                },
            },
            "environment": {"dryRun": True, "assets": assets},
        }
        report_path = output_dir / "dry-run.json"
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(
            f"TRAIL dry-run: tasks={len(tasks)} "
            f"license={adapter.license_status}"
        )
        print(f"report: {report_path}")
        return 0

    if not (args.judge_model and args.judge_base_url and args.judge_api_key):
        raise ValueError(
            "TRAIL requires --judge-model, --judge-base-url and --judge-api-key"
        )
    report = adapter.run(
        OpenAICompatibleJudge(
            model=args.judge_model,
            base_url=args.judge_base_url,
            api_key=args.judge_api_key,
            max_tokens=65536,
        ),
        split=args.split,
        output_dir=output_dir,
        concurrency=args.concurrency,
    )
    print(
        f"TRAIL: tasks={len(report['tasks'])} "
        f"domains={len(report['metrics']['domains'])}"
    )
    print(f"report: {output_dir / 'report.json'}")
    return 0


def compactbench_command(args: argparse.Namespace) -> int:
    adapter = CompactBenchAdapter(
        Path(args.manifest).resolve() if args.manifest else Path(__file__).resolve().parent
        / "benchmarks"
        / "compactbench"
        / "manifest.yaml"
    )
    output_path = Path(args.out).resolve()
    traces_path = Path(args.traces).resolve() if args.traces else None
    source = (
        Path(args.source).resolve()
        if args.source
        else (
            Path(os.environ["COMPACTBENCH_SOURCE"]).resolve()
            if os.environ.get("COMPACTBENCH_SOURCE")
            else None
        )
    )
    result = adapter.run(
        source=source,
        suite=args.suite,
        output_path=output_path,
        traces_path=traces_path,
        budget=args.budget,
        cases=args.cases,
        drift_cycles=args.drift_cycles,
        controls=args.controls,
        reader_provider=args.reader_provider,
        reader_model=args.reader_model,
    )
    print(f"compactbench exit={result.returncode}")
    print(f"results: {output_path}")
    if traces_path is not None:
        print(f"traces: {traces_path}")
    return result.returncode


def ab_run_command(args: argparse.Namespace) -> int:
    report = run_experiment(
        Path(args.experiment).resolve(),
        Path(args.out).resolve(),
    )
    print(
        f"cute-evals ab: experiment={report['experimentId']} "
        f"cells={report['summary']['actualCellCount']}/"
        f"{report['summary']['expectedCellCount']} "
        f"recommendation={report['recommendation']}"
    )
    print(f"report: {Path(args.out).resolve() / 'report.html'}")
    return exit_code_for(report)


def ab_resume_command(args: argparse.Namespace) -> int:
    report = resume_experiment(Path(args.out).resolve())
    print(
        f"cute-evals ab resume: cells={report['summary']['actualCellCount']}/"
        f"{report['summary']['expectedCellCount']} "
        f"recommendation={report['recommendation']}"
    )
    print(f"report: {Path(args.out).resolve() / 'report.html'}")
    return exit_code_for(report)


def ab_report_command(args: argparse.Namespace) -> int:
    report = report_experiment(Path(args.out).resolve())
    print(
        f"cute-evals ab report: recommendation={report['recommendation']} "
        f"anomalies={len(report['anomalies'])}"
    )
    print(f"report: {Path(args.out).resolve() / 'report.html'}")
    return exit_code_for(report)


def ab_audit_command(args: argparse.Namespace) -> int:
    payload = audit_cell(Path(args.out).resolve(), args.cell)
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cute-evals")
    subparsers = parser.add_subparsers(dest="command", required=True)
    run = subparsers.add_parser("run", help="run an evaluation suite")
    run.add_argument("--profile", choices=["pr", "nightly", "release"], default="pr")
    run.add_argument(
        "--suite",
        choices=[
            "all",
            "react-core",
            "rag-core",
            "data-core",
            "production-core",
            "holdout-core",
            "skill-sim",
            *AB_SUITES,
        ],
        default="all",
    )
    run.add_argument("--cases-dir")
    run.add_argument("--out")
    run.add_argument("--skip-build", action="store_true")
    run.add_argument(
        "--java-mode",
        choices=["auto", "fixture", "live", "benchmark", "compaction"],
        default="auto",
        help="fixture=deterministic model script; live=real configured model",
    )
    run.add_argument(
        "--engine",
        choices=["auto", "inspect", "native"],
        default="auto",
        help="Inspect AI is the default runner when installed",
    )
    run.add_argument("--repeat", type=int)
    run.add_argument("--timeout", type=int, default=600)
    run.add_argument("--judge-model")
    run.add_argument("--judge-base-url")
    run.add_argument("--judge-api-key")
    run.add_argument("--judge-cache-dir")
    run.add_argument("--baseline")
    run.add_argument("--update-baseline", action="store_true")
    run.add_argument(
        "--require-judge",
        action="store_true",
        help="treat hard-gated judge unverified results as a CI failure",
    )
    run.set_defaults(func=run_command)

    draft = subparsers.add_parser(
        "case-draft",
        help="turn a sanitized real Trace into a private badcase draft",
    )
    draft.add_argument("--trace", required=True)
    draft.add_argument("--out", required=True)
    draft.add_argument(
        "--failure-class",
        choices=["model", "tool", "environment", "persistence", "unknown"],
    )
    draft.set_defaults(func=case_draft_command)

    approve = subparsers.add_parser(
        "case-approve",
        help="approve a reviewed badcase draft for regression replay",
    )
    approve.add_argument("--draft", required=True)
    approve.add_argument("--out", required=True)
    approve.add_argument("--reviewer", required=True)
    approve.add_argument("--notes")
    approve.add_argument(
        "--advisory",
        action="store_true",
        help="keep the approved case advisory instead of a hard gate",
    )
    approve.add_argument("--force", action="store_true")
    approve.set_defaults(func=case_approve_command)

    trace = subparsers.add_parser(
        "trace-replay",
        help="re-score an existing Trace without rerunning the agent",
    )
    trace.add_argument("--case", required=True)
    trace.add_argument("--trace", required=True)
    trace.add_argument("--out", required=True)
    trace.add_argument("--profile", choices=["pr", "nightly", "release"], default="pr")
    trace.add_argument("--judge-model")
    trace.add_argument("--judge-base-url")
    trace.add_argument("--judge-api-key")
    trace.add_argument("--require-judge", action="store_true")
    trace.set_defaults(func=trace_replay_command)

    private = subparsers.add_parser(
        "private-replay",
        help="replay all private badcases from their recorded baseline Traces",
    )
    private.add_argument("--cases-dir")
    private.add_argument("--out")
    private.add_argument("--judge-model")
    private.add_argument("--judge-base-url")
    private.add_argument("--judge-api-key")
    private.add_argument("--require-judge", action="store_true")
    private.set_defaults(func=private_replay_command)

    counterfactual = subparsers.add_parser(
        "counterfactual-replay",
        help="rerun the same case with another model and compare metrics",
    )
    counterfactual.add_argument("--case", required=True)
    counterfactual.add_argument("--baseline-trace", required=True)
    counterfactual.add_argument("--out", required=True)
    counterfactual.add_argument(
        "--java-mode",
        choices=["fixture", "live"],
        default="live",
    )
    counterfactual.add_argument("--timeout", type=int, default=600)
    counterfactual.set_defaults(func=counterfactual_command)

    export = subparsers.add_parser(
        "ledger-export",
        help="read-only export of completed runs from Execution Ledger",
    )
    export.add_argument("--run-id", "--request-id", dest="run_id")
    export.add_argument("--session-id")
    export.add_argument("--from", dest="from_time")
    export.add_argument("--to", dest="to_time")
    export.add_argument("--out", required=True)
    export.add_argument("--limit", type=int, default=50)
    export.add_argument(
        "--status",
        help="comma-separated completed statuses: SUCCESS,FAILED,TIMEOUT,STOPPED",
    )
    export.add_argument("--spring-profile", default="eval")
    export.set_defaults(func=ledger_export_command)

    process_view = subparsers.add_parser(
        "process-view",
        help="project a ledger trace into the neutral step-oriented view",
    )
    process_view.add_argument("--trace", required=True)
    process_view.add_argument("--out", required=True)
    process_view.add_argument(
        "--source",
        choices=["auto", "conversation", "ledger"],
        default="auto",
    )
    process_view.set_defaults(func=process_view_command)

    trajectory_quality = subparsers.add_parser(
        "trajectory-quality",
        help="score a real Execution Ledger trace with calibrated judges",
    )
    trajectory_quality.add_argument("--trace", action="append")
    trajectory_quality.add_argument("--manifest")
    trajectory_quality.add_argument("--out", required=True)
    trajectory_quality.add_argument("--judge-model")
    trajectory_quality.add_argument("--judge-base-url")
    trajectory_quality.add_argument("--judge-api-key")
    trajectory_quality.add_argument("--agent-process-bench-source")
    trajectory_quality.add_argument("--trail-source")
    trajectory_quality.add_argument("--concurrency", type=int, default=1)
    trajectory_quality.add_argument("--timeout", type=int, default=300)
    trajectory_quality.set_defaults(func=trajectory_quality_command)

    multi_agent_view = subparsers.add_parser(
        "multi-agent-view",
        help="project a ledger trace into a main/sub-agent ownership view",
    )
    multi_agent_view.add_argument("--trace", required=True)
    multi_agent_view.add_argument("--out", required=True)
    multi_agent_view.set_defaults(func=multi_agent_view_command)

    multiagent_benchmark = subparsers.add_parser(
        "multiagent-benchmark",
        help="inspect a pinned multi-agent benchmark",
    )
    multiagent_benchmark.add_argument(
        "--name",
        choices=["multiagentbench-marble"],
        default="multiagentbench-marble",
    )
    multiagent_benchmark.add_argument(
        "--split",
        choices=["smoke", "official-subset", "official-full"],
        default="smoke",
    )
    multiagent_benchmark.add_argument("--source-dir")
    multiagent_benchmark.add_argument("--manifest")
    multiagent_benchmark.add_argument("--out", required=True)
    multiagent_benchmark.add_argument("--python")
    multiagent_benchmark.add_argument(
        "--domain",
        default="research",
        help="current executable domain: research",
    )
    multiagent_benchmark.add_argument(
        "--task-id",
        type=int,
        action="append",
        help="official task id; defaults to 1",
    )
    multiagent_benchmark.add_argument(
        "--llm-model",
        default="openai/group/deepseek",
    )
    multiagent_benchmark.add_argument(
        "--compatibility-local",
        action="store_true",
        help="allow local compatibility execution without changing the manifest",
    )
    multiagent_benchmark.add_argument(
        "--repair-evaluator-syntax",
        action="store_true",
        help="apply the known indentation-only evaluator repair in memory",
    )
    multiagent_benchmark.add_argument(
        "--offline-tools",
        action="store_true",
        help="disable external research tools for an offline compatibility run",
    )
    multiagent_benchmark.add_argument("--timeout", type=int, default=1800)
    multiagent_benchmark.add_argument("--dry-run", action="store_true")
    multiagent_benchmark.set_defaults(func=multiagent_benchmark_command)

    judge_benchmark = subparsers.add_parser(
        "judge-benchmark",
        help="run a pinned benchmark that evaluates a judge model",
    )
    judge_benchmark.add_argument(
        "--name",
        choices=["agent-process-bench", "agent-reward-bench", "trail"],
        required=True,
    )
    judge_benchmark.add_argument(
        "--split",
        choices=["smoke", "dev", "test", "official-subset", "official-full"],
        default="smoke",
    )
    judge_benchmark.add_argument("--source-dir")
    judge_benchmark.add_argument("--trajectory-dir")
    judge_benchmark.add_argument("--manifest")
    judge_benchmark.add_argument("--out", required=True)
    judge_benchmark.add_argument("--judge-model")
    judge_benchmark.add_argument("--judge-base-url")
    judge_benchmark.add_argument("--judge-api-key")
    judge_benchmark.add_argument("--concurrency", type=int, default=1)
    judge_benchmark.add_argument("--timeout", type=int, default=300)
    judge_benchmark.add_argument(
        "--judge-variant",
        choices=["axtree", "screen", "both", "none"],
        default="axtree",
    )
    judge_benchmark.add_argument("--dry-run", action="store_true")
    judge_benchmark.set_defaults(func=judge_benchmark_command)

    benchmark = subparsers.add_parser(
        "benchmark",
        help="run a pinned public benchmark through its official evaluator",
    )
    benchmark.add_argument(
        "--name",
        choices=[
            "bird",
            "deepresearch",
            "gaia",
            "longmemeval",
            "memoryagentbench",
            "rag-python",
            "spider2",
            "tau2",
        ],
        default="gaia",
    )
    benchmark.add_argument(
        "--split",
        choices=["official-subset", "official-full", "internal"],
        default="official-subset",
    )
    benchmark.add_argument("--profile", choices=["pr", "nightly", "release"], default="pr")
    benchmark.add_argument(
        "--java-mode",
        choices=["auto", "fixture", "live"],
        default="auto",
    )
    benchmark.add_argument("--repeat", type=int)
    benchmark.add_argument("--timeout", type=int, default=600)
    benchmark.add_argument("--out")
    benchmark.add_argument("--manifest")
    benchmark.add_argument("--data-home")
    benchmark.add_argument(
        "--data-file",
        help="explicit official LongMemEval JSON file",
    )
    benchmark.add_argument(
        "--dataset-file",
        default="longmemeval_oracle.json",
        help="pinned LongMemEval JSON file name",
    )
    benchmark.add_argument("--judge-model")
    benchmark.add_argument("--judge-base-url")
    benchmark.add_argument("--judge-api-key")
    benchmark.add_argument("--clean-model")
    benchmark.add_argument("--fact-model")
    benchmark.add_argument("--jina-api-key")
    benchmark.add_argument("--target-model", default="cute-agent")
    benchmark.add_argument(
        "--deepsearch-url",
        default="http://127.0.0.1:1601",
    )
    benchmark.add_argument("--deepsearch-timeout", type=int, default=1800)
    benchmark.add_argument("--deepsearch-max-loop", type=int, default=1)
    benchmark.add_argument(
        "--mrag-url",
        default="http://127.0.0.1:1601",
    )
    benchmark.add_argument("--mrag-timeout", type=int, default=600)
    benchmark.add_argument("--mrag-kb-id")
    benchmark.add_argument(
        "--require-judge",
        action="store_true",
        help="treat hard-gated judge unverified results as a CI failure",
    )
    benchmark.add_argument(
        "--search-engines",
        help="comma-separated DeepSearch engines; empty uses the Python service default",
    )
    benchmark.add_argument("--source-dir")
    benchmark.add_argument("--limit", type=int)
    benchmark.add_argument("--official-max-workers", type=int, default=10)
    benchmark.add_argument("--skip-official-eval", action="store_true")
    benchmark.add_argument("--skip-build", action="store_true")
    benchmark.add_argument("--user-simulator-model")
    benchmark.add_argument("--user-simulator-base-url")
    benchmark.add_argument("--user-simulator-api-key")
    benchmark.set_defaults(func=benchmark_command)

    compactbench = subparsers.add_parser(
        "compactbench",
        help="run CompactBench's official compactor harness",
    )
    compactbench.add_argument("--suite", default="starter")
    compactbench.add_argument("--out", required=True)
    compactbench.add_argument("--traces")
    compactbench.add_argument("--manifest")
    compactbench.add_argument(
        "--source",
        help="CompactBench 0.3 source snapshot (or COMPACTBENCH_SOURCE)",
    )
    compactbench.add_argument("--budget", type=int, default=2048)
    compactbench.add_argument("--cases", type=int)
    compactbench.add_argument("--drift-cycles", type=int)
    compactbench.add_argument("--controls", action="store_true")
    compactbench.add_argument("--reader-provider")
    compactbench.add_argument("--reader-model")
    compactbench.set_defaults(func=compactbench_command)

    ops = subparsers.add_parser(
        "ops",
        help="record evaluation history, trends, matrices and releases",
    )
    ops_sub = ops.add_subparsers(dest="ops_command", required=True)

    ops_record = ops_sub.add_parser("record", help="append a report to history")
    ops_record.add_argument("--report", required=True)
    ops_record.add_argument("--history")
    ops_record.add_argument("--series")
    ops_record.add_argument("--variant")
    ops_record.add_argument("--notes")
    ops_record.add_argument("--release-version")
    ops_record.add_argument("--release-commit")
    ops_record.add_argument("--release-model")
    ops_record.set_defaults(func=ops_record_command)

    ops_trend = ops_sub.add_parser("trend", help="build a historical trend report")
    ops_trend.add_argument("--history")
    ops_trend.add_argument("--out")
    ops_trend.add_argument("--series")
    ops_trend.add_argument("--limit", type=int, default=20)
    ops_trend.add_argument("--max-token-increase", type=float, default=0.20)
    ops_trend.add_argument("--max-duration-increase", type=float, default=0.25)
    ops_trend.add_argument("--fail-on-regression", action="store_true")
    ops_trend.set_defaults(func=ops_trend_command)

    ops_matrix = ops_sub.add_parser(
        "matrix",
        help="aggregate multiple A/B reports into one comparison matrix",
    )
    ops_matrix.add_argument("--reports", nargs="+", required=True)
    ops_matrix.add_argument("--out", required=True)
    ops_matrix.add_argument("--fail-on-block", action="store_true")
    ops_matrix.set_defaults(func=ops_matrix_command)

    ops_release = ops_sub.add_parser(
        "release",
        help="evaluate a report against release gate checks",
    )
    ops_release.add_argument("--report", required=True)
    ops_release.add_argument("--out", required=True)
    ops_release.add_argument("--version", required=True)
    ops_release.add_argument("--commit", required=True)
    ops_release.add_argument("--model", required=True)
    ops_release.add_argument("--min-pass-at-k", type=float, default=0.0)
    ops_release.add_argument("--history")
    ops_release.add_argument("--series")
    ops_release.add_argument("--variant")
    ops_release.set_defaults(func=ops_release_command)

    ops_gate = ops_sub.add_parser(
        "gate",
        help="evaluate multiple eval and A/B reports as one CI gate",
    )
    ops_gate.add_argument("--reports", nargs="+", required=True)
    ops_gate.add_argument("--out", required=True)
    ops_gate.add_argument("--min-pass-at-k", type=float, default=0.0)
    ops_gate.add_argument("--require-ship-ab", action="store_true")
    ops_gate.set_defaults(func=ops_gate_command)

    ops_overfit = ops_sub.add_parser(
        "overfit",
        help="compare validation and private holdout reports for leakage and overfitting",
    )
    ops_overfit.add_argument("--validation-report", required=True)
    ops_overfit.add_argument("--holdout-report", required=True)
    ops_overfit.add_argument("--validation-cases")
    ops_overfit.add_argument("--holdout-cases")
    ops_overfit.add_argument("--out")
    ops_overfit.add_argument("--fail-on-risk", action="store_true")
    ops_overfit.set_defaults(func=ops_overfit_command)

    ops_sample = ops_sub.add_parser(
        "sample",
        help="export, audit and deterministically sample production ledger traces",
    )
    ops_sample.add_argument("--config")
    ops_sample.add_argument("--from", dest="from_time")
    ops_sample.add_argument("--to", dest="to_time")
    ops_sample.add_argument("--out", required=True)
    ops_sample.add_argument("--max-candidates", type=int)
    ops_sample.add_argument("--failure-quota", type=int)
    ops_sample.add_argument("--health-quota", type=int)
    ops_sample.add_argument("--seed", type=int)
    ops_sample.add_argument("--status")
    ops_sample.add_argument("--retention-days", type=int)
    ops_sample.add_argument("--spring-profile", default="eval")
    ops_sample.add_argument("--timeout", type=int, default=600)
    ops_sample.add_argument("--skip-build", action="store_true")
    ops_sample.set_defaults(func=ops_sample_command)

    ops_schedule = ops_sub.add_parser(
        "schedule",
        help="run a long-lived Python daily ledger sampling scheduler",
    )
    ops_schedule.add_argument("--config")
    ops_schedule.add_argument("--out-root")
    ops_schedule.add_argument("--at")
    ops_schedule.add_argument("--timezone")
    enable_group = ops_schedule.add_mutually_exclusive_group()
    enable_group.add_argument(
        "--enable",
        action="store_true",
        help="enable the scheduler for this process",
    )
    enable_group.add_argument(
        "--disable",
        action="store_true",
        help="force the scheduler off even when config enables it",
    )
    ops_schedule.add_argument("--run-now", action="store_true")
    ops_schedule.add_argument("--max-candidates", type=int)
    ops_schedule.add_argument("--failure-quota", type=int)
    ops_schedule.add_argument("--health-quota", type=int)
    ops_schedule.add_argument("--seed", type=int)
    ops_schedule.add_argument("--status")
    ops_schedule.add_argument("--retention-days", type=int)
    ops_schedule.add_argument("--spring-profile", default="eval")
    ops_schedule.add_argument("--timeout", type=int, default=600)
    ops_schedule.add_argument("--skip-build", action="store_true")
    ops_schedule.set_defaults(func=ops_schedule_command)

    ab = subparsers.add_parser(
        "ab",
        help="run and report isolated baseline/candidate experiments",
    )
    ab_sub = ab.add_subparsers(dest="ab_command", required=True)
    ab_run = ab_sub.add_parser("run", help="start or continue an A/B experiment")
    ab_run.add_argument("--experiment", required=True)
    ab_run.add_argument("--out", required=True)
    ab_run.set_defaults(func=ab_run_command)
    ab_resume = ab_sub.add_parser("resume", help="resume an interrupted experiment")
    ab_resume.add_argument("--out", required=True)
    ab_resume.set_defaults(func=ab_resume_command)
    ab_report = ab_sub.add_parser("report", help="rebuild an A/B report")
    ab_report.add_argument("--out", required=True)
    ab_report.set_defaults(func=ab_report_command)
    ab_audit = ab_sub.add_parser("audit", help="inspect one Cell result")
    ab_audit.add_argument("--out", required=True)
    ab_audit.add_argument("--cell", required=True)
    ab_audit.set_defaults(func=ab_audit_command)
    return parser


def main(argv: list[str] | None = None) -> None:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        code = args.func(args)
    except Exception as exc:
        print(f"cute-evals error: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
    raise SystemExit(code)


if __name__ == "__main__":
    main()
