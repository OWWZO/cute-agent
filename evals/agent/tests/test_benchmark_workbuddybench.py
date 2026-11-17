from __future__ import annotations

import hashlib
import io
import tarfile
from pathlib import Path

import pytest
import yaml

from cute_evals.benchmarks.workbuddybench.adapter import WorkBuddyBenchAdapter
from cute_evals.benchmarks.base import BenchmarkRun
from cute_evals.benchmarks.workbuddybench.docker_runner import (
    DockerEnvironmentError,
    WorkBuddyDockerVerifierResult,
)
from cute_evals.models import RunTrace


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_workspace(path: Path, *, traversal: bool = False) -> None:
    with tarfile.open(path, "w:gz") as archive:
        content = b"class Local:\n    pass\n"
        info = tarfile.TarInfo("asgiref/local.py")
        info.size = len(content)
        archive.addfile(info, io.BytesIO(content))
        if traversal:
            bad = b"escape"
            bad_info = tarfile.TarInfo("../escape.txt")
            bad_info.size = len(bad)
            archive.addfile(bad_info, io.BytesIO(bad))


def _fixture(
    tmp_path: Path,
    *,
    family: str = "pytest_injected",
) -> tuple[Path, Path, dict]:
    source = tmp_path / "source"
    task_id = "bug_fix-easy-a_crash_in_local"
    task_dir = (
        source
        / "datasets"
        / "wb-bench-code-v1.0"
        / "tasks"
        / task_id
    )
    (task_dir / "environment").mkdir(parents=True)
    (task_dir / "tests").mkdir()
    instruction = task_dir / "instruction.md"
    instruction.write_text("Fix Local.__del__ cleanup.\n", encoding="utf-8")
    task_toml = task_dir / "task.toml"
    task_toml.write_text(
        """
schema_version = "1.3"
[metadata]
category = "bug-fix"
repo = "asgiref"
commit = "abc123"
[agent]
timeout_sec = 1800.0
[verifier]
timeout_sec = 600.0
""".strip()
        + "\n",
        encoding="utf-8",
    )
    workspace = task_dir / "environment" / "workspace.tar.gz"
    _write_workspace(workspace)
    verifier = task_dir / "tests" / "verifier.toml"
    verifier.write_text(
        f"""
schema_version = "workbuddy.code_lite.verifier.v1"
family = "{family}"
[run]
cwd = "/workspace"
command = "python -m pytest tests/test_local.py"
""".strip()
        + "\n",
        encoding="utf-8",
    )
    dataset_archive = (
        source / "datasets" / "wb-bench-code-v1.0.tar.gz"
    )
    dataset_archive.write_bytes(b"dataset-archive")
    manifest = {
        "name": "workbuddybench",
        "version": "test-v1",
        "upstream": "https://github.com/Tencent/workbuddy-bench",
        "license": "test-license",
        "scoreLabel": "compatibility-subset",
        "runnerMode": "hybrid-host-agent-docker-verifier",
        "officialScore": False,
        "source": {
            "archive": "workbuddy-bench-main.zip",
            "sha256": "0" * 64,
        },
        "dataset": {
            "id": "wb-bench-code-v1.0",
            "archive": "wb-bench-code-v1.0.tar.gz",
            "sha256": _sha256(dataset_archive),
            "taskRoot": "tasks",
        },
        "supportedSplits": ["internal"],
        "supportedVerifierFamilies": ["pytest_injected"],
        "budgets": {
            "maxWallMs": 1800000,
            "maxTokens": 240000,
            "maxSteps": 80,
            "maxToolCalls": 80,
            "softLimitPercent": 0.9,
        },
        "discoveredTaskIds": [task_id],
        "taskIds": [task_id],
        "blockedTaskIds": {},
        "tasks": {
            task_id: {
                "category": "bug-fix",
                "verifierFamily": "pytest_injected",
                "workspaceSha256": _sha256(workspace),
                "instructionSha256": _sha256(instruction),
                "verifierSha256": _sha256(verifier),
                "taskTomlSha256": _sha256(task_toml),
            }
        },
    }
    manifest_path = tmp_path / "manifest.yaml"
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    return manifest_path, source, manifest


