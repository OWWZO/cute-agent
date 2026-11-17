from __future__ import annotations

import json
import os
import posixpath
import shutil
import stat
import subprocess
import sys
import tarfile
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..base import BenchmarkTask

OFFICIAL_VERIFIER_SHA256 = (
    "c70a2777e8c2a02caf77be5472cb1276239be46cc6ea0c528bd1e8237680b442"
)

CommandRunner = Callable[
    [list[str], int],
    subprocess.CompletedProcess[str],
]


class DockerEnvironmentError(RuntimeError):
    """Docker is missing or does not expose a Linux engine."""


@dataclass(slots=True)
class WorkBuddyDockerVerifierResult:
    status: str
    phase: str
    image: str
    docker_api: str | None = None
    docker_os: str | None = None
    image_id: str | None = None
    container_name: str | None = None
    container_id: str | None = None
    verifier_command: str = ""
    exit_code: int | None = None
    reward: dict[str, Any] = field(default_factory=dict)
    score: dict[str, Any] = field(default_factory=dict)
    output_dir: str = ""
    files: dict[str, str] = field(default_factory=dict)
    stdout: str = ""
    stderr: str = ""
    error: str | None = None
    failure_category: str | None = None

    @property
    def passed(self) -> bool | None:
        if self.status != "verified":
            return None
        return (
            float(self.reward.get("overall") or 0.0) >= 1.0
            and str(self.score.get("test_status") or "") == "full_pass"
        )

    def to_evidence(self) -> dict[str, Any]:
        return {
            "dockerOs": self.docker_os,
            "dockerApi": self.docker_api,
            "dockerImage": self.image,
            "dockerImageId": self.image_id,
            "containerName": self.container_name,
            "containerId": self.container_id,
            "verifierCommand": self.verifier_command,
            "officialVerifierSha256": OFFICIAL_VERIFIER_SHA256,
            "exitCode": self.exit_code,
            "reward": self.reward,
            "score": self.score,
            "outputDir": self.output_dir,
            "resultFiles": self.files,
            "stdout": self.stdout[-4000:],
            "stderr": self.stderr[-4000:],
            "error": self.error,
        }


def _default_command_runner(
    command: list[str],
    timeout: int,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
        timeout=timeout,
    )


def _slug(value: str) -> str:
    normalized = "".join(
        character.lower()
        if character.isalnum() or character in {"-", "_", "."}
        else "-"
        for character in value
    )
    return normalized.strip("-.")[:72] or "task"


def _copy_workspace(source: Path, target: Path) -> None:
    if not source.is_dir():
        raise ValueError(f"WorkBuddy Bench agent workspace not found: {source}")
    if not (source / ".git").is_dir():
        raise ValueError(
            "WorkBuddy Bench agent workspace has no git baseline: "
            f"{source}"
        )
    for path in source.rglob("*"):
        if path.is_symlink():
            raise ValueError(
                f"WorkBuddy Bench workspace symlink is not supported: {path}"
            )
    ignore = shutil.ignore_patterns(
        "__pycache__",
        "*.pyc",
        ".pytest_cache",
        ".mypy_cache",
        ".ruff_cache",
    )
    shutil.copytree(source, target, ignore=ignore)


def _create_workspace_archive(workspace: Path, archive: Path) -> None:
    with tarfile.open(archive, "w:gz") as tar:
        for child in sorted(workspace.iterdir(), key=lambda path: path.name):
            if child.name == ".git":
                continue
            tar.add(child, arcname=child.name)


def _baseline_files(
    archive: Path,
    agent_workspace: Path,
) -> list[str]:
    baseline: set[str] = set()
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar.getmembers():
            if not (
                member.isfile()
                or member.issym()
                or member.islnk()
            ):
                continue
            normalized = posixpath.normpath(member.name)
            if (
                normalized in {"", ".", ".."}
                or normalized.startswith("../")
                or normalized.startswith("/")
            ):
                continue
            if normalized.startswith("./"):
                normalized = normalized[2:]
            if normalized != ".git" and not normalized.startswith(".git/"):
                baseline.add(normalized)
    agent_files = {
        path.relative_to(agent_workspace).as_posix()
        for path in agent_workspace.rglob("*")
        if (path.is_file() or path.is_symlink())
        and path.name != ".git"
        and ".git" not in path.relative_to(agent_workspace).parts
    }
    return sorted(baseline - agent_files)


