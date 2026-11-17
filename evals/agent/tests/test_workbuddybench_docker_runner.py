from __future__ import annotations

import json
import io
import os
import stat
import subprocess
import tarfile
from pathlib import Path

import pytest

from cute_evals.benchmarks.base import BenchmarkTask
from cute_evals.benchmarks.workbuddybench.docker_runner import (
    DockerEnvironmentError,
    WorkBuddyDockerVerifier,
)


def _task(tmp_path: Path) -> BenchmarkTask:
    task_dir = tmp_path / "task"
    (task_dir / "environment").mkdir(parents=True)
    (task_dir / "environment" / "Dockerfile").write_text(
        "FROM python:3.12-slim\n",
        encoding="utf-8",
    )
    (task_dir / "tests" / "injected").mkdir(parents=True)
    (task_dir / "tests" / "verifier.toml").write_text(
        'family = "pytest_injected"\n',
        encoding="utf-8",
    )
    (task_dir / "tests" / "gold.patch").write_text("gold\n", encoding="utf-8")
    workspace_archive = task_dir / "environment" / "workspace.tar.gz"
    with tarfile.open(workspace_archive, "w:gz") as archive:
        content = b"value = 1\n"
        info = tarfile.TarInfo("asgiref/local.py")
        info.size = len(content)
        archive.addfile(info, io.BytesIO(content))
    return BenchmarkTask(
        id="bug_fix-easy-a_crash_in_local",
        category="bug-fix",
        question="Fix it.",
        functions=[],
        ground_truth=None,
        raw={"task": {"environment": {"build_timeout_sec": 60}}},
        metadata={
            "taskDir": str(task_dir),
            "workspaceArchive": str(workspace_archive),
            "workspaceSha256": "a" * 64,
            "verifierCwd": "/workspace",
            "verifierCommand": "python -m pytest tests/test_local.py",
            "verifierTimeoutSec": 60,
        },
    )


def _workspace(tmp_path: Path) -> Path:
    workspace = tmp_path / "workspace"
    (workspace / ".git").mkdir(parents=True)
    (workspace / "asgiref").mkdir()
    (workspace / "asgiref" / "local.py").write_text("value = 1\n", encoding="utf-8")
    (workspace / "__pycache__").mkdir()
    (workspace / "__pycache__" / "local.pyc").write_bytes(b"cache")
    return workspace


class FakeDocker:
    def __init__(
        self,
        *,
        reward: float = 1.0,
        test_status: str = "full_pass",
        verifier_exit: int = 0,
        docker_available: bool = True,
    ) -> None:
        self.reward = reward
        self.test_status = test_status
        self.verifier_exit = verifier_exit
        self.docker_available = docker_available
        self.commands: list[list[str]] = []
        self.container_name = ""

    def __call__(
        self,
        command: list[str],
        timeout: int,
    ) -> subprocess.CompletedProcess[str]:
        self.commands.append(command)
        arguments = command[1:]
        if arguments[:1] == ["version"]:
            if not self.docker_available:
                return subprocess.CompletedProcess(
                    command,
                    1,
                    "",
                    "engine unavailable",
                )
            return subprocess.CompletedProcess(command, 0, "linux|1.51\n", "")
        if arguments[:1] == ["build"]:
            return subprocess.CompletedProcess(command, 0, "built\n", "")
        if arguments[:2] == ["image", "inspect"]:
            return subprocess.CompletedProcess(command, 0, "sha256:image\n", "")
        if arguments[:1] == ["create"]:
            self.container_name = arguments[2]
            return subprocess.CompletedProcess(command, 0, "container\n", "")
        if arguments[:1] in (["start"], ["rm"]):
            return subprocess.CompletedProcess(command, 0, "", "")
        if arguments[:1] == ["inspect"]:
            return subprocess.CompletedProcess(command, 0, "container-id\n", "")
        if arguments[:1] == ["cp"]:
            destination = Path(arguments[2])
            if arguments[1].endswith(":/logs/verifier/."):
                destination.mkdir(parents=True, exist_ok=True)
                (destination / "results.xml").write_text(
                    "<testsuite tests='3' failures='0' errors='0'/>",
                    encoding="utf-8",
                )
                (destination / "test_output.txt").write_text(
                    "3 passed\n",
                    encoding="utf-8",
                )
                (destination / "score.json").write_text(
                    json.dumps(
                        {
                            "test_pass_rate": self.reward,
                            "test_status": self.test_status,
                            "overall": self.reward,
                            "tests_passed": int(self.reward * 3),
                            "tests_total": 3,
                        }
                    ),
                    encoding="utf-8",
                )
                (destination / "reward.json").write_text(
                    json.dumps({"overall": self.reward, "reward": self.reward}),
                    encoding="utf-8",
                )
                (destination / "agent.patch").write_text(
                    "diff --git a/asgiref/local.py b/asgiref/local.py\n",
                    encoding="utf-8",
                )
                (destination / "pre_injected_agent.patch").write_text(
                    "diff --git a/tests/test_local.py b/tests/test_local.py\n",
                    encoding="utf-8",
                )
            return subprocess.CompletedProcess(command, 0, "", "")
        if arguments[:1] == ["exec"]:
            script = arguments[-1]
            if "=== Running injected tests ===" in script:
                return subprocess.CompletedProcess(
                    command,
                    self.verifier_exit,
                    "verifier complete\n",
                    "verifier error\n" if self.verifier_exit else "",
                )
            return subprocess.CompletedProcess(command, 0, "", "")
        raise AssertionError(f"unexpected docker command: {command}")