def test_tasks_prepare_and_case_mapping(tmp_path: Path) -> None:
    manifest_path, source, _ = _fixture(tmp_path)
    adapter = WorkBuddyBenchAdapter(manifest_path, source_root=source)

    tasks = adapter.tasks("internal")

    assert [task.id for task in tasks] == ["bug_fix-easy-a_crash_in_local"]
    assert adapter.discover_pytest_task_ids() == [
        "bug_fix-easy-a_crash_in_local"
    ]
    task = tasks[0]
    assert task.question == "Fix Local.__del__ cleanup.\n"
    assert task.category == "bug-fix"
    assert task.metadata["verifierFamily"] == "pytest_injected"
    assert task.metadata["agentTimeoutSec"] == 1800.0
    assert task.metadata["verifierTimeoutSec"] == 600.0
    assert task.raw["task"]["metadata"]["repo"] == "asgiref"
    assert adapter.dataset_hash() == (
        "sha256:" + _sha256(source / "datasets" / "wb-bench-code-v1.0.tar.gz")
    )

    prepared = adapter.prepare(task, tmp_path / "attempt" / "workspace")
    assert (prepared.workspace / "asgiref" / "local.py").is_file()
    assert prepared.metadata["workspaceRoot"] == str(
        prepared.workspace.resolve()
    )
    assert prepared.metadata["verifierCommand"].startswith("python -m pytest")

    case = adapter.case_for_task(task)
    assert case.id == task.id
    assert case.suite == "workbuddybench-internal"
    assert case.query == task.question
    assert case.expected.max_steps == 80
    assert case.budgets.max_total_tokens == 240000
    assert case.budgets.max_wall_ms == 1800000
    assert case.budgets.max_tool_calls == 80
    assert case.script == []


def test_dataset_and_task_hashes_are_reported(tmp_path: Path) -> None:
    manifest_path, source, _ = _fixture(tmp_path)
    adapter = WorkBuddyBenchAdapter(manifest_path, source_root=source)
    metadata = adapter.report_metadata()
    assert metadata["officialScore"] is False
    assert metadata["scoreLabel"] == "compatibility-subset"
    assert metadata["datasetArchiveVerified"] is True
    assert metadata["discoveredTaskIds"] == [
        "bug_fix-easy-a_crash_in_local"
    ]
    assert metadata["taskIds"] == ["bug_fix-easy-a_crash_in_local"]
    assert metadata["blockedTaskIds"] == []


def test_workspace_hash_mismatch_is_rejected(tmp_path: Path) -> None:
    manifest_path, source, _ = _fixture(tmp_path)
    workspace = (
        source
        / "datasets"
        / "wb-bench-code-v1.0"
        / "tasks"
        / "bug_fix-easy-a_crash_in_local"
        / "environment"
        / "workspace.tar.gz"
    )
    workspace.write_bytes(b"tampered")
    adapter = WorkBuddyBenchAdapter(manifest_path, source_root=source)
    with pytest.raises(ValueError, match="workspace .* hash mismatch"):
        adapter.tasks("internal")


def test_unsupported_verifier_family_is_rejected(tmp_path: Path) -> None:
    manifest_path, source, _ = _fixture(tmp_path, family="script_verifier")
    adapter = WorkBuddyBenchAdapter(manifest_path, source_root=source)
    with pytest.raises(ValueError, match="unsupported WorkBuddy Bench verifier"):
        adapter.tasks("internal")


def test_unknown_task_directory_is_rejected(tmp_path: Path) -> None:
    manifest_path, source, manifest = _fixture(tmp_path)
    manifest["taskIds"] = ["missing-task"]
    manifest["tasks"]["missing-task"] = manifest["tasks"].pop(
        "bug_fix-easy-a_crash_in_local"
    )
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    adapter = WorkBuddyBenchAdapter(manifest_path, source_root=source)
    with pytest.raises(FileNotFoundError, match="task not found"):
        adapter.tasks("internal")


def test_archive_path_traversal_is_rejected(tmp_path: Path) -> None:
    manifest_path, source, manifest = _fixture(tmp_path)
    task_dir = (
        source
        / "datasets"
        / "wb-bench-code-v1.0"
        / "tasks"
        / "bug_fix-easy-a_crash_in_local"
    )
    workspace = task_dir / "environment" / "workspace.tar.gz"
    _write_workspace(workspace, traversal=True)
    manifest["tasks"]["bug_fix-easy-a_crash_in_local"][
        "workspaceSha256"
    ] = _sha256(workspace)
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    adapter = WorkBuddyBenchAdapter(manifest_path, source_root=source)
    task = adapter.tasks("internal")[0]
    with pytest.raises(ValueError, match="archive escapes target"):
        adapter.prepare(task, tmp_path / "traversal-workspace")