def _remove_tree(path: Path) -> None:
    def clear_readonly(
        function: Callable[..., Any],
        target: str,
        error: BaseException,
    ) -> None:
        del error
        os.chmod(target, stat.S_IWRITE | stat.S_IREAD)
        function(target)

    if sys.version_info >= (3, 12):
        shutil.rmtree(path, onexc=clear_readonly)
    else:
        shutil.rmtree(
            path,
            onerror=lambda function, target, error_info: clear_readonly(
                function,
                target,
                error_info[1],
            ),
        )


def _official_pytest_command(pytest_command: str) -> str:
    template = r"""
set -euo pipefail
cd /workspace
START_TIME=$(date +%s)
mkdir -p /logs/verifier
if [ -f /logs/verifier/agent.patch ]; then
    cp /logs/verifier/agent.patch /logs/verifier/pre_injected_agent.patch
fi
if [ -d /tests/injected ]; then
python3 - <<'PY'
from pathlib import Path
import shutil

src_root = Path("/tests/injected")
dst_root = Path("/workspace")
for src_path in src_root.rglob("*"):
    if not src_path.is_file():
        continue
    dest_path = dst_root / src_path.relative_to(src_root)
    dest_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src_path, dest_path)
PY
fi
echo "=== Running injected tests ==="
set +e
__PYTEST_COMMAND__
PYTEST_EXIT=$?
set -e
__PARSE_PYTEST_RESULTS__
cat /logs/verifier/test_output.txt 2>/dev/null || true
END_TIME=$(date +%s)
WALL_TIME=$((END_TIME - START_TIME))
__SCORE_PYTEST_RESULTS__
cat /logs/verifier/score.json
python3 - <<'PY'
import json
score = json.load(open("/logs/verifier/score.json"))
numeric = {
    key: value
    for key, value in score.items()
    if isinstance(value, (int, float)) and not isinstance(value, bool)
}
overall = score.get("overall", numeric.get("overall", 0.0))
if not isinstance(overall, (int, float)) or isinstance(overall, bool):
    overall = 0.0
numeric["reward"] = overall
json.dump(numeric, open("/logs/verifier/reward.json", "w"), indent=2)
PY
cat /logs/verifier/reward.json
""".strip()
    return (
        template.replace("__PYTEST_COMMAND__", pytest_command)
        .replace("__PARSE_PYTEST_RESULTS__", _parse_pytest_results_snippet())
        .replace("__SCORE_PYTEST_RESULTS__", _score_pytest_results_snippet())
    )


