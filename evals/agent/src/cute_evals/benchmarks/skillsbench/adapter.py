from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import uuid
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
from ..source import download_official_archive

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_path(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _parse_task_markdown(text: str) -> tuple[dict[str, Any], str]:
    if not text.startswith("---"):
        raise ValueError("SkillsBench task.md is missing YAML front matter")
    parts = text.split("---", 2)
    if len(parts) != 3:
        raise ValueError("SkillsBench task.md has invalid YAML front matter")
    metadata = yaml.safe_load(parts[1])
    if not isinstance(metadata, dict):
        raise TypeError("SkillsBench task.md metadata must be an object")
    return metadata, parts[2].strip()


class DockerVerifier:
    def __init__(self, docker: str = "docker") -> None:
        self.docker = docker

    def _run(
        self,
        command: list[str],
        *,
        timeout: int,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [self.docker, *command],
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            check=False,
            timeout=timeout,
        )

    def build(self, task_dir: Path, out_dir: Path) -> dict[str, Any]:
        environment_dir = task_dir / "environment"
        if not environment_dir.is_dir():
            raise ValueError(f"invalid SkillsBench task package: {task_dir}")
        out_dir.mkdir(parents=True, exist_ok=True)
        image = f"cute-skillsbench-{task_dir.name}-{_sha256_path(task_dir / 'task.md')[:12]}"
        try:
            result = self._run(
                ["build", "-t", image, str(environment_dir)],
                timeout=600,
            )
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError(f"docker build timed out: {exc}") from exc
        if result.returncode != 0:
            raise RuntimeError(
                f"docker build failed: {result.stderr[-4000:]}"
            )
        return {"image": image, "status": "built"}

    def materialize(self, task_dir: Path, workspace: Path, out_dir: Path) -> dict[str, Any]:
        """Copy the image's initial /root into an isolated host workspace.

        The official SkillsBench tasks place their seed files in the image,
        not necessarily in the environment directory. Copying those files is
        necessary before the host-side Cute Agent starts working.
        """
        workspace.mkdir(parents=True, exist_ok=True)
        build = self.build(task_dir, out_dir)
        image = str(build["image"])
        name = f"cute-skillsbench-seed-{uuid.uuid4().hex[:12]}"
        try:
            created = self._run(
                ["create", "--name", name, image],
                timeout=120,
            )
            if created.returncode != 0:
                raise RuntimeError(
                    f"docker create failed: {created.stderr[-4000:]}"
                )
            copied = self._run(
                ["cp", f"{name}:/root/.", str(workspace.resolve())],
                timeout=300,
            )
            if copied.returncode != 0:
                raise RuntimeError(
                    f"docker copy seed workspace failed: {copied.stderr[-4000:]}"
                )
        finally:
            try:
                self._run(["rm", "-f", name], timeout=60)
            except subprocess.TimeoutExpired:
                pass
        return {
            "image": image,
            "status": "materialized",
            "workspace": str(workspace.resolve()),
        }

    def run(
        self,
        task_dir: Path,
        workspace: Path,
        out_dir: Path,
        *,
        image: str | None = None,
    ) -> dict[str, Any]:
        environment_dir = task_dir / "environment"
        verifier_dir = task_dir / "verifier"
        if not environment_dir.is_dir() or not verifier_dir.is_dir():
            raise ValueError(f"invalid SkillsBench task package: {task_dir}")
        out_dir.mkdir(parents=True, exist_ok=True)
        if image is None:
            image = f"cute-skillsbench-{task_dir.name}-{_sha256_path(task_dir / 'task.md')[:12]}"
            try:
                build = self._run(
                    ["build", "-t", image, str(environment_dir)],
                    timeout=600,
                )
            except subprocess.TimeoutExpired as exc:
                return {
                    "passed": False,
                    "status": "infrastructure_error",
                    "phase": "build",
                    "error": f"docker build timed out: {exc}",
                    "image": image,
                }
            if build.returncode != 0:
                return {
                    "passed": False,
                    "status": "infrastructure_error",
                    "phase": "build",
                    "error": build.stderr[-4000:],
                    "image": image,
                }
        logs = out_dir / "logs"
        logs.mkdir(parents=True, exist_ok=True)
        name = f"cute-skillsbench-verify-{uuid.uuid4().hex[:12]}"
        try:
            created = self._run(
                [
                    "create",
                    "--name",
                    name,
                    "-v",
                    f"{verifier_dir.resolve()}:/verifier:ro",
                    "-v",
                    f"{logs.resolve()}:/logs",
                    image,
                    "sleep",
                    "infinity",
                ],
                timeout=120,
            )
            if created.returncode != 0:
                return {
                    "passed": False,
                    "status": "infrastructure_error",
                    "phase": "create",
                    "error": created.stderr[-4000:],
                    "image": image,
                }
            started = self._run(["start", name], timeout=120)
            if started.returncode != 0:
                return {
                    "passed": False,
                    "status": "infrastructure_error",
                    "phase": "start",
                    "error": started.stderr[-4000:],
                    "image": image,
                }
            copied = self._run(
                ["cp", f"{workspace.resolve()}/.", f"{name}:/root"],
                timeout=300,
            )
            if copied.returncode != 0:
                return {
                    "passed": False,
                    "status": "infrastructure_error",
                    "phase": "copy-workspace",
                    "error": copied.stderr[-4000:],
                    "image": image,
                }
            run = self._run(
                ["exec", name, "bash", "/verifier/test.sh"],
                timeout=900,
            )
        except subprocess.TimeoutExpired as exc:
            return {
                "passed": False,
                "status": "infrastructure_error",
                "phase": "verify",
                "error": f"docker verifier timed out: {exc}",
                "image": image,
            }
        finally:
            try:
                self._run(["rm", "-f", name], timeout=60)
            except subprocess.TimeoutExpired:
                pass
        reward_path = logs / "verifier" / "reward.txt"
        reward_text = reward_path.read_text(encoding="utf-8").strip() if reward_path.exists() else ""
        if not reward_text:
            return {
                "passed": False,
                "status": "infrastructure_error",
                "phase": "reward",
                "error": "official verifier did not write reward.txt",
                "exitCode": run.returncode,
                "stdout": run.stdout[-4000:],
                "stderr": run.stderr[-4000:],
                "image": image,
            }
        return {
            "passed": run.returncode == 0 and reward_text == "1",
            "status": "verified" if run.returncode == 0 else "failed",
            "exitCode": run.returncode,
            "reward": reward_text,
            "stdout": run.stdout[-4000:],
            "stderr": run.stderr[-4000:],
            "image": image,
        }


class SkillsBenchAdapter:
    name = "skillsbench"

    def __init__(
        self,
        manifest_path: Path = DEFAULT_MANIFEST,
        *,
        data_home: Path | None = None,
        source_root: Path | None = None,
        verifier: DockerVerifier | None = None,
    ) -> None:
        self.manifest_path = manifest_path.resolve()
        self.manifest = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(self.manifest, dict):
            raise TypeError(f"invalid SkillsBench manifest: {self.manifest_path}")
        self.version = str(self.manifest["version"])
        self.upstream = str(self.manifest["upstream"])
        self.score_label = str(self.manifest["scoreLabel"])
        self.data_home = (
            data_home
            or Path(
                os.environ.get("EVAL_DATA_HOME", Path.home() / ".cache" / "cute-evals")
            )
        ).resolve()
        self.source_root = (
            source_root.resolve()
            if source_root is not None
            else self.data_home / "skillsbench" / self.version / "source"
        )
        self.verifier = verifier or DockerVerifier()
        self._tasks: list[BenchmarkTask] | None = None

    def ensure_source(self) -> Path:
        if (self.source_root / "tasks").is_dir():
            return self.source_root
        source = self.manifest["source"]
        expected = str(source["sha256"])
        if expected == "pending":
            raise RuntimeError(
                "SkillsBench source SHA-256 is not pinned; provide an extracted "
                f"official source at {self.source_root}"
            )
        archive = self.source_root.parent / "source.tar.gz"
        archive.parent.mkdir(parents=True, exist_ok=True)
        if not archive.exists():
            data = download_official_archive(source["url"])
            actual = _sha256_bytes(data)
            if actual != expected:
                raise ValueError(
                    f"SkillsBench archive hash mismatch: expected {expected}, got {actual}"
                )
            archive.write_bytes(data)
        else:
            actual = _sha256_path(archive)
            if actual != expected:
                raise ValueError(
                    f"cached SkillsBench archive hash mismatch: "
                    f"expected {expected}, got {actual}"
                )
        with tarfile.open(archive, "r:gz") as bundle:
            root = bundle.getmembers()[0].name.split("/", 1)[0]
            with tempfile.TemporaryDirectory(dir=archive.parent) as tmp:
                bundle.extractall(tmp, filter="data")
                extracted = Path(tmp) / root
                if not (extracted / "tasks").is_dir():
                    raise ValueError("SkillsBench archive has no tasks directory")
                shutil.copytree(extracted, self.source_root, dirs_exist_ok=True)
        return self.source_root

    def tasks(self, split: str) -> list[BenchmarkTask]:
        if split != "official-subset":
            raise ValueError(f"unsupported SkillsBench split: {split}")
        if self._tasks is not None:
            return list(self._tasks)
        source = self.ensure_source()
        tasks: list[BenchmarkTask] = []
        for task_id in self.manifest["taskIds"]:
            task_dir = source / "tasks" / str(task_id)
            task_file = task_dir / "task.md"
            if not task_file.exists():
                raise ValueError(f"SkillsBench task not found: {task_id}")
            metadata, prompt = _parse_task_markdown(
                task_file.read_text(encoding="utf-8")
            )
            skills_dir = task_dir / "environment" / "skills"
            skills = (
                sorted(path.name for path in skills_dir.iterdir() if path.is_dir())
                if skills_dir.is_dir()
                else []
            )
            tasks.append(
                BenchmarkTask(
                    id=str(task_id),
                    category=str(
                        (metadata.get("metadata") or {}).get("category")
                        or "skillsbench"
                    ),
                    question=prompt,
                    functions=[],
                    ground_truth=None,
                    raw={"metadata": metadata, "prompt": prompt},
                    turns=[{"query": prompt}],
                    metadata={
                        "taskDir": str(task_dir),
                        "skills": skills,
                    },
                )
            )
        self._tasks = tasks
        return list(tasks)

    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask:
        task_dir = Path(str(task.metadata["taskDir"]))
        workspace.mkdir(parents=True, exist_ok=True)
        skills_dir = task_dir / "environment" / "skills"
        if skills_dir.is_dir():
            shutil.copytree(skills_dir, workspace / "skills", dirs_exist_ok=True)
        return PreparedTask(
            task=task,
            workspace=workspace,
            metadata={
                "taskDir": str(task_dir),
                "skills": list(task.metadata.get("skills") or []),
            },
        )

    def prepare_live(
        self,
        task: BenchmarkTask,
        workspace: Path,
        out_dir: Path,
    ) -> PreparedTask:
        prepared = self.prepare(task, workspace)
        environment = self.verifier.materialize(
            Path(str(task.metadata["taskDir"])),
            workspace,
            out_dir,
        )
        prepared.metadata["environment"] = environment
        return prepared

    def fixture_script(self, task: BenchmarkTask) -> dict[str, Any]:
        return {
            "id": task.id,
            "query": task.question,
            "script": [{"type": "final", "content": "SKILLSBENCH_FIXTURE_SMOKE"}],
            "tools": [],
        }

    def case_for_task(self, task: BenchmarkTask) -> EvalCase:
        budgets = self.manifest["budgets"]
        return EvalCase(
            id=task.id,
            suite="skillsbench-official-subset",
            version=1,
            hard_gate=True,
            query=task.question,
            expected=ExpectedSpec(max_steps=int(budgets["maxSteps"])),
            budgets=BudgetSpec(
                max_total_tokens=int(budgets["maxTokens"]),
                max_wall_ms=int(budgets["maxWallMs"]),
            ),
            script=[{"type": "final", "content": "SKILLSBENCH_FIXTURE_SMOKE"}],
            source_path=str(self.manifest_path),
            tools=[],
        )

    def collect(
        self,
        task: BenchmarkTask,
        trace: RunTrace,
        trace_path: str,
        *,
        prepared: PreparedTask | None = None,
        oracle: dict[str, Any] | None = None,
    ) -> BenchmarkRun:
        return BenchmarkRun(
            task=task,
            trace=trace,
            trace_path=trace_path,
            calls=trace.tool_invocations,
            oracle=oracle or {},
            metadata={"skills": (prepared.metadata if prepared else {}).get("skills") or []},
        )

    def verify(
        self,
        task: BenchmarkTask,
        workspace: Path,
        out_dir: Path,
        *,
        image: str | None = None,
    ) -> dict[str, Any]:
        return self.verifier.run(
            Path(str(task.metadata["taskDir"])),
            workspace,
            out_dir,
            image=image,
        )

    def score(self, run: BenchmarkRun) -> CaseResult:
        oracle = run.oracle or {}
        oracle_status = str(oracle.get("status") or "unverified")
        task_passed = oracle_status == "verified" and bool(oracle.get("passed"))
        availability = "verified" if oracle_status in {"verified", "failed"} else "unverified"
        skill_scores = self._skill_scores(run)
        scores = [
            ScoreRecord(
                case_id=run.task.id,
                scorer="skillsbench.official",
                name="official_verifier",
                value=bool(oracle.get("passed")),
                unit="boolean",
                passed=task_passed,
                hard_gate=True,
                status=availability,
                evidence=oracle,
            ),
            *skill_scores,
        ]
        return CaseResult(
            case=self.case_for_task(run.task),
            trace=run.trace,
            scores=scores,
            trace_path=run.trace_path,
        )

    def _skill_scores(self, run: BenchmarkRun) -> list[ScoreRecord]:
        available = sorted(
            str(item) for item in run.metadata.get("skills") or [] if str(item)
        )
        canonical = {skill.lower(): skill for skill in available}
        mentioned: list[str] = []
        loads: dict[str, int] = {}
        for invocation in run.trace.tool_invocations:
            haystack = " ".join(
                str(invocation.get(key) or "")
                for key in ("toolName", "inputJson", "toolResult", "llmObservation")
            ).lower()
            matches = {
                canonical.get(name, name)
                for name in re.findall(
                    r"(?:^|[/\\\"'])skills[/\\]([^/\\\s\"']+)",
                    haystack,
                )
            }
            matches.update(skill for skill in available if skill.lower() in haystack)
            for skill in sorted(matches):
                loads[skill] = loads.get(skill, 0) + 1
                if skill not in mentioned:
                    mentioned.append(skill)
        used = [skill for skill in mentioned if skill in available]
        wrong_skill = [skill for skill in mentioned if skill not in available]
        load_count = sum(loads.values())
        repeated = max(0, sum(loads[skill] for skill in used) - len(used))
        selection_ok = bool(used) if available else True
        return [
            ScoreRecord(
                case_id=run.task.id,
                scorer="skillsbench.trajectory",
                name="skill_selection",
                value=used,
                unit="skill_names",
                passed=selection_ok,
                hard_gate=False,
                status="verified",
                evidence={
                    "availableSkills": available,
                    "usedSkills": used,
                    "wrongSkills": wrong_skill,
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="skillsbench.trajectory",
                name="skill_load_count",
                value=load_count,
                unit="count",
                passed=None,
                hard_gate=False,
                status="verified",
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="skillsbench.trajectory",
                name="repeated_skill_loads",
                value=repeated,
                unit="count",
                passed=repeated == 0,
                hard_gate=False,
                status="verified",
            ),
        ]

    def dataset_hash(self) -> str:
        payload = []
        for task in self.tasks("official-subset"):
            task_dir = Path(str(task.metadata["taskDir"]))
            files = {}
            for path in sorted(task_dir.rglob("*")):
                if path.is_file():
                    files[str(path.relative_to(task_dir))] = _sha256_path(path)
            payload.append({"taskId": task.id, "files": files})
        encoded = json.dumps(
            {
                "name": self.name,
                "version": self.version,
                "tasks": payload,
            },
            sort_keys=True,
            ensure_ascii=False,
        ).encode("utf-8")
        return f"sha256:{_sha256_bytes(encoded)}"

    def report_metadata(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "license": self.manifest["license"],
            "split": self.manifest["split"],
            "scoreLabel": self.score_label,
            "officialEvaluator": True,
            "sandbox": "official-image-copy",
            "runnerMode": self.manifest["runnerMode"],
            "taskIds": list(self.manifest["taskIds"]),
            "sourceSha256": self.manifest["source"]["sha256"],
            "datasetHash": self.dataset_hash(),
        }
