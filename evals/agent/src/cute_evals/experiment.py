from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

import jsonschema
import yaml

from .case_loader import load_case_dirs, load_case_file
from .models import EvalCase

PACKAGE_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_DIR = PACKAGE_ROOT / "schemas"
REPO_ROOT = Path(__file__).resolve().parents[4]


def now_iso() -> str:
    return datetime.now().isoformat(timespec="milliseconds")


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def repo_root() -> Path:
    override = os.environ.get("CUTE_EVAL_REPO_ROOT")
    return Path(override).resolve() if override else REPO_ROOT


def _json_schema(name: str) -> dict[str, Any]:
    candidates = (
        Path(__file__).resolve().parent / "schemas" / name,
        SCHEMA_DIR / name,
    )
    for path in candidates:
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
    raise FileNotFoundError(f"schema not found: {name}")


@dataclass(frozen=True, slots=True)
class ArmSpec:
    source: str
    ref: str | None = None
    root: str | None = None
    env: dict[str, str] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> ArmSpec:
        return cls(
            source=str(raw["source"]),
            ref=raw.get("ref"),
            root=raw.get("root"),
            env={str(key): str(value) for key, value in (raw.get("env") or {}).items()},
        )


@dataclass(slots=True)
class ExperimentSpec:
    schema_version: str
    experiment_id: str
    suite: str
    base: ArmSpec
    candidate: ArmSpec
    models: list[str]
    repetitions: int
    seed: int
    parallelism: int
    timeout_seconds: int
    retries: int
    profile: str
    java_mode: str
    require_judge: bool
    isolation: dict[str, Any]
    budgets: dict[str, Any]
    thresholds: dict[str, Any]
    case_dirs: list[Path]
    source_path: Path
    raw: dict[str, Any]

    @property
    def spec_hash(self) -> str:
        return sha256_text(canonical_json(self.raw))

    @classmethod
    def from_dict(
        cls,
        raw: dict[str, Any],
        *,
        source_path: Path,
        case_dirs: list[Path],
    ) -> ExperimentSpec:
        isolation = dict(raw["isolation"])
        if isolation.get("workspace") != "per-cell":
            raise ValueError(
                "isolation.workspace=per-run is not supported; use per-cell"
            )
        return cls(
            schema_version=str(raw["schemaVersion"]),
            experiment_id=str(raw["experimentId"]),
            suite=str(raw["suite"]),
            base=ArmSpec.from_dict(raw["base"]),
            candidate=ArmSpec.from_dict(raw["candidate"]),
            models=[str(item) for item in raw["models"]],
            repetitions=int(raw["repetitions"]),
            seed=int(raw["seed"]),
            parallelism=int(raw["parallelism"]),
            timeout_seconds=int(raw["timeoutSeconds"]),
            retries=int(raw.get("retries", 2)),
            profile=str(raw.get("profile", "pr")),
            java_mode=str(raw.get("javaMode", "fixture")),
            require_judge=bool(raw.get("requireJudge", False)),
            isolation=isolation,
            budgets=dict(raw["budgets"]),
            thresholds=dict(raw["thresholds"]),
            case_dirs=case_dirs,
            source_path=source_path.resolve(),
            raw=raw,
        )


def load_experiment(path: Path) -> ExperimentSpec:
    path = path.resolve()
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise TypeError(f"experiment must be a mapping: {path}")
    jsonschema.validate(raw, _json_schema("experiment.schema.json"))
    root = repo_root()
    configured = raw.get("caseDirs")
    if configured:
        case_dirs = [(root / str(item)).resolve() for item in configured]
    else:
        case_dirs = [root / "evals" / "agent" / "cases" / "ab" / str(raw["suite"])]
    return ExperimentSpec.from_dict(raw, source_path=path, case_dirs=case_dirs)


def load_experiment_cases(spec: ExperimentSpec, *, suite: str | None = None) -> list[EvalCase]:
    cases = load_case_dirs(spec.case_dirs, suite=suite)
    if not cases:
        raise ValueError(f"experiment selected no cases: {spec.experiment_id}")
    return sorted(cases, key=lambda item: item.id)


def case_fingerprint(case: EvalCase) -> str:
    return file_sha256(Path(case.source_path))


