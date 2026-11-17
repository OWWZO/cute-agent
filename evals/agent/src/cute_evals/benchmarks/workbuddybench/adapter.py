from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tomllib
from pathlib import Path
from typing import Any

import yaml

from ...models import (
    BudgetSpec,
    CaseResult,
    EvalCase,
    ExpectedSpec,
    RunTrace,
    ScoreRecord,
)
from ..base import BenchmarkRun, BenchmarkTask, PreparedTask
from .docker_runner import DockerEnvironmentError, WorkBuddyDockerVerifier

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _verify_file(path: Path, expected: str, label: str) -> str:
    if not path.is_file():
        raise FileNotFoundError(f"{label} not found: {path}")
    actual = _sha256_file(path)
    if actual != expected.lower():
        raise ValueError(
            f"{label} hash mismatch: expected {expected.lower()}, got {actual}"
        )
    return actual


def _read_toml(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise FileNotFoundError(f"WorkBuddy Bench TOML not found: {path}")
    try:
        payload = tomllib.loads(path.read_text(encoding="utf-8"))
    except tomllib.TOMLDecodeError as exc:
        raise ValueError(f"invalid WorkBuddy Bench TOML: {path}") from exc
    if not isinstance(payload, dict):
        raise TypeError(f"WorkBuddy Bench TOML must be an object: {path}")
    return payload


def _safe_extract(archive_path: Path, target: Path) -> None:
    root = target.resolve()
    with tarfile.open(archive_path, "r:gz") as archive:
        members = archive.getmembers()
        for member in members:
            if not (
                member.isfile()
                or member.isdir()
                or member.issym()
                or member.islnk()
            ):
                raise ValueError(
                    f"unsupported WorkBuddy workspace archive member: {member.name}"
                )
            destination = (root / member.name).resolve()
            if not destination.is_relative_to(root):
                raise ValueError(
                    f"WorkBuddy workspace archive escapes target: {member.name}"
                )
            if member.issym() or member.islnk():
                link_base = (
                    (root / member.name).parent
                    if member.issym()
                    else root
                )
                link_target = (link_base / member.linkname).resolve()
                if not link_target.is_relative_to(root):
                    raise ValueError(
                        "WorkBuddy workspace archive link escapes target: "
                        f"{member.name} -> {member.linkname}"
                    )
        extract_options = {"filter": "data"} if sys.version_info >= (3, 12) else {}
        archive.extractall(root, members=members, **extract_options)


def _git_executable() -> str:
    configured = os.environ.get("WORKBUDDY_GIT")
    if configured:
        return configured
    executable = shutil.which("git.exe") or shutil.which("git")
    if not executable:
        raise RuntimeError("git is required to create a WorkBuddy Bench workspace baseline")
    return executable


def _initialize_git_baseline(workspace: Path) -> str:
    executable = _git_executable()
    commands = [
        [executable, "-C", str(workspace), "init"],
        [executable, "-C", str(workspace), "config", "core.autocrlf", "false"],
        [executable, "-C", str(workspace), "config", "core.eol", "lf"],
        [executable, "-C", str(workspace), "config", "core.longpaths", "true"],
        [executable, "-C", str(workspace), "config", "user.name", "Cute Eval"],
        [
            executable,
            "-C",
            str(workspace),
            "config",
            "user.email",
            "cute-eval@localhost",
        ],
        [executable, "-C", str(workspace), "add", "-A"],
        [
            executable,
            "-C",
            str(workspace),
            "commit",
            "--no-verify",
            "--no-gpg-sign",
            "-m",
            "workbuddy baseline",
        ],
        [executable, "-C", str(workspace), "rev-parse", "HEAD"],
    ]
    for command in commands:
        result = subprocess.run(
            command,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"WorkBuddy Bench workspace git baseline failed: {' '.join(command)}\n"
                f"{result.stderr[-2000:]}"
            )
    return result.stdout.strip()


def _agent_budget_control(run: BenchmarkRun) -> dict[str, Any]:
    configured = run.metadata.get("agentResultPath")
    if not configured:
        return {}
    result_path = Path(str(configured))
    if not result_path.is_file():
        return {}
    try:
        payload = json.loads(result_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    for row in payload.get("cases") or []:
        if str(row.get("caseId") or "") != run.task.id:
            continue
        turns = row.get("turns") or []
        if turns and isinstance(turns[0].get("budgetControl"), dict):
            return dict(turns[0]["budgetControl"])
    return {}


class WorkBuddyBenchAdapter:
    name = "workbuddybench"

    def __init__(
        self,
        manifest_path: Path = DEFAULT_MANIFEST,
        *,
        source_root: Path | None = None,
        docker_verifier: WorkBuddyDockerVerifier | None = None,
    ) -> None:
        self.manifest_path = manifest_path.resolve()
        manifest = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(manifest, dict):
            raise TypeError(f"invalid WorkBuddy Bench manifest: {self.manifest_path}")
        self.manifest = manifest
        self.version = str(manifest["version"])
        self.upstream = str(manifest["upstream"])
        self.score_label = str(manifest["scoreLabel"])
        env_root = os.environ.get("CUTE_EVAL_WORKBUDDY_BENCH_ROOT")
        self.source_root = (
            source_root or (Path(env_root) if env_root else None)
        )
        if self.source_root is None:
            raise ValueError(
                "WorkBuddy Bench source root is required; pass --source-dir or set "
                "CUTE_EVAL_WORKBUDDY_BENCH_ROOT"
            )
        self.source_root = self.source_root.resolve()
        if not self.source_root.is_dir():
            raise FileNotFoundError(
                f"WorkBuddy Bench source root not found: {self.source_root}"
            )
        self.dataset_root = (
            self.source_root
            / "datasets"
            / str(manifest["dataset"]["id"])
        )
        if not self.dataset_root.is_dir():
            raise FileNotFoundError(
                f"WorkBuddy Bench dataset not found: {self.dataset_root}"
            )
        self._tasks: dict[str, list[BenchmarkTask]] = {}
        self._dataset_archive_verified: bool | None = None
        self.docker_verifier = docker_verifier or WorkBuddyDockerVerifier()

    def _verify_dataset_archive(self) -> None:
        if self._dataset_archive_verified is not None:
            return
        archive = (
            self.source_root
            / "datasets"
            / str(self.manifest["dataset"]["archive"])
        )
        if archive.is_file():
            _verify_file(
                archive,
                str(self.manifest["dataset"]["sha256"]),
                "WorkBuddy Bench dataset archive",
            )
            self._dataset_archive_verified = True
            return
        self._dataset_archive_verified = False

    def _task_dir(self, task_id: str) -> Path:
        if Path(task_id).name != task_id or task_id in {".", ".."}:
            raise ValueError(f"invalid WorkBuddy Bench task id: {task_id}")
        path = (
            self.dataset_root
            / str(self.manifest["dataset"]["taskRoot"])
            / task_id
        )
        if not path.is_dir():
            raise FileNotFoundError(f"WorkBuddy Bench task not found: {path}")
        return path

    def discover_pytest_task_ids(self) -> list[str]:
        task_root = self.dataset_root / str(self.manifest["dataset"]["taskRoot"])
        discovered: list[str] = []
        for task_dir in sorted(task_root.iterdir()):
            if not task_dir.is_dir():
                continue
            verifier_path = task_dir / "tests" / "verifier.toml"
            if not verifier_path.is_file():
                continue
            verifier = _read_toml(verifier_path)
            if str(verifier.get("family") or "") == "pytest_injected":
                discovered.append(task_dir.name)
        return discovered

    def _verify_task_coverage(self) -> None:
        discovered = self.discover_pytest_task_ids()
        discovered_pins = [
            str(value)
            for value in self.manifest.get("discoveredTaskIds") or []
        ]
        runnable = [str(value) for value in self.manifest.get("taskIds") or []]
        blocked_payload = self.manifest.get("blockedTaskIds") or {}
        if not isinstance(blocked_payload, dict):
            raise TypeError(
                "WorkBuddy Bench blockedTaskIds must be a mapping"
            )
        blocked = [str(value) for value in blocked_payload]
        expected_runnable = [
            task_id for task_id in discovered if task_id not in set(blocked)
        ]
        pinned_tasks = set(self.manifest.get("tasks") or {})
        missing_pins = sorted(set(discovered) - pinned_tasks)
        extra_pins = sorted(pinned_tasks - set(discovered))
        errors = {
            "discovered": discovered if discovered_pins != discovered else [],
            "missingPins": missing_pins,
            "extraPins": extra_pins,
            "runnable": runnable if runnable != expected_runnable else [],
            "blockedNotDiscovered": sorted(set(blocked) - set(discovered)),
            "runnableBlockedOverlap": sorted(set(runnable) & set(blocked)),
        }
        if not any(errors.values()):
            return
        raise ValueError(
            "WorkBuddy Bench pytest_injected manifest coverage mismatch: "
            f"{errors}"
        )

    def _load_task(self, task_id: str, split: str) -> BenchmarkTask:
        pin = self.manifest["tasks"].get(task_id)
        if not isinstance(pin, dict):
            raise ValueError(f"WorkBuddy Bench task is not pinned: {task_id}")
        task_dir = self._task_dir(task_id)
        instruction_path = task_dir / "instruction.md"
        task_toml_path = task_dir / "task.toml"
        workspace_archive = task_dir / "environment" / "workspace.tar.gz"
        verifier_path = task_dir / "tests" / "verifier.toml"

        instruction_sha = _verify_file(
            instruction_path,
            str(pin["instructionSha256"]),
            f"WorkBuddy Bench instruction {task_id}",
        )
        task_toml_sha = _verify_file(
            task_toml_path,
            str(pin["taskTomlSha256"]),
            f"WorkBuddy Bench task.toml {task_id}",
        )
        workspace_sha = _verify_file(
            workspace_archive,
            str(pin["workspaceSha256"]),
            f"WorkBuddy Bench workspace {task_id}",
        )
        verifier_sha = _verify_file(
            verifier_path,
            str(pin["verifierSha256"]),
            f"WorkBuddy Bench verifier {task_id}",
        )

        task_toml = _read_toml(task_toml_path)
        verifier = _read_toml(verifier_path)
        family = str(verifier.get("family") or "")
        supported = {
            str(value)
            for value in self.manifest.get("supportedVerifierFamilies") or []
        }
        if family not in supported:
            raise ValueError(
                f"unsupported WorkBuddy Bench verifier family for {task_id}: "
                f"{family or '<missing>'}"
            )
        if family != str(pin["verifierFamily"]):
            raise ValueError(
                f"WorkBuddy Bench verifier family changed for {task_id}: "
                f"expected {pin['verifierFamily']}, got {family}"
            )
        run = verifier.get("run")
        if not isinstance(run, dict):
            raise ValueError(f"WorkBuddy Bench verifier run table missing: {task_id}")
        command = str(run.get("command") or "").strip()
        cwd = str(run.get("cwd") or "").strip()
        if not command or not cwd:
            raise ValueError(
                f"WorkBuddy Bench verifier command/cwd missing: {task_id}"
            )
        metadata_table = task_toml.get("metadata")
        if not isinstance(metadata_table, dict):
            metadata_table = {}
        agent_table = task_toml.get("agent")
        if not isinstance(agent_table, dict):
            agent_table = {}
        verifier_table = task_toml.get("verifier")
        if not isinstance(verifier_table, dict):
            verifier_table = {}

        category = str(
            pin.get("category") or metadata_table.get("category") or "unknown"
        )
        metadata = {
            "taskDir": str(task_dir),
            "taskToml": str(task_toml_path),
            "instructionPath": str(instruction_path),
            "instructionSha256": instruction_sha,
            "taskTomlSha256": task_toml_sha,
            "workspaceArchive": str(workspace_archive),
            "workspaceSha256": workspace_sha,
            "verifierPath": str(verifier_path),
            "verifierSha256": verifier_sha,
            "verifierFamily": family,
            "verifierCommand": command,
            "verifierCwd": cwd,
            "agentTimeoutSec": float(agent_table.get("timeout_sec") or 0),
            "verifierTimeoutSec": float(verifier_table.get("timeout_sec") or 0),
            "sourceRepo": metadata_table.get("repo"),
            "sourceCommit": metadata_table.get("commit"),
            "split": split,
        }
        return BenchmarkTask(
            id=task_id,
            category=category,
            question=instruction_path.read_text(encoding="utf-8"),
            functions=[],
            ground_truth=None,
            raw={
                "task": task_toml,
                "verifier": verifier,
            },
            metadata=metadata,
        )

    def tasks(self, split: str) -> list[BenchmarkTask]:
        supported_splits = {
            str(value) for value in self.manifest.get("supportedSplits") or []
        }
        if split not in supported_splits:
            raise ValueError(
                f"unsupported WorkBuddy Bench split in this phase: {split}"
            )
        if split in self._tasks:
            return list(self._tasks[split])
        self._verify_dataset_archive()
        tasks = [
            self._load_task(str(task_id), split)
            for task_id in self.manifest.get("taskIds") or []
        ]
        self._verify_task_coverage()
        self._tasks[split] = tasks
        return list(tasks)

    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask:
        if workspace.exists() and any(workspace.iterdir()):
            raise FileExistsError(f"WorkBuddy Bench workspace is not empty: {workspace}")
        workspace.mkdir(parents=True, exist_ok=True)
        archive = Path(str(task.metadata["workspaceArchive"]))
        _verify_file(
            archive,
            str(task.metadata["workspaceSha256"]),
            f"WorkBuddy Bench workspace {task.id}",
        )
        _safe_extract(archive, workspace)
        if not (workspace / ".git").is_dir():
            _initialize_git_baseline(workspace)
        git_result = subprocess.run(
            [
                _git_executable(),
                "-C",
                str(workspace),
                "rev-parse",
                "HEAD",
            ],
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
        )
        if git_result.returncode != 0:
            raise RuntimeError(
                "WorkBuddy Bench workspace has no usable git baseline: "
                f"{git_result.stderr[-2000:]}"
            )
        git_baseline = git_result.stdout.strip()
        task_attempt_dir = workspace.resolve().parent.parent
        verification_output = (
            task_attempt_dir / "verifier" / task.id
        )
        return PreparedTask(
            task=task,
            workspace=workspace,
            metadata={
                **dict(task.metadata),
                "workspaceRoot": str(workspace.resolve()),
                "dockerContext": str(
                    Path(str(task.metadata["taskDir"])) / "environment"
                ),
                "dockerfile": str(
                    Path(str(task.metadata["taskDir"]))
                    / "environment"
                    / "Dockerfile"
                ),
                "officialTests": str(
                    Path(str(task.metadata["taskDir"])) / "tests"
                ),
                "gitBaseline": git_baseline,
                "verificationOutputDir": str(verification_output),
                "agentResultPath": str(task_attempt_dir / "result.json"),
            },
        )

    def fixture_script(self, task: BenchmarkTask) -> dict[str, Any]:
        return {
            "id": task.id,
            "query": task.question,
            "script": [
                {
                    "type": "final",
                    "content": "WorkBuddy Bench hybrid adapter fixture completed.",
                }
            ],
            "tools": [],
        }

    def case_for_task(self, task: BenchmarkTask) -> EvalCase:
        budgets = self.manifest["budgets"]
        split = str(task.metadata.get("split") or "internal")
        return EvalCase(
            id=task.id,
            suite=f"workbuddybench-{split}",
            version=1,
            hard_gate=True,
            query=task.question,
            expected=ExpectedSpec(max_steps=int(budgets["maxSteps"])),
            budgets=BudgetSpec(
                max_total_tokens=int(budgets["maxTokens"]),
                max_wall_ms=int(budgets["maxWallMs"]),
                max_tool_calls=int(budgets["maxToolCalls"]),
                soft_limit_percent=float(budgets["softLimitPercent"]),
            ),
            script=[],
            source_path=str(task.metadata["taskToml"]),
            metadata={"workbuddyTask": dict(task.metadata)},
        )

    def collect(
        self,
        task: BenchmarkTask,
        trace: RunTrace,
        trace_path: str,
        *,
        prepared: PreparedTask | None = None,
    ) -> BenchmarkRun:
        metadata = prepared.metadata if prepared else dict(task.metadata)
        return BenchmarkRun(
            task=task,
            trace=trace,
            trace_path=trace_path,
            calls=trace.tool_invocations,
            conversation=trace.conversation,
            environment_events=trace.environment_events,
            environment_snapshots=trace.environment_snapshots,
            metadata=dict(metadata),
        )

    def score(self, run: BenchmarkRun, *, case: EvalCase | None = None) -> CaseResult:
        if run.trace.status != "SUCCESS":
            score = ScoreRecord(
                case_id=run.task.id,
                scorer="workbuddybench.official-test",
                name="official_verifier",
                value=None,
                unit="reward",
                passed=None,
                hard_gate=True,
                status="unverified",
                evidence={
                    "failureCategory": "agent_failed",
                    "runStatus": run.trace.status,
                    "errorCode": (run.trace.raw.get("run") or {}).get("errorCode"),
                },
            )
            return CaseResult(
                case=case or self.case_for_task(run.task),
                trace=run.trace,
                scores=[score],
                trace_path=run.trace_path,
            )

        output_dir = Path(str(run.metadata["verificationOutputDir"]))
        try:
            verifier = self.docker_verifier.run(
                task=run.task,
                workspace=Path(str(run.metadata["workspaceRoot"])),
                output_dir=output_dir,
            )
        except DockerEnvironmentError as exc:
            scores = [
                ScoreRecord(
                    case_id=run.task.id,
                    scorer="workbuddybench.official-test",
                    name="official_verifier",
                    value=None,
                    unit="reward",
                    passed=None,
                    hard_gate=True,
                    status="unverified",
                    evidence={
                        "failureCategory": "docker_environment",
                        "error": str(exc),
                        "officialScore": False,
                        "scoreLabel": self.score_label,
                    },
                )
            ]
            return CaseResult(
                case=case or self.case_for_task(run.task),
                trace=run.trace,
                scores=scores,
                trace_path=run.trace_path,
            )
        verified = verifier.status == "verified"
        test_status = str(verifier.score.get("test_status") or "")
        verifier_failed = verified and test_status == "build_error"
        effective_status = (
            "unverified" if not verified or verifier_failed else "verified"
        )
        overall = float(verifier.reward.get("overall") or 0.0)
        passed = (
            None
            if effective_status != "verified"
            else overall >= 1.0 and test_status == "full_pass"
        )
        failure_category = verifier.failure_category
        if verifier_failed:
            failure_category = "verifier_failure"
        elif verified and test_status != "full_pass":
            failure_category = "test_failure"
        evidence = verifier.to_evidence()
        evidence.update(
            {
                "failureCategory": failure_category,
                "testStatus": test_status,
                "officialScore": False,
                "scoreLabel": self.score_label,
                "agentBudgetControl": _agent_budget_control(run),
            }
        )
        scores = [
            ScoreRecord(
                case_id=run.task.id,
                scorer="workbuddybench.official-test",
                name="official_verifier",
                value=overall,
                unit="reward",
                passed=passed,
                hard_gate=True,
                status=effective_status,
                evidence=evidence,
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="workbuddybench.official-test",
                name="test_pass_rate",
                value=verifier.score.get("test_pass_rate"),
                unit="ratio",
                passed=None,
                hard_gate=False,
                status=effective_status,
                evidence={
                    "testsPassed": verifier.score.get("tests_passed"),
                    "testsTotal": verifier.score.get("tests_total"),
                    "testStatus": test_status,
                },
            ),
        ]
        agent_budget = evidence["agentBudgetControl"]
        if agent_budget:
            scores.extend(
                [
                    ScoreRecord(
                        case_id=run.task.id,
                        scorer="workbuddybench.hybrid",
                        name="agent_total_tokens",
                        value=agent_budget.get("totalTokens"),
                        unit="tokens",
                        passed=None,
                        hard_gate=False,
                        status="verified",
                        evidence={
                            "source": "result.json.turns[0].budgetControl",
                            "toolCalls": agent_budget.get("toolCalls"),
                            "wallMs": agent_budget.get("wallMs"),
                        },
                    ),
                    ScoreRecord(
                        case_id=run.task.id,
                        scorer="workbuddybench.hybrid",
                        name="agent_tool_calls",
                        value=agent_budget.get("toolCalls"),
                        unit="count",
                        passed=None,
                        hard_gate=False,
                        status="verified",
                        evidence={
                            "source": "result.json.turns[0].budgetControl",
                        },
                    ),
                ]
            )
        return CaseResult(
            case=case or self.case_for_task(run.task),
            trace=run.trace,
            scores=scores,
            trace_path=run.trace_path,
        )

    def dataset_hash(self) -> str:
        return f"sha256:{self.manifest['dataset']['sha256']}"

    def report_metadata(self) -> dict[str, Any]:
        self._verify_dataset_archive()
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "license": self.manifest["license"],
            "dataset": self.manifest["dataset"]["id"],
            "datasetHash": self.dataset_hash(),
            "datasetArchiveVerified": bool(self._dataset_archive_verified),
            "split": "internal",
            "scoreLabel": self.score_label,
            "runnerMode": self.manifest["runnerMode"],
            "officialScore": False,
            "supportedVerifierFamilies": list(
                self.manifest["supportedVerifierFamilies"]
            ),
            "discoveredTaskIds": list(
                str(value)
                for value in self.manifest.get("discoveredTaskIds") or []
            ),
            "taskIds": [task.id for task in self.tasks("internal")],
            "blockedTaskIds": sorted(
                str(value)
                for value in (
                    self.manifest.get("blockedTaskIds") or {}
                )
            ),
            "blockedTaskDetails": dict(
                self.manifest.get("blockedTaskIds") or {}
            ),
        }