def test_docker_runner_collects_results_and_only_cleans_own_container(
    tmp_path: Path,
) -> None:
    docker = FakeDocker()
    verifier = WorkBuddyDockerVerifier(command_runner=docker)
    stale_object = (
        tmp_path
        / "out"
        / "verification-workspace"
        / ".git"
        / "objects"
        / "stale"
    )
    stale_object.parent.mkdir(parents=True)
    stale_object.write_text("stale\n", encoding="utf-8")
    os.chmod(stale_object, stat.S_IREAD)

    result = verifier.run(
        task=_task(tmp_path),
        workspace=_workspace(tmp_path),
        output_dir=tmp_path / "out",
    )

    assert result.status == "verified"
    assert result.passed is True
    assert result.reward["overall"] == 1.0
    assert result.image_id == "sha256:image"
    assert set(result.files) == {
        "results.xml",
        "test_output.txt",
        "score.json",
        "reward.json",
        "agent.patch",
        "pre_injected_agent.patch",
    }
    cleanup = [
        command
        for command in docker.commands
        if command[1:2] == ["rm"]
    ]
    assert cleanup == [["docker", "rm", "-f", docker.container_name]]
    assert not any("prune" in command for command in docker.commands)
    assert any(
        command[1:2] == ["exec"]
        and "pre_injected_agent.patch" in command[-1]
        for command in docker.commands
    )
    assert any(
        command[1:3] == ["cp", str((tmp_path / "task" / "tests").resolve())]
        for command in docker.commands
    )
    archive = tmp_path / "out" / "verification-workspace.tar.gz"
    with tarfile.open(archive, "r:gz") as tar:
        names = set(tar.getnames())
    assert ".git" not in names
    assert "asgiref/local.py" in names
    assert "__pycache__/local.pyc" not in names
    assert any(
        command[1:2] == ["exec"]
        and "tar -xzf /tmp/workbuddy-workspace.tar.gz" in command[-1]
        and "rm -rf /workspace" not in command[-1]
        for command in docker.commands
    )
    prepare_script = next(
        command[-1]
        for command in docker.commands
        if command[1:2] == ["exec"]
        and "git add -A" in command[-1]
    )
    assert "git config core.fileMode false" in prepare_script
    assert "':(exclude).cute'" in prepare_script
    assert "':(exclude)__last_source__.py'" in prepare_script
    assert "':(exclude)input'" in prepare_script
    assert "':(exclude)output'" in prepare_script
    assert "awk '$1 == \"120000\"" in prepare_script


def test_docker_runner_preserves_failed_test_logs(tmp_path: Path) -> None:
    docker = FakeDocker(reward=0.0, test_status="no_pass")
    verifier = WorkBuddyDockerVerifier(command_runner=docker)

    result = verifier.run(
        task=_task(tmp_path),
        workspace=_workspace(tmp_path),
        output_dir=tmp_path / "out",
    )

    assert result.status == "verified"
    assert result.passed is False
    assert (tmp_path / "out" / "verifier" / "results.xml").is_file()
    assert (tmp_path / "out" / "verifier" / "test_output.txt").is_file()


def test_docker_unavailable_is_an_environment_error(tmp_path: Path) -> None:
    docker = FakeDocker(docker_available=False)
    verifier = WorkBuddyDockerVerifier(command_runner=docker)

    with pytest.raises(DockerEnvironmentError, match="unavailable"):
        verifier.run(
            task=_task(tmp_path),
            workspace=_workspace(tmp_path),
            output_dir=tmp_path / "out",
        )
    assert docker.commands[0][1:2] == ["version"]
    assert not any(command[1:2] == ["build"] for command in docker.commands)


def test_verifier_exit_failure_is_unverified_and_cleaned(tmp_path: Path) -> None:
    docker = FakeDocker(verifier_exit=2)
    verifier = WorkBuddyDockerVerifier(command_runner=docker)

    result = verifier.run(
        task=_task(tmp_path),
        workspace=_workspace(tmp_path),
        output_dir=tmp_path / "out",
    )

    assert result.status == "infrastructure_error"
    assert result.failure_category == "verifier_failure"
    assert result.exit_code == 2
    assert (tmp_path / "out" / "docker-exec.stderr.txt").is_file()
    assert docker.commands[-1] == [
        "docker",
        "rm",
        "-f",
        docker.container_name,
    ]
