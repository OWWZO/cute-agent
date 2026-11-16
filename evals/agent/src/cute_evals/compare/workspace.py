from __future__ import annotations

import hashlib
import os
import shutil
import stat
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .dataset import CompareCase


@dataclass(slots=True)
class MaterializedWorkspace:
    case_id: str
    path: Path
    tree_hash: str
    template: Path
    read_only_paths: list[str]

    def to_dict(self) -> dict[str, Any]:
        return {
            "caseId": self.case_id,
            "path": str(self.path),
            "treeHash": self.tree_hash,
            "template": str(self.template),
            "readOnlyPaths": self.read_only_paths,
        }


@dataclass(slots=True)
class WorkspaceCheckOutcome:
    passed: bool | None
    status: str
    evidence: dict[str, Any]


def _ensure_within(root: Path, candidate: Path) -> None:
    resolved_root = root.resolve()
    resolved_candidate = candidate.resolve()
    if resolved_candidate != resolved_root and resolved_root not in resolved_candidate.parents:
        raise ValueError(f"path escapes workspace root: {candidate}")


def _make_writable(root: Path) -> None:
    if not root.exists():
        return
    for current_root, dirs, files in os.walk(root):
        for name in files:
            path = Path(current_root) / name
            try:
                path.chmod(path.stat().st_mode | stat.S_IWRITE)
            except OSError:
                pass
        for name in dirs:
            path = Path(current_root) / name
            try:
                path.chmod(path.stat().st_mode | stat.S_IWRITE)
            except OSError:
                pass


def reset_directory(path: Path, *, root: Path) -> None:
    _ensure_within(root, path)
    if path.exists():
        _make_writable(path)
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


def tree_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*"), key=lambda item: item.as_posix()):
        relative = path.relative_to(root).as_posix()
        if path.is_dir():
            digest.update(f"D\0{relative}\0".encode("utf-8"))
            continue
        digest.update(f"F\0{relative}\0".encode("utf-8"))
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return f"sha256:{digest.hexdigest()}"


def materialize_workspace(
    case: CompareCase,
    case_root: Path,
    *,
    root: Path,
) -> MaterializedWorkspace:
    _ensure_within(root, case_root)
    workspace = case_root / "workspace"
    if workspace.exists():
        _make_writable(workspace)
        shutil.rmtree(workspace)
    case_root.mkdir(parents=True, exist_ok=True)
    shutil.copytree(case.workspace_template, workspace)

    for relative in case.workspace_read_only_paths:
        target = (workspace / Path(relative)).resolve()
        _ensure_within(workspace, target)
        if not target.exists():
            raise FileNotFoundError(
                f"read-only workspace path does not exist for {case.id}: {relative}"
            )
        paths = [target, *target.rglob("*")] if target.is_dir() else [target]
        for path in paths:
            if path.is_file():
                path.chmod(path.stat().st_mode & ~stat.S_IWRITE)

    return MaterializedWorkspace(
        case_id=case.id,
        path=workspace,
        tree_hash=tree_hash(workspace),
        template=case.workspace_template,
        read_only_paths=list(case.workspace_read_only_paths),
    )


def check_workspace(
    workspace: Path,
    checks: list[dict[str, Any]],
) -> WorkspaceCheckOutcome | None:
    if not checks:
        return None
    results: list[dict[str, Any]] = []
    passed = True
    unverified = False
    for check in checks:
        relative = str(check["path"])
        target = (workspace / Path(relative)).resolve()
        _ensure_within(workspace, target)
        expected_exists = bool(check.get("exists", True))
        exists = target.exists()
        detail: dict[str, Any] = {
            "path": relative,
            "expectedExists": expected_exists,
            "exists": exists,
            "contains": list(check.get("contains") or []),
            "notContains": list(check.get("notContains") or []),
        }
        if exists != expected_exists:
            passed = False
            detail["passed"] = False
            results.append(detail)
            continue
        if not exists:
            detail["passed"] = True
            results.append(detail)
            continue
        try:
            content = target.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            unverified = True
            detail.update({"passed": None, "error": f"{type(exc).__name__}: {exc}"})
            results.append(detail)
            continue
        missing = [value for value in detail["contains"] if value not in content]
        forbidden = [
            value for value in detail["notContains"] if value in content
        ]
        detail["missingContains"] = missing
        detail["matchedNotContains"] = forbidden
        detail["passed"] = not missing and not forbidden
        if not detail["passed"]:
            passed = False
        results.append(detail)

    status = "unverified" if unverified else "verified"
    return WorkspaceCheckOutcome(
        passed=None if unverified else passed,
        status=status,
        evidence={"checks": results},
    )


def snapshot_workspace(workspace: Path, destination: Path) -> None:
    _make_writable(destination)
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(workspace, destination)