def _parse_pytest_results_snippet() -> str:
    return r"""
eval "$(PYTEST_EXIT="$PYTEST_EXIT" python3 <<'PY'
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

xml_path = "/logs/verifier/results.xml"
output_text = (
    Path("/logs/verifier/test_output.txt").read_text(errors="replace")
    if Path("/logs/verifier/test_output.txt").exists()
    else ""
)
exit_code = int(os.environ["PYTEST_EXIT"])

def parse_text_fallback(text: str):
    passed = failed = errors = skipped = total = 0
    ran_match = re.search(r"Ran (\d+) tests?", text)
    if ran_match:
        total = int(ran_match.group(1))
        failed_match = re.search(r"failures=(\d+)", text)
        error_match = re.search(r"errors=(\d+)", text)
        skipped_match = re.search(r"skipped=(\d+)", text)
        failed = int(failed_match.group(1)) if failed_match else 0
        errors = int(error_match.group(1)) if error_match else 0
        skipped = int(skipped_match.group(1)) if skipped_match else 0
        passed = max(0, total - failed - errors - skipped)
    rate = round(passed / total, 4) if total > 0 else 0
    return passed, failed, errors, skipped, total, rate

if Path(xml_path).exists():
    try:
        tree = ET.parse(xml_path)
        root = tree.getroot()
        suites = root.findall("testsuite") if root.tag == "testsuites" else [root]
        total = sum(int(s.get("tests", 0)) for s in suites)
        failed = sum(int(s.get("failures", 0)) for s in suites)
        errors = sum(int(s.get("errors", 0)) for s in suites)
        skipped = sum(int(s.get("skipped", 0)) for s in suites)
        passed = max(0, total - failed - errors - skipped)
        rate = round(passed / total, 4) if total > 0 else 0
    except Exception:
        passed, failed, errors, skipped, total, rate = parse_text_fallback(output_text)
else:
    passed, failed, errors, skipped, total, rate = parse_text_fallback(output_text)

if total <= 0:
    passed, failed, errors, skipped, total, rate = parse_text_fallback(output_text)

build_error_markers = (
    "ERROR: found no collectors",
    "ImportError while loading conftest",
    "fixture '",
)
arg_parse_error = "error: unrecognized arguments:" in output_text

if exit_code not in (0, 1):
    status = "build_error"
elif any(marker in output_text for marker in build_error_markers):
    status = "build_error"
elif arg_parse_error and total <= 0:
    status = "build_error"
elif total <= 0:
    status = "build_error"
elif rate >= 1.0:
    status = "full_pass"
elif rate > 0:
    status = "partial_pass"
else:
    status = "no_pass"

print(f"TEST_PASS_RATE={rate}")
print(f"TEST_PASSED={passed}")
print(f"TEST_FAILED={failed}")
print(f"TEST_ERRORS={errors}")
print(f"TEST_SKIPPED={skipped}")
print(f"TEST_TOTAL={total}")
print(f"TEST_STATUS={status}")
PY
)"
""".strip()


def _score_pytest_results_snippet() -> str:
    return r"""
SCORER="/environment/scorer.py"
[ ! -f "$SCORER" ] && SCORER="/scorer/scorer.py"
[ ! -f "$SCORER" ] && SCORER="/tests/scorer.py"
_RATE="${TEST_PASS_RATE:-0}"
_PASSED="${TEST_PASSED:-0}"
_TOTAL="${TEST_TOTAL:-0}"
_WALL="${WALL_TIME:-0}"
if [ -f /tests/gold.patch ] && [ -f "$SCORER" ]; then
    python3 "$SCORER" \
        --agent-patch /logs/verifier/agent.patch \
        --gold-patch /tests/gold.patch \
        --test-pass "$_RATE" \
        --tests-passed "$_PASSED" \
        --tests-total "$_TOTAL" \
        --wall-time "$_WALL" \
        ${TEST_STATUS:+--test-status "$TEST_STATUS"} \
        --output /logs/verifier/score.json
else
    _RATE="$_RATE" _PASSED="$_PASSED" _TOTAL="$_TOTAL" _WALL="$_WALL" \
        TEST_STATUS="${TEST_STATUS:-}" python3 - <<'PY'
import json
import os

tpr = float(os.environ.get("_RATE") or 0)
total = int(os.environ.get("_TOTAL") or 0)
status = os.environ.get("TEST_STATUS") or (
    "build_error"
    if total == 0
    else ("full_pass" if tpr >= 1 else ("partial_pass" if tpr > 0 else "no_pass"))
)
json.dump(
    {
        "test_pass_rate": tpr,
        "test_status": status,
        "overall": tpr,
        "tests_passed": int(os.environ.get("_PASSED") or 0),
        "tests_total": total,
        "wall_time_sec": float(os.environ.get("_WALL") or 0),
    },
    open("/logs/verifier/score.json", "w"),
    indent=2,
)
PY
fi
""".strip()