def arm_fingerprint(arm_name: str, arm: ArmSpec, root: Path) -> str:
    payload = {
        "arm": arm_name,
        "source": arm.source,
        "ref": arm.ref,
        "root": str(root),
        "env": arm.env,
    }
    if arm.source == "git":
        payload["commit"] = _git_output(["rev-parse", "HEAD"], cwd=root)
    elif (root / ".git").exists():
        # Local A/B is useful while iterating, but a dirty working tree must
        # invalidate completed Cells just like a changed Git ref does.
        try:
            payload["commit"] = _git_output(["rev-parse", "HEAD"], cwd=root)
            payload["worktreeState"] = sha256_text(
                _git_output(["status", "--porcelain"], cwd=root)
                + _git_output(["diff", "--binary", "HEAD"], cwd=root)
            )
        except ValueError:
            payload["worktreeState"] = "unborn"
        evaluator_root = root / "evals" / "agent" / "src" / "cute_evals"
        java_eval_root = (
            root
            / "Cute-agent-app"
            / "src"
            / "test"
            / "java"
            / "org"
            / "wwz"
            / "ai"
            / "test"
            / "domain"
        )
        evaluator_files = sorted(evaluator_root.rglob("*.py")) if evaluator_root.exists() else []
        evaluator_files.extend(
            sorted(java_eval_root.glob("Eval*.java"))
            if java_eval_root.exists()
            else []
        )
        if evaluator_files:
            payload["evaluatorSource"] = sha256_text(
                "".join(
                    f"{path.relative_to(root).as_posix()}:{file_sha256(path)}\n"
                    for path in evaluator_files
                )
            )
    return sha256_text(canonical_json(payload))


def _git_executable() -> str:
    return shutil.which("git.exe") or shutil.which("git") or "git"


def _git_run(args: list[str], *, cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [_git_executable(), *args],
        cwd=cwd,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
    )


def _git_output(args: list[str], *, cwd: Path) -> str:
    result = _git_run(args, cwd=cwd)
    if result.returncode != 0:
        raise ValueError(
            f"git command failed: git {' '.join(args)}\n{result.stderr[-2000:]}"
        )
    return result.stdout.strip()


def _git_is_dirty(root: Path) -> bool:
    result = _git_run(["status", "--porcelain"], cwd=root)
    if result.returncode != 0:
        return True
    return bool(result.stdout.strip())


def prepare_arm_roots(spec: ExperimentSpec, output_dir: Path) -> dict[str, Path]:
    roots: dict[str, Path] = {}
    for arm_name, arm in (("base", spec.base), ("candidate", spec.candidate)):
        if arm.source == "local":
            if not arm.root:
                raise ValueError(f"{arm_name} local arm requires root")
            root = (repo_root() / arm.root).resolve() if not Path(arm.root).is_absolute() else Path(arm.root).resolve()
            if not root.is_dir():
                raise ValueError(f"{arm_name} local root does not exist: {root}")
            roots[arm_name] = root
            continue

        if not arm.ref:
            raise ValueError(f"{arm_name} git arm requires ref")
        repo = repo_root()
        commit = _git_output(["rev-parse", "--verify", f"{arm.ref}^{{commit}}"], cwd=repo)
        if arm.ref in {"HEAD", "WORKTREE"} and _git_is_dirty(repo) and not spec.isolation.get("allowDirty", False):
            raise ValueError(
                "candidate git arm is dirty; commit the candidate or use "
                "isolation.allowDirty for a local experiment"
            )
        worktree = (output_dir / "worktrees" / arm_name).resolve()
        if worktree.exists():
            actual = _git_output(["rev-parse", "HEAD"], cwd=worktree)
            if actual == commit:
                roots[arm_name] = worktree
                continue
            raise ValueError(
                f"existing worktree has a different commit: {worktree}; "
                "choose a new --out directory"
            )
        worktree.parent.mkdir(parents=True, exist_ok=True)
        result = _git_run(
            ["worktree", "add", "--detach", str(worktree), commit],
            cwd=repo,
        )
        if result.returncode != 0:
            raise ValueError(
                f"git worktree add failed for {arm_name}\n{result.stderr[-4000:]}"
            )
        roots[arm_name] = worktree
    return roots


def build_arm_fingerprints(
    spec: ExperimentSpec,
    roots: dict[str, Path],
) -> dict[str, str]:
    return {
        "base": arm_fingerprint("base", spec.base, roots["base"]),
        "candidate": arm_fingerprint("candidate", spec.candidate, roots["candidate"]),
    }