def test_safe_hardlink_is_extracted(tmp_path: Path) -> None:
    manifest_path, source, manifest = _fixture(tmp_path)
    task_dir = (
        source
        / "datasets"
        / "wb-bench-code-v1.0"
        / "tasks"
        / "bug_fix-easy-a_crash_in_local"
    )
    workspace = task_dir / "environment" / "workspace.tar.gz"
    with tarfile.open(workspace, "w:gz") as archive:
        content = b"class Local:\n    pass\n"
        info = tarfile.TarInfo("asgiref/local.py")
        info.size = len(content)
        archive.addfile(info, io.BytesIO(content))
        link = tarfile.TarInfo("docs/local.py")
        link.type = tarfile.LNKTYPE
        link.linkname = "asgiref/local.py"
        archive.addfile(link)
    manifest["tasks"]["bug_fix-easy-a_crash_in_local"][
        "workspaceSha256"
    ] = _sha256(workspace)
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    adapter = WorkBuddyBenchAdapter(manifest_path, source_root=source)
    task = adapter.tasks("internal")[0]

    prepared = adapter.prepare(task, tmp_path / "hardlink-workspace")

    assert (prepared.workspace / "docs" / "local.py").is_file()


def test_official_split_is_rejected_in_phase_one(tmp_path: Path) -> None:
    manifest_path, source, _ = _fixture(tmp_path)
    adapter = WorkBuddyBenchAdapter(manifest_path, source_root=source)
    with pytest.raises(ValueError, match="unsupported WorkBuddy Bench split"):
        adapter.tasks("official-subset")


def _trace(case_id: str, *, status: str = "SUCCESS") -> RunTrace:
    return RunTrace.from_dict(
        {
            "run": {
                "caseId": case_id,
                "requestId": "request",
                "status": status,
                "finalSummaryText": "done",
            },
            "llmInvocations": [],
            "toolInvocations": [],
            "artifacts": [],
            "environment": {},
        }
    )


def test_score_maps_verified_reward_to_hard_gate(tmp_path: Path) -> None:
    manifest_path, source, _ = _fixture(tmp_path)

    class Verifier:
        def run(self, **kwargs):
            return WorkBuddyDockerVerifierResult(
                status="verified",
                phase="complete",
                image="cute-workbuddybench:test",
                reward={"overall": 1.0},
                score={"test_pass_rate": 1.0, "test_status": "full_pass"},
            )

    adapter = WorkBuddyBenchAdapter(
        manifest_path,
        source_root=source,
        docker_verifier=Verifier(),
    )
    task = adapter.tasks("internal")[0]
    run = BenchmarkRun(
        task=task,
        trace=_trace(task.id),
        trace_path="trace.json",
        calls=[],
        metadata={
            "workspaceRoot": str(tmp_path / "workspace"),
            "verificationOutputDir": str(tmp_path / "verifier"),
        },
    )

    result = adapter.score(run)

    assert result.passed is True
    assert result.scores[0].name == "official_verifier"
    assert result.scores[0].status == "verified"
    assert result.scores[0].evidence["officialScore"] is False


def test_score_marks_missing_docker_as_environment_unverified(
    tmp_path: Path,
) -> None:
    manifest_path, source, _ = _fixture(tmp_path)

    class Verifier:
        def run(self, **kwargs):
            raise DockerEnvironmentError("engine unavailable")

    adapter = WorkBuddyBenchAdapter(
        manifest_path,
        source_root=source,
        docker_verifier=Verifier(),
    )
    task = adapter.tasks("internal")[0]
    run = BenchmarkRun(
        task=task,
        trace=_trace(task.id),
        trace_path="trace.json",
        calls=[],
        metadata={
            "workspaceRoot": str(tmp_path / "workspace"),
            "verificationOutputDir": str(tmp_path / "verifier"),
        },
    )

    result = adapter.score(run)

    assert result.unverified is True
    assert result.scores[0].passed is None
    assert result.scores[0].evidence["failureCategory"] == "docker_environment"


def test_score_marks_partial_official_test_as_test_failure(tmp_path: Path) -> None:
    manifest_path, source, _ = _fixture(tmp_path)

    class Verifier:
        def run(self, **kwargs):
            return WorkBuddyDockerVerifierResult(
                status="verified",
                phase="complete",
                image="cute-workbuddybench:test",
                reward={"overall": 0.3333},
                score={"test_pass_rate": 0.3333, "test_status": "partial_pass"},
            )

    adapter = WorkBuddyBenchAdapter(
        manifest_path,
        source_root=source,
        docker_verifier=Verifier(),
    )
    task = adapter.tasks("internal")[0]
    run = BenchmarkRun(
        task=task,
        trace=_trace(task.id),
        trace_path="trace.json",
        calls=[],
        metadata={
            "workspaceRoot": str(tmp_path / "workspace"),
            "verificationOutputDir": str(tmp_path / "verifier"),
        },
    )

    result = adapter.score(run)

    assert result.passed is False
    assert result.scores[0].status == "verified"
    assert result.scores[0].evidence["failureCategory"] == "test_failure"