OFFICIAL_PREPARE_COMMAND = r"""
set -euo pipefail
mkdir -p /logs/verifier
cd /workspace
find /workspace -type d -name __pycache__ -prune -exec rm -rf {} + 2>/dev/null || true
# 宿主 code_execution 会在仓库旁写同步状态；这些文件不计入 Agent patch。
# 同时关闭 Windows 可执行位漂移，避免整文件 mode 差异污染 patch。
git config core.fileMode false
git add -A -- . \
    ':(exclude).cute' \
    ':(exclude)__last_source__.py' \
    ':(exclude)input' \
    ':(exclude)output' \
    ':(exclude)reports'
git ls-tree -r HEAD \
    | awk '$1 == "120000" { print $4 }' \
    | while IFS= read -r path; do git reset -q -- "$path"; done
git diff --staged > /logs/verifier/agent.patch 2>/dev/null || true
git reset >/dev/null 2>&1 || true
""".strip()


class WorkBuddyDockerVerifier:
    def __init__(
        self,
        docker: str = "docker",
        *,
        command_runner: CommandRunner | None = None,
    ) -> None:
        self.docker = docker
        self.command_runner = command_runner or _default_command_runner

    def _run(
        self,
        arguments: list[str],
        *,
        timeout: int,
    ) -> subprocess.CompletedProcess[str]:
        return self.command_runner([self.docker, *arguments], timeout)

    def check_environment(self) -> tuple[str, str]:
        try:
            result = self._run(
                [
                    "version",
                    "--format",
                    "{{.Server.Os}}|{{.Server.APIVersion}}",
                ],
                timeout=30,
            )
        except (FileNotFoundError, OSError, subprocess.TimeoutExpired) as exc:
            raise DockerEnvironmentError(
                f"Docker Linux engine is unavailable: {exc}"
            ) from exc
        if result.returncode != 0:
            raise DockerEnvironmentError(
                "Docker Linux engine is unavailable: "
                f"{result.stderr[-2000:]}"
            )
        parts = result.stdout.strip().split("|", 1)
        if len(parts) != 2 or parts[0].lower() != "linux" or not parts[1]:
            raise DockerEnvironmentError(
                "WorkBuddy Bench verifier requires a Docker Linux engine; "
                f"got {result.stdout.strip()!r}"
            )
        return parts[0].lower(), parts[1]

    def run(
        self,
        *,
        task: BenchmarkTask,
        workspace: Path,
        output_dir: Path,
        docker: str | None = None,
    ) -> WorkBuddyDockerVerifierResult:
        if docker and docker != self.docker:
            self.docker = docker
        docker_os, docker_api = self.check_environment()

        task_dir = Path(str(task.metadata["taskDir"]))
        environment_dir = task_dir / "environment"
        tests_dir = task_dir / "tests"
        dockerfile = environment_dir / "Dockerfile"
        intermediate = output_dir / "verification-workspace"
        archive = output_dir / "verification-workspace.tar.gz"
        logs_dir = output_dir / "verifier"
        output_dir.mkdir(parents=True, exist_ok=True)
        for required in (
            environment_dir,
            tests_dir,
            dockerfile,
            tests_dir / "verifier.toml",
            tests_dir / "gold.patch",
            tests_dir / "injected",
        ):
            if not required.exists():
                raise ValueError(
                    f"WorkBuddy Bench verifier asset not found: {required}"
                )
        if str(task.metadata.get("verifierCwd") or "/workspace") != "/workspace":
            raise ValueError(
                "WorkBuddy Bench hybrid verifier only supports cwd=/workspace"
            )
        if intermediate.exists():
            _remove_tree(intermediate)
        if archive.exists():
            archive.unlink()
        _copy_workspace(workspace.resolve(), intermediate)
        _create_workspace_archive(intermediate, archive)
        missing_baseline_files = _baseline_files(
            Path(str(task.metadata["workspaceArchive"])),
            intermediate,
        )
        missing_baseline_path = output_dir / "missing-baseline-files.txt"
        missing_baseline_path.write_text(
            "\n".join(missing_baseline_files)
            + ("\n" if missing_baseline_files else ""),
            encoding="utf-8",
        )

        image = (
            "cute-workbuddybench:"
            f"{_slug(task.id)}-{str(task.metadata['workspaceSha256'])[:12]}"
        )
        container_name = f"cute-wb-verify-{uuid.uuid4().hex[:12]}"
        result = WorkBuddyDockerVerifierResult(
            status="infrastructure_error",
            phase="build",
            image=image,
            docker_api=docker_api,
            docker_os=docker_os,
            output_dir=str(output_dir.resolve()),
            verifier_command=str(task.metadata["verifierCommand"]),
        )
        container_created = False
        phase = "build"
        try:
            build_timeout = max(
                60,
                int(float((task.raw.get("task", {}).get("environment") or {}).get(
                    "build_timeout_sec"
                ) or 600)),
            )
            build = self._run(
                ["build", "-t", image, str(environment_dir.resolve())],
                timeout=build_timeout,
            )
            result.stdout = build.stdout
            result.stderr = build.stderr
            if build.returncode != 0:
                result.error = f"docker build failed: {build.stderr[-4000:]}"
                result.failure_category = "docker_environment"
                return result

            phase = "image"
            inspect = self._run(
                ["image", "inspect", image, "--format", "{{.Id}}"],
                timeout=60,
            )
            if inspect.returncode != 0:
                result.error = f"docker image inspect failed: {inspect.stderr[-4000:]}"
                result.failure_category = "docker_environment"
                return result
            result.image_id = inspect.stdout.strip()

            phase = "create"
            created = self._run(
                ["create", "--name", container_name, image, "sleep", "infinity"],
                timeout=120,
            )
            if created.returncode != 0:
                result.error = f"docker create failed: {created.stderr[-4000:]}"
                result.failure_category = "docker_environment"
                return result
            container_created = True
            result.container_name = container_name

            phase = "start"
            started = self._run(["start", container_name], timeout=120)
            if started.returncode != 0:
                result.error = f"docker start failed: {started.stderr[-4000:]}"
                result.failure_category = "docker_environment"
                return result

            phase = "container-id"
            container = self._run(
                ["inspect", container_name, "--format", "{{.Id}}"],
                timeout=60,
            )
            if container.returncode == 0:
                result.container_id = container.stdout.strip()

            phase = "copy-verifier"
            copied_tests = self._run(
                ["cp", str(tests_dir.resolve()), f"{container_name}:/tests"],
                timeout=300,
            )
            if copied_tests.returncode != 0:
                result.error = (
                    "docker copy official tests failed: "
                    f"{copied_tests.stderr[-4000:]}"
                )
                result.failure_category = "verifier_environment"
                return result

            phase = "replace-workspace"
            copied_archive = self._run(
                [
                    "cp",
                    str(archive.resolve()),
                    f"{container_name}:/tmp/workbuddy-workspace.tar.gz",
                ],
                timeout=300,
            )
            if copied_archive.returncode != 0:
                result.error = (
                    "docker copy workspace archive failed: "
                    f"{copied_archive.stderr[-4000:]}"
                )
                result.failure_category = "verifier_environment"
                return result
            copied_missing = self._run(
                [
                    "cp",
                    str(missing_baseline_path.resolve()),
                    f"{container_name}:/tmp/missing-baseline-files.txt",
                ],
                timeout=120,
            )
            if copied_missing.returncode != 0:
                result.error = (
                    "docker copy baseline deletion list failed: "
                    f"{copied_missing.stderr[-4000:]}"
                )
                result.failure_category = "verifier_environment"
                return result
            replaced = self._run(
                [
                    "exec",
                    "-w",
                    "/tmp",
                    container_name,
                    "bash",
                    "-lc",
                    (
                        "set -euo pipefail\n"
                        "tar -xzf /tmp/workbuddy-workspace.tar.gz -C /workspace\n"
                        "rm -f /tmp/workbuddy-workspace.tar.gz\n"
                        "python3 - <<'PY'\n"
                        "import shutil\n"
                        "from pathlib import Path\n"
                        "root = Path('/workspace')\n"
                        "listing = Path('/tmp/missing-baseline-files.txt')\n"
                        "for line in listing.read_text().splitlines():\n"
                        "    target = (root / line).resolve()\n"
                        "    if root.resolve() not in target.parents:\n"
                        "        continue\n"
                        "    if target.is_symlink() or target.is_file():\n"
                        "        target.unlink()\n"
                        "    elif target.is_dir():\n"
                        "        shutil.rmtree(target)\n"
                        "listing.unlink()\n"
                        "PY"
                    ),
                ],
                timeout=300,
            )
            if replaced.returncode != 0:
                result.error = (
                    "docker replace workspace failed: "
                    f"{replaced.stderr[-4000:]}"
                )
                result.failure_category = "verifier_environment"
                return result

            phase = "verify"
            script = (
                OFFICIAL_PREPARE_COMMAND
                + "\n"
                + _official_pytest_command(str(task.metadata["verifierCommand"]))
            )
            verifier_timeout = max(
                60,
                int(float(task.metadata.get("verifierTimeoutSec") or 600)) + 60,
            )
            executed = self._run(
                [
                    "exec",
                    "-w",
                    "/workspace",
                    container_name,
                    "bash",
                    "-lc",
                    script,
                ],
                timeout=verifier_timeout,
            )
            result.exit_code = executed.returncode
            result.stdout = executed.stdout
            result.stderr = executed.stderr
            (output_dir / "docker-exec.stdout.txt").write_text(
                executed.stdout,
                encoding="utf-8",
            )
            (output_dir / "docker-exec.stderr.txt").write_text(
                executed.stderr,
                encoding="utf-8",
            )

            phase = "collect-logs"
            logs_dir.mkdir(parents=True, exist_ok=True)
            copied_logs = self._run(
                [
                    "cp",
                    f"{container_name}:/logs/verifier/.",
                    str(logs_dir.resolve()),
                ],
                timeout=300,
            )
            if copied_logs.returncode != 0:
                result.error = (
                    "docker copy verifier logs failed: "
                    f"{copied_logs.stderr[-4000:]}"
                )
                result.failure_category = "verifier_environment"
                return result

            expected_files = (
                "results.xml",
                "test_output.txt",
                "score.json",
                "reward.json",
                "agent.patch",
                "pre_injected_agent.patch",
            )
            result.files = {
                name: str((logs_dir / name).resolve())
                for name in expected_files
                if (logs_dir / name).exists()
            }
            if executed.returncode != 0:
                result.error = (
                    f"official verifier exited with code {executed.returncode}: "
                    f"{executed.stderr[-4000:]}"
                )
                result.failure_category = "verifier_failure"
                return result

            reward_path = logs_dir / "reward.json"
            score_path = logs_dir / "score.json"
            if not reward_path.is_file() or not score_path.is_file():
                result.error = "official verifier did not write reward.json/score.json"
                result.failure_category = "verifier_failure"
                return result
            try:
                result.reward = json.loads(reward_path.read_text(encoding="utf-8"))
                result.score = json.loads(score_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exc:
                result.error = f"invalid official verifier result JSON: {exc}"
                result.failure_category = "verifier_failure"
                return result
            result.phase = "complete"
            result.status = "verified"
            return result
        except subprocess.TimeoutExpired as exc:
            result.phase = phase
            result.error = f"Docker verifier timed out in {phase}: {exc}"
            result.failure_category = "docker_environment"
            return result
        except (FileNotFoundError, OSError) as exc:
            result.phase = phase
            result.error = f"Docker verifier failed in {phase}: {exc}"
            result.failure_category = "docker_environment"
            return result
        finally:
            if container_created:
                try:
                    cleaned = self._run(
                        ["rm", "-f", container_name],
                        timeout=60,
                    )
                    if cleaned.returncode != 0:
                        result.stderr = (
                            result.stderr
                            + "\ncontainer cleanup failed: "
                            + cleaned.stderr[-2000:]
                        ).strip()
                except (OSError, subprocess.TimeoutExpired) as exc:
                    result.stderr = (
                        result.stderr + f"\ncontainer cleanup failed: {exc}"
                    ).strip()