@dataclass(frozen=True, slots=True)
class CellSpec:
    cell_key: str
    experiment_id: str
    experiment_spec_hash: str
    arm: str
    model: str
    case: EvalCase
    repetition: int
    arm_root: Path
    arm_env: dict[str, str]
    source_fingerprint: str

    def to_payload(self) -> dict[str, Any]:
        return {
            "cellKey": self.cell_key,
            "experimentId": self.experiment_id,
            "experimentSpecHash": self.experiment_spec_hash,
            "arm": self.arm,
            "model": self.model,
            "caseId": self.case.id,
            "suite": self.case.suite,
            "casePath": self.case.source_path,
            "repetition": self.repetition,
            "armRoot": str(self.arm_root),
            "armEnv": self.arm_env,
            "sourceFingerprint": self.source_fingerprint,
        }


def build_cells(
    spec: ExperimentSpec,
    cases: list[EvalCase],
    roots: dict[str, Path],
    fingerprints: dict[str, str],
) -> list[CellSpec]:
    cells: list[CellSpec] = []
    for arm_name in ("base", "candidate"):
        arm = spec.base if arm_name == "base" else spec.candidate
        for model in spec.models:
            for case in cases:
                for repetition in range(1, spec.repetitions + 1):
                    cell_key = sha256_text(
                        "|".join(
                            [
                                spec.spec_hash,
                                arm_name,
                                model,
                                case.id,
                                str(repetition),
                            ]
                        )
                    )
                    source_fingerprint = sha256_text(
                        "|".join(
                            [
                                fingerprints[arm_name],
                                case_fingerprint(case),
                            ]
                        )
                    )
                    cells.append(
                        CellSpec(
                            cell_key=cell_key,
                            experiment_id=spec.experiment_id,
                            experiment_spec_hash=spec.spec_hash,
                            arm=arm_name,
                            model=model,
                            case=case,
                            repetition=repetition,
                            arm_root=roots[arm_name],
                            arm_env=dict(arm.env),
                            source_fingerprint=source_fingerprint,
                        )
                    )
    return cells


@dataclass(slots=True)
class CellResult:
    cell_key: str
    experiment_id: str
    experiment_spec_hash: str
    arm: str
    model: str
    case_id: str
    suite: str
    repetition: int
    status: str
    task_score: float
    hard_gate_passed: bool
    in_accuracy_denominator: bool
    failure_ownership: str
    metrics: dict[str, Any]
    scores: list[dict[str, Any]]
    retry_history: list[dict[str, Any]]
    started_at: str
    finished_at: str
    source_fingerprint: str
    trace_path: str | None = None
    transcript_path: str | None = None
    artifact_refs: list[dict[str, Any]] = field(default_factory=list)
    error: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": "1.0",
            "cellKey": self.cell_key,
            "experimentId": self.experiment_id,
            "experimentSpecHash": self.experiment_spec_hash,
            "arm": self.arm,
            "model": self.model,
            "caseId": self.case_id,
            "suite": self.suite,
            "repetition": self.repetition,
            "status": self.status,
            "taskScore": self.task_score,
            "hardGatePassed": self.hard_gate_passed,
            "inAccuracyDenominator": self.in_accuracy_denominator,
            "failureOwnership": self.failure_ownership,
            "tracePath": self.trace_path,
            "transcriptPath": self.transcript_path,
            "artifactRefs": self.artifact_refs,
            "metrics": self.metrics,
            "scores": self.scores,
            "retryHistory": self.retry_history,
            "startedAt": self.started_at,
            "finishedAt": self.finished_at,
            "sourceFingerprint": self.source_fingerprint,
            "error": self.error,
        }

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> CellResult:
        return cls(
            cell_key=str(raw["cellKey"]),
            experiment_id=str(raw["experimentId"]),
            experiment_spec_hash=str(raw["experimentSpecHash"]),
            arm=str(raw["arm"]),
            model=str(raw["model"]),
            case_id=str(raw["caseId"]),
            suite=str(raw.get("suite", "")),
            repetition=int(raw["repetition"]),
            status=str(raw["status"]),
            task_score=float(raw["taskScore"]),
            hard_gate_passed=bool(raw["hardGatePassed"]),
            in_accuracy_denominator=bool(raw["inAccuracyDenominator"]),
            failure_ownership=str(raw["failureOwnership"]),
            metrics=dict(raw.get("metrics") or {}),
            scores=list(raw.get("scores") or []),
            retry_history=list(raw.get("retryHistory") or []),
            started_at=str(raw["startedAt"]),
            finished_at=str(raw["finishedAt"]),
            source_fingerprint=str(raw["sourceFingerprint"]),
            trace_path=raw.get("tracePath"),
            transcript_path=raw.get("transcriptPath"),
            artifact_refs=list(raw.get("artifactRefs") or []),
            error=raw.get("error"),
        )


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
        delete=False,
    ) as handle:
        temporary = Path(handle.name)
        try:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        except BaseException:
            handle.close()
            temporary.unlink(missing_ok=True)
            raise
    os.replace(temporary, path)


def load_existing_cells(
    output_dir: Path,
    *,
    experiment_spec_hash: str | None = None,
) -> dict[str, CellResult]:
    result: dict[str, CellResult] = {}
    cells_dir = output_dir / "cells"
    if not cells_dir.exists():
        return result
    for path in sorted(cells_dir.glob("*.json")):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            jsonschema.validate(payload, _json_schema("cell.schema.json"))
            cell = CellResult.from_dict(payload)
        except (OSError, ValueError, KeyError, json.JSONDecodeError, jsonschema.ValidationError) as exc:
            raise ValueError(f"invalid existing cell result: {path}: {exc}") from exc
        if (
            experiment_spec_hash is not None
            and cell.experiment_spec_hash != experiment_spec_hash
        ):
            continue
        result[cell.cell_key] = cell
    return result


def reusable_cell(
    cell: CellSpec,
    existing: CellResult | None,
) -> bool:
    if existing is None:
        return False
    if existing.experiment_spec_hash != cell.experiment_spec_hash:
        return False
    if existing.source_fingerprint != cell.source_fingerprint:
        return False
    return existing.status in {
        "passed",
        "failed",
        "unverified",
        "model_error",
    }


def isolated_environment(
    *,
    arm_env: dict[str, str],
    arm_root: Path,
    cell_work_dir: Path,
) -> dict[str, str]:
    keep = {
        "PATH",
        "PATHEXT",
        "SYSTEMROOT",
        "WINDIR",
        "COMSPEC",
        "TEMP",
        "TMP",
        "JAVA_HOME",
        "MAVEN_OPTS",
        "VIRTUAL_ENV",
        "PYTHONPATH",
        "UV_CACHE_DIR",
        "EVAL_DATA_HOME",
    }
    env = {
        key: value
        for key, value in os.environ.items()
        if key in keep or key.startswith("CUTE_EVAL_")
    }
    for key, value in arm_env.items():
        env[key] = value
    home = cell_work_dir / "home"
    home.mkdir(parents=True, exist_ok=True)
    crontab = cell_work_dir / "crontab"
    crontab.mkdir(parents=True, exist_ok=True)
    workspace = cell_work_dir / "workspace"
    artifacts = cell_work_dir / "artifacts"
    workspace.mkdir(parents=True, exist_ok=True)
    artifacts.mkdir(parents=True, exist_ok=True)
    browser_profile = cell_work_dir / "browser-profile"
    browser_profile.mkdir(parents=True, exist_ok=True)
    env.update(
        {
            "HOME": str(home),
            "USERPROFILE": str(home),
            "TMP": str(cell_work_dir / "tmp"),
            "TEMP": str(cell_work_dir / "tmp"),
            "XDG_CONFIG_HOME": str(home / ".config"),
            "XDG_CACHE_HOME": str(home / ".cache"),
            "CUTE_EVAL_REPO_ROOT": str(arm_root),
            "CUTE_EVAL_CELL_HOME": str(home),
            "CUTE_EVAL_CELL_WORKSPACE": str(workspace),
            "CUTE_EVAL_ARTIFACT_DIR": str(artifacts),
            "CUTE_EVAL_BROWSER_PROFILE": str(browser_profile),
            "CUTE_EVAL_CRONTAB_DIR": str(crontab),
            "CUTE_EVAL_BROWSER_DISABLED": "1",
            # Maven exec splits --out paths on spaces. Keep the Java staging
            # directory outside the workspace path, which may contain spaces.
            "CUTE_EVAL_JAVA_TMP": tempfile.mkdtemp(prefix="cute-evals-java-"),
        }
    )
    (cell_work_dir / "tmp").mkdir(parents=True, exist_ok=True)
    return env


def load_cell_case(cell: CellSpec | dict[str, Any]) -> EvalCase:
    if isinstance(cell, CellSpec):
        return cell.case
    return load_case_file(Path(str(cell["casePath"])))
