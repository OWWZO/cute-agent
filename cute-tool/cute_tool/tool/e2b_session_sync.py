# -*- coding: utf-8 -*-
"""会话目录双向同步：本地 workspace <-> E2B sessions/{sessionId}/。

本地工作区是用户可见的真相源，E2B 远端是执行镜像。普通业务文件（含
``output/**``）双向同步；``input/**`` 只向远端推送；``skills/**`` 由 bash
的专用 skill 同步流程处理。
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shlex
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

from loguru import logger

from cute_tool.tool.e2b_file_upload import write_e2b_files
from cute_tool.tool.sandbox_backend_config import get_e2b_workdir
from cute_tool.util.path_safety import safe_path_segment

SKILLS_DIR = "skills"
SYNC_STATE_VERSION = 2
_SKIP_DIR_NAMES = frozenset(
    {
        ".venv",
        "venv",
        "node_modules",
        "__pycache__",
        ".git",
        ".cache",
        ".pytest_cache",
        ".mypy_cache",
        "dist",
        "build",
    }
)
_E2B_MAX_NAME_BYTES = 200
_E2B_MAX_PATH_BYTES = 1000
_FINGERPRINT_REL = ".cute/e2b-sync.json"
_DEFAULT_MAX_FILE_BYTES = 100 * 1024 * 1024
_SKIP_FILE_NAMES = {"__last_source__.py"}
_SAFE_REMOTE_RELATIVE_PATH = re.compile(
    r"^(?!\.\.)(?!/)(?!\\)(?:[^\x00-\x1f:*?\"<>|\\]+/)*[^\x00-\x1f:*?\"<>|\\]+$"
)


def _deletion_sync_enabled() -> bool:
    raw = (os.getenv("E2B_WORKSPACE_DELETE_SYNC_ENABLED") or "true").strip()
    return raw.lower() not in {"0", "false", "no", "off"}


@dataclass
class FileFingerprint:
    size: int
    mtime_ns: int
    sha256: str = ""

    def changed_from(self, other: Optional["FileFingerprint"]) -> bool:
        if other is None:
            return True
        if self.size != other.size or self.mtime_ns != other.mtime_ns:
            return True
        return bool(self.sha256 and other.sha256 and self.sha256 != other.sha256)


@dataclass
class SyncState:
    version: int = SYNC_STATE_VERSION
    owner_key: str = ""
    sandbox_id: str = ""
    generation: int = 0
    local: Dict[str, FileFingerprint] = field(default_factory=dict)
    remote: Dict[str, Tuple[int, int]] = field(default_factory=dict)
    last_run_id: str = ""

    @property
    def files(self) -> Dict[str, FileFingerprint]:
        """v1 兼容别名；旧调用方只认识 files。"""
        return self.local


# v1 名称保留，避免已有调用方和测试一次性断裂。
SyncManifest = SyncState


@dataclass
class SyncResult:
    backend: str = "e2b"
    uploaded: List[str] = field(default_factory=list)
    downloaded: List[str] = field(default_factory=list)
    deleted_local: List[str] = field(default_factory=list)
    deleted_remote: List[str] = field(default_factory=list)
    ignored_deletes: List[str] = field(default_factory=list)
    conflicts: List[Dict[str, str]] = field(default_factory=list)
    skipped_large_files: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)
    state_version: int = SYNC_STATE_VERSION
    deletion_sync_enabled: bool = field(default_factory=_deletion_sync_enabled)

    def merge(self, other: "SyncResult") -> None:
        self.uploaded.extend(other.uploaded)
        self.downloaded.extend(other.downloaded)
        self.deleted_local.extend(other.deleted_local)
        self.deleted_remote.extend(other.deleted_remote)
        self.ignored_deletes.extend(other.ignored_deletes)
        self.conflicts.extend(other.conflicts)
        self.skipped_large_files.extend(other.skipped_large_files)
        self.warnings.extend(other.warnings)

    def error_code(self) -> str:
        if self.conflicts:
            codes = {item.get("code", "") for item in self.conflicts}
            if "WORKSPACE_DELETE_CONFLICT" in codes:
                return "WORKSPACE_DELETE_CONFLICT"
            if "WORKSPACE_WRITE_CONFLICT" in codes:
                return "WORKSPACE_WRITE_CONFLICT"
        return "WORKSPACE_SYNC_FAILED"

    def to_workspace_sync(self) -> Dict[str, Any]:
        status = "conflict" if self.conflicts else "ok"
        payload: Dict[str, Any] = {
            "backend": self.backend,
            "status": status,
            "uploaded": list(dict.fromkeys(self.uploaded)),
            "downloaded": list(dict.fromkeys(self.downloaded)),
            "deletedLocal": list(dict.fromkeys(self.deleted_local)),
            "deletedRemote": list(dict.fromkeys(self.deleted_remote)),
            "ignoredDeletes": list(dict.fromkeys(self.ignored_deletes)),
            "conflicts": list(self.conflicts),
            "skippedLargeFiles": list(dict.fromkeys(self.skipped_large_files)),
            "warnings": list(dict.fromkeys(self.warnings)),
            "stateVersion": self.state_version,
            "deletionSyncEnabled": self.deletion_sync_enabled,
        }
        if self.conflicts:
            payload["errorCode"] = self.error_code()
        return payload


class WorkspaceSyncError(RuntimeError):
    def __init__(self, code: str, message: str, result: SyncResult | None = None):
        super().__init__(message)
        self.code = code
        self.result = result or SyncResult()


def normalize_session_id(session_id: str) -> str:
    text = (session_id or "").strip()
    return safe_path_segment(text, label="session id") if text else "anonymous"


def session_remote_root(session_id: str, workdir: str | None = None) -> str:
    root = (workdir or get_e2b_workdir()).rstrip("/")
    remote_root = f"{root}/sessions/{normalize_session_id(session_id)}"
    if not e2b_remote_path_ok(remote_root):
        raise ValueError("session remote root is not a safe path")
    return remote_root


def fingerprint_path(workspace: Path) -> Path:
    return workspace / _FINGERPRINT_REL


def _normalise_rel(rel: Any) -> str:
    return str(rel or "").replace("\\", "/").strip("/")


def load_sync_state(workspace: Path) -> tuple[SyncState, bool, str]:
    """读取状态；返回 (state, usable, warning)。损坏/缺失时不推导删除。"""
    path = fingerprint_path(workspace)
    if not path.is_file():
        return SyncState(), False, ""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        return SyncState(), False, f"sync state is unreadable: {exc}"
    if not isinstance(raw, dict):
        return SyncState(), False, "sync state root is not an object"

    local: Dict[str, FileFingerprint] = {}
    raw_local = raw.get("local")
    if not isinstance(raw_local, dict):
        # v1 compatibility: files is the local baseline.
        raw_local = raw.get("files") or {}
    for rel, item in dict(raw_local).items():
        if not isinstance(item, dict):
            continue
        local[_normalise_rel(rel)] = FileFingerprint(
            size=int(item.get("size") or 0),
            mtime_ns=int(item.get("mtimeNs") or item.get("mtime_ns") or 0),
            sha256=str(item.get("sha256") or ""),
        )

    remote: Dict[str, Tuple[int, int]] = {}
    for rel, item in dict(raw.get("remote") or {}).items():
        if isinstance(item, dict):
            size = int(item.get("size") or 0)
            mtime_ns = int(item.get("mtimeNs") or item.get("mtime_ns") or 0)
        elif isinstance(item, (list, tuple)) and len(item) >= 2:
            size, mtime_ns = int(item[0]), int(item[1])
        else:
            continue
        remote[_normalise_rel(rel)] = (size, mtime_ns)

    state = SyncState(
        version=int(raw.get("version") or 1),
        owner_key=str(raw.get("ownerKey") or raw.get("owner_key") or ""),
        sandbox_id=str(raw.get("sandboxId") or raw.get("sandbox_id") or ""),
        generation=int(raw.get("generation") or 0),
        local=local,
        remote=remote,
        last_run_id=str(raw.get("lastRunId") or raw.get("last_run_id") or ""),
    )
    return state, True, ""


def load_manifest(workspace: Path) -> SyncState:
    return load_sync_state(workspace)[0]


def save_manifest(workspace: Path, manifest: SyncState) -> None:
    path = fingerprint_path(workspace)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "version": SYNC_STATE_VERSION,
        "ownerKey": manifest.owner_key,
        "sandboxId": manifest.sandbox_id,
        "generation": manifest.generation,
        "local": {
            rel: {
                "size": item.size,
                "mtimeNs": item.mtime_ns,
                "sha256": item.sha256,
            }
            for rel, item in sorted(manifest.local.items())
        },
        "remote": {
            rel: {"size": signature[0], "mtimeNs": signature[1]}
            for rel, signature in sorted(manifest.remote.items())
        },
        "lastRunId": manifest.last_run_id,
    }
    temp_path = path.with_name(f"{path.name}.tmp")
    temp_path.write_text(
        json.dumps(payload, ensure_ascii=True, indent=2), encoding="utf-8"
    )
    os.replace(temp_path, path)


def file_sig(path: Path) -> Tuple[int, int]:
    st = path.stat()
    return int(st.st_size), int(
        getattr(st, "st_mtime_ns", int(st.st_mtime * 1_000_000_000))
    )


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(65536)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _file_fingerprint(path: Path, *, include_hash: bool = True) -> FileFingerprint:
    size, mtime_ns = file_sig(path)
    return FileFingerprint(
        size=size,
        mtime_ns=mtime_ns,
        sha256=file_sha256(path) if include_hash else "",
    )


def _max_file_bytes() -> int:
    raw = (os.getenv("E2B_SYNC_MAX_FILE_BYTES") or "").strip()
    try:
        return max(1, int(raw)) if raw else _DEFAULT_MAX_FILE_BYTES
    except ValueError:
        return _DEFAULT_MAX_FILE_BYTES


def utf8_len(text: str) -> int:
    return len((text or "").encode("utf-8"))


def e2b_path_component_ok(name: str) -> bool:
    if not name or name in {".", ".."}:
        return False
    if utf8_len(name) > _E2B_MAX_NAME_BYTES:
        return False
    if "\x00" in name or "/" in name or "\\" in name:
        return False
    return True


def e2b_remote_path_ok(remote_path: str) -> bool:
    if not remote_path or utf8_len(remote_path) > _E2B_MAX_PATH_BYTES:
        return False
    normalized = remote_path.replace("\\", "/").strip()
    for part in normalized.split("/"):
        if not part or part == ".":
            continue
        if not e2b_path_component_ok(part):
            return False
    return True


def _is_safe_remote_relative_path(rel: Any) -> bool:
    text = str(rel)
    if not text or "\x00" in text or "\\" in text:
        return False
    if not _SAFE_REMOTE_RELATIVE_PATH.fullmatch(text):
        return False
    if any(part in {"", ".", ".."} for part in text.split("/")):
        return False
    return all(e2b_path_component_ok(part) for part in text.split("/"))


def should_skip_rel(rel: Path, *, skip_top: set[str] | None = None) -> bool:
    if not rel.parts:
        return True
    if skip_top and rel.parts[0] in skip_top:
        return True
    # code_execution rewrites this generated source before each sync. It is
    # execution state, not a workspace file, so it must not participate in
    # create/delete reconciliation.
    if rel.name in _SKIP_FILE_NAMES:
        return True
    for part in rel.parts:
        if part.startswith("."):
            return True
        if part in _SKIP_DIR_NAMES:
            return True
    return False


def scan_local_files(
    root: Path, *, skip_top: set[str] | None = None
) -> Dict[str, Path]:
    found: Dict[str, Path] = {}
    if not root.is_dir():
        return found
    resolved_root = root.resolve()
    for path in root.rglob("*"):
        if not path.is_file() or path.is_symlink():
            continue
        try:
            rel = path.resolve().relative_to(resolved_root)
        except ValueError:
            continue
        if should_skip_rel(rel, skip_top=skip_top):
            continue
        found[rel.as_posix()] = path
    return found


def snapshot_local_files(
    root: Path,
    *,
    skip_top: set[str] | None = None,
    skipped_large: Optional[List[str]] = None,
) -> Dict[str, FileFingerprint]:
    snapshot: Dict[str, FileFingerprint] = {}
    limit = _max_file_bytes()
    for rel, path in scan_local_files(root, skip_top=skip_top).items():
        try:
            size, _ = file_sig(path)
            if size > limit:
                if skipped_large is not None:
                    skipped_large.append(rel)
                continue
            snapshot[rel] = _file_fingerprint(path)
        except OSError as exc:
            logger.warning("[e2b_sync] fingerprint {} failed: {}", path, exc)
    return snapshot


def mkdir_remote(sandbox: Any, remote_root: str) -> None:
    if not e2b_remote_path_ok(remote_root):
        raise ValueError("remote root is not a safe path")
    commands = getattr(sandbox, "commands", None)
    run = getattr(commands, "run", None) if commands is not None else None
    if not callable(run):
        raise RuntimeError("E2B sandbox does not support commands.run")
    result = run(
        f"mkdir -p {shlex.quote(remote_root)}/skills "
        f"{shlex.quote(remote_root)}/input "
        f"{shlex.quote(remote_root)}/output",
        timeout=30,
    )
    exit_code = getattr(result, "exit_code", None)
    if exit_code not in (None, 0) or getattr(result, "error", None):
        detail = " ".join(
            part.strip()
            for part in (
                str(getattr(result, "stdout", "") or ""),
                str(getattr(result, "stderr", "") or ""),
            )
            if part and part.strip()
        )
        raise RuntimeError(detail or f"E2B mkdir failed with exit code {exit_code}")


def run_remote_python(sandbox: Any, script: str, *, timeout: int = 60) -> str:
    commands = getattr(sandbox, "commands", None)
    run = getattr(commands, "run", None) if commands is not None else None
    if not callable(run):
        raise RuntimeError("E2B sandbox does not support commands.run")
    result = run(f"python -c {shlex.quote(script)}", timeout=timeout)
    exit_code = getattr(result, "exit_code", None)
    if exit_code not in (None, 0) or getattr(result, "error", None):
        detail = " ".join(
            part.strip()
            for part in (
                str(getattr(result, "stdout", "") or ""),
                str(getattr(result, "stderr", "") or ""),
            )
            if part and part.strip()
        )
        raise RuntimeError(
            detail or f"E2B Python CLI failed with exit code {exit_code}"
        )
    return str(getattr(result, "stdout", "") or "")


def write_files(sandbox: Any, files: list[dict[str, Any]], *, label: str) -> None:
    files_api = getattr(sandbox, "files", None)
    if files_api is None:
        raise RuntimeError("E2B sandbox has no files API")
    safe = [item for item in files if e2b_remote_path_ok(str(item.get("path") or ""))]
    if not safe:
        return
    write_e2b_files(files_api, safe, label=label)


def remove_remote_file(sandbox: Any, remote_path: str) -> None:
    if not e2b_remote_path_ok(remote_path):
        raise ValueError("remote path is not safe")
    files_api = getattr(sandbox, "files", None)
    remover = getattr(files_api, "remove", None) if files_api is not None else None
    if callable(remover):
        remover(remote_path)
        return
    commands = getattr(sandbox, "commands", None)
    run = getattr(commands, "run", None) if commands is not None else None
    if not callable(run):
        raise RuntimeError("E2B sandbox has no remote remove capability")
    result = run(f"rm -f {shlex.quote(remote_path)}", timeout=30)
    exit_code = getattr(result, "exit_code", None)
    if exit_code not in (None, 0) or getattr(result, "error", None):
        raise RuntimeError(f"E2B remove failed with exit code {exit_code}")


def snapshot_remote_files(
    sandbox: Any,
    remote_root: str,
    *,
    skip_top: Iterable[str] = ("input",),
) -> Dict[str, Tuple[int, int]]:
    skip = list(skip_top)
    script = f"""
import json
from pathlib import Path
root = Path({remote_root!r})
skip_top = set({skip!r})
skip_dirs = set({sorted(_SKIP_DIR_NAMES)!r})
skip_files = set({sorted(_SKIP_FILE_NAMES)!r})
files = {{}}
if root.is_dir():
    for path in root.rglob('*'):
        if not path.is_file() or path.is_symlink():
            continue
        try:
            rel = path.resolve().relative_to(root.resolve())
        except ValueError:
            continue
        parts = rel.parts
        if (
            not parts
            or parts[0] in skip_top
            or rel.name in skip_files
            or any(p.startswith('.') for p in parts)
        ):
            continue
        if any(p in skip_dirs for p in parts):
            continue
        st = path.stat()
        files[rel.as_posix()] = [int(st.st_size), int(st.st_mtime_ns)]
print('__SESSION_SNAPSHOT__' + json.dumps(files, ensure_ascii=True))
"""
    stdout = run_remote_python(sandbox, script, timeout=60)
    for line in reversed(stdout.splitlines()):
        for marker in (
            "__SESSION_SNAPSHOT__",
            "__BASH_WORKSPACE_SNAPSHOT__",
            "__SANDBOX_SNAPSHOT__",
        ):
            if marker in line:
                raw = json.loads(line.split(marker, 1)[1].strip() or "{}")
                return {str(k): (int(v[0]), int(v[1])) for k, v in raw.items()}
    raise RuntimeError("E2B workspace snapshot output was missing its marker")


def _safe_local_path(workspace: Path, rel: str) -> Path:
    if not _is_safe_remote_relative_path(rel):
        raise WorkspaceSyncError("WORKSPACE_PATH_REJECTED", f"unsafe workspace path: {rel}")
    root = workspace.resolve()
    target = (root / rel).resolve()
    try:
        target.relative_to(root)
    except ValueError as exc:
        raise WorkspaceSyncError(
            "WORKSPACE_PATH_REJECTED", f"path escapes workspace: {rel}"
        ) from exc
    current = target
    while current != root:
        if current.is_symlink():
            raise WorkspaceSyncError(
                "WORKSPACE_PATH_REJECTED", f"symlink is not allowed: {rel}"
            )
        current = current.parent
    return target


def _read_remote_file(sandbox: Any, remote_path: str) -> bytes:
    files_api = getattr(sandbox, "files", None)
    if files_api is None:
        raise RuntimeError("E2B sandbox has no files API")
    try:
        content = files_api.read(remote_path, format="bytes")
    except TypeError:
        content = files_api.read(remote_path)
    return content.encode("utf-8") if isinstance(content, str) else bytes(content)


def _remote_matches_local(
    sandbox: Any,
    remote_root: str,
    rel: str,
    fingerprint: Optional[FileFingerprint],
) -> bool:
    if fingerprint is None or not fingerprint.sha256:
        return False
    try:
        data = _read_remote_file(sandbox, f"{remote_root}/{rel}")
    except Exception:
        return False
    return (
        len(data) == fingerprint.size
        and hashlib.sha256(data).hexdigest() == fingerprint.sha256
    )


def _write_local_atomic(workspace: Path, rel: str, data: bytes) -> Path:
    target = _safe_local_path(workspace, rel)
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=".cute-sync-", dir=str(target.parent))
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
        os.replace(temp_name, target)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)
    return target


def _remove_local_file(workspace: Path, rel: str) -> bool:
    target = _safe_local_path(workspace, rel)
    if not target.exists():
        return False
    if target.is_dir():
        raise WorkspaceSyncError(
            "WORKSPACE_PATH_REJECTED", f"expected file but found directory: {rel}"
        )
    target.unlink()
    root = workspace.resolve()
    parent = target.parent
    while parent != root:
        try:
            parent.rmdir()
        except OSError:
            break
        parent = parent.parent
    return True


def _reverse_managed(rel: str) -> bool:
    return (
        rel != SKILLS_DIR
        and not rel.startswith(f"{SKILLS_DIR}/")
        and not rel.startswith("input/")
    )


def _conflict(rel: str, code: str, message: str) -> Dict[str, str]:
    return {"path": rel, "code": code, "message": message}


class SessionWorkspaceMirror:
    """一次 bash/code_execution 调用的统一工作区镜像编排。"""

    def __init__(
        self,
        sandbox: Any,
        workspace: Path,
        session_id: str,
        *,
        owner_key: str,
        sandbox_id: str,
        generation: int,
    ):
        self.sandbox = sandbox
        self.workspace = workspace.resolve()
        self.session_id = normalize_session_id(session_id)
        self.owner_key = owner_key
        self.sandbox_id = sandbox_id
        self.generation = generation
        self.remote_root = session_remote_root(self.session_id)
        self._state = SyncState()
        self._baseline_local: Dict[str, FileFingerprint] = {}
        self._baseline_remote: Dict[str, Tuple[int, int]] = {}
        self._reconciled = False

    def reconcile(self) -> SyncResult:
        mkdir_remote(self.sandbox, self.remote_root)
        result = SyncResult()
        state, usable, warning = load_sync_state(self.workspace)
        if warning:
            result.warnings.append(warning)
        if state.owner_key and state.owner_key != self.owner_key:
            usable = False
            result.warnings.append("sync owner changed; baseline rebuilt")
        if state.sandbox_id and (
            state.sandbox_id != self.sandbox_id
            or state.generation != self.generation
        ):
            usable = False
            result.warnings.append("sandbox generation changed; baseline rebuilt")

        local_before = snapshot_local_files(
            self.workspace,
            skip_top={SKILLS_DIR},
            skipped_large=result.skipped_large_files,
        )
        remote_before = snapshot_remote_files(
            self.sandbox, self.remote_root, skip_top=(SKILLS_DIR,)
        )

        if usable:
            self._recover_remote_changes(state, local_before, remote_before, result)
            local_before = snapshot_local_files(
                self.workspace,
                skip_top={SKILLS_DIR},
                skipped_large=result.skipped_large_files,
            )
            self._delete_remote_for_local_deletes(
                state, local_before, remote_before, result
            )

        state.version = SYNC_STATE_VERSION
        state.owner_key = self.owner_key
        state.sandbox_id = self.sandbox_id
        state.generation = self.generation
        if not usable:
            state.local = {}
            state.remote = {}

        local_current = snapshot_local_files(
            self.workspace,
            skip_top={SKILLS_DIR},
            skipped_large=result.skipped_large_files,
        )
        remote_current = snapshot_remote_files(
            self.sandbox, self.remote_root, skip_top=(SKILLS_DIR,)
        )
        self._push_local_changes(state, local_current, remote_current, result)

        self._baseline_local = snapshot_local_files(
            self.workspace,
            skip_top={SKILLS_DIR},
            skipped_large=result.skipped_large_files,
        )
        self._baseline_remote = snapshot_remote_files(
            self.sandbox, self.remote_root, skip_top=(SKILLS_DIR,)
        )
        skill_state = {
            rel: fingerprint
            for rel, fingerprint in state.local.items()
            if rel == SKILLS_DIR or rel.startswith(f"{SKILLS_DIR}/")
        }
        state.local = dict(self._baseline_local)
        state.local.update(skill_state)
        state.remote = dict(self._baseline_remote)
        save_manifest(self.workspace, state)
        self._state = state
        self._reconciled = True
        return result

    def snapshot_remote(self) -> Dict[str, Tuple[int, int]]:
        return snapshot_remote_files(
            self.sandbox, self.remote_root, skip_top=(SKILLS_DIR,)
        )

    def apply_remote_changes(
        self,
        before_remote: Optional[Dict[str, Tuple[int, int]]] = None,
        *,
        produced_paths: Optional[List[Path]] = None,
    ) -> SyncResult:
        if not self._reconciled:
            raise WorkspaceSyncError(
                "WORKSPACE_STATE_INCONSISTENT",
                "workspace mirror reconcile must run before execution",
            )
        result = SyncResult()
        before = before_remote if before_remote is not None else self._baseline_remote
        after_remote = self.snapshot_remote()
        local_before = self._baseline_local
        local_after = snapshot_local_files(self.workspace, skip_top={SKILLS_DIR})

        for rel, signature in sorted(after_remote.items()):
            if before.get(rel) == signature:
                continue
            if (
                rel.startswith("input/")
                or rel == SKILLS_DIR
                or rel.startswith(f"{SKILLS_DIR}/")
            ):
                continue
            if local_after.get(rel) != local_before.get(rel):
                if _remote_matches_local(
                    self.sandbox, self.remote_root, rel, local_after.get(rel)
                ):
                    continue
                local_deleted = (
                    local_after.get(rel) is None
                    and local_before.get(rel) is not None
                )
                result.conflicts.append(
                    _conflict(
                        rel,
                        "WORKSPACE_DELETE_CONFLICT"
                        if local_deleted
                        else "WORKSPACE_WRITE_CONFLICT",
                        "local deleted while remote changed"
                        if local_deleted
                        else "remote and local changed during execution",
                    )
                )
                continue
            try:
                data = _read_remote_file(self.sandbox, f"{self.remote_root}/{rel}")
                local_path = _write_local_atomic(self.workspace, rel, data)
                result.downloaded.append(rel)
                if produced_paths is not None:
                    produced_paths.append(local_path)
            except WorkspaceSyncError:
                raise
            except Exception as exc:
                result.conflicts.append(
                    _conflict(
                        rel,
                        "WORKSPACE_SYNC_FAILED",
                        f"download failed: {exc}",
                    )
                )

        for rel in sorted(set(before) - set(after_remote)):
            if not _is_safe_remote_relative_path(rel):
                raise WorkspaceSyncError(
                    "WORKSPACE_PATH_REJECTED",
                    f"unsafe workspace path: {rel}",
                    result,
                )
            if (
                rel.startswith("input/")
                or rel == SKILLS_DIR
                or rel.startswith(f"{SKILLS_DIR}/")
            ):
                result.ignored_deletes.append(rel)
                continue
            if not result.deletion_sync_enabled:
                result.ignored_deletes.append(rel)
                continue
            current = local_after.get(rel)
            baseline = local_before.get(rel)
            if current is None:
                continue
            if current != baseline:
                result.conflicts.append(
                    _conflict(
                        rel,
                        "WORKSPACE_DELETE_CONFLICT",
                        "remote deleted a file that changed locally",
                    )
                )
                continue
            try:
                if _remove_local_file(self.workspace, rel):
                    result.deleted_local.append(rel)
            except WorkspaceSyncError:
                raise
            except Exception as exc:
                result.conflicts.append(
                    _conflict(
                        rel,
                        "WORKSPACE_SYNC_FAILED",
                        f"local delete failed: {exc}",
                    )
                )

        if result.conflicts:
            return result

        final_local = snapshot_local_files(self.workspace, skip_top={SKILLS_DIR})
        state = self._state
        skill_state = {
            rel: fingerprint
            for rel, fingerprint in state.local.items()
            if rel == SKILLS_DIR or rel.startswith(f"{SKILLS_DIR}/")
        }
        state.local = dict(final_local)
        state.local.update(skill_state)
        state.remote = dict(after_remote)
        save_manifest(self.workspace, state)
        self._baseline_local = final_local
        self._baseline_remote = after_remote
        return result

    def local_state(self) -> Dict[str, FileFingerprint]:
        return dict(self._state.local)

    def _recover_remote_changes(
        self,
        state: SyncState,
        local: Dict[str, FileFingerprint],
        remote: Dict[str, Tuple[int, int]],
        result: SyncResult,
    ) -> None:
        for rel, signature in sorted(remote.items()):
            if rel.startswith("input/") or rel.startswith(f"{SKILLS_DIR}/"):
                continue
            baseline_remote = state.remote.get(rel)
            if baseline_remote == signature:
                continue
            current_local = local.get(rel)
            baseline_local = state.local.get(rel)
            if baseline_remote is None and current_local is None:
                try:
                    data = _read_remote_file(
                        self.sandbox, f"{self.remote_root}/{rel}"
                    )
                    _write_local_atomic(self.workspace, rel, data)
                    result.downloaded.append(rel)
                except Exception as exc:
                    result.conflicts.append(
                        _conflict(
                            rel,
                            "WORKSPACE_SYNC_FAILED",
                            f"recovery download failed: {exc}",
                        )
                    )
                continue
            if current_local is None and baseline_local is not None:
                result.conflicts.append(
                    _conflict(
                        rel,
                        "WORKSPACE_DELETE_CONFLICT",
                        "remote changed after local deletion",
                    )
                )
                continue
            if current_local != baseline_local:
                if _remote_matches_local(
                    self.sandbox, self.remote_root, rel, current_local
                ):
                    if current_local is not None:
                        state.local[rel] = current_local
                    continue
                result.conflicts.append(
                    _conflict(
                        rel,
                        "WORKSPACE_WRITE_CONFLICT",
                        "remote changed before reconcile while local also changed",
                    )
                )
                continue
            try:
                data = _read_remote_file(
                    self.sandbox, f"{self.remote_root}/{rel}"
                )
                _write_local_atomic(self.workspace, rel, data)
                result.downloaded.append(rel)
            except Exception as exc:
                result.conflicts.append(
                    _conflict(
                        rel,
                        "WORKSPACE_SYNC_FAILED",
                        f"recovery download failed: {exc}",
                    )
                )

        for rel in sorted(set(state.remote) - set(remote)):
            if not _reverse_managed(rel):
                result.ignored_deletes.append(rel)
                continue
            if not result.deletion_sync_enabled:
                result.ignored_deletes.append(rel)
                continue
            current_local = local.get(rel)
            baseline_local = state.local.get(rel)
            if current_local is None:
                continue
            if current_local != baseline_local:
                result.conflicts.append(
                    _conflict(
                        rel,
                        "WORKSPACE_DELETE_CONFLICT",
                        "remote deleted while local changed",
                    )
                )
                continue
            try:
                if _remove_local_file(self.workspace, rel):
                    result.deleted_local.append(rel)
            except Exception as exc:
                result.conflicts.append(
                    _conflict(
                        rel,
                        "WORKSPACE_SYNC_FAILED",
                        f"recovery delete failed: {exc}",
                    )
                )

        if result.conflicts:
            raise WorkspaceSyncError(
                result.error_code(),
                "workspace recovery found conflicting local and remote changes",
                result,
            )
        state.remote = dict(remote)

    def _delete_remote_for_local_deletes(
        self,
        state: SyncState,
        local: Dict[str, FileFingerprint],
        remote: Dict[str, Tuple[int, int]],
        result: SyncResult,
    ) -> None:
        if not result.deletion_sync_enabled:
            return
        for rel in sorted(set(state.local) - set(local)):
            if rel == SKILLS_DIR or rel.startswith(f"{SKILLS_DIR}/"):
                continue
            if rel not in remote:
                continue
            try:
                remove_remote_file(self.sandbox, f"{self.remote_root}/{rel}")
                result.deleted_remote.append(rel)
            except Exception as exc:
                raise WorkspaceSyncError(
                    "WORKSPACE_SYNC_FAILED",
                    f"failed to delete remote file {rel}: {exc}",
                    result,
                ) from exc

    def _push_local_changes(
        self,
        state: SyncState,
        local: Dict[str, FileFingerprint],
        remote: Dict[str, Tuple[int, int]],
        result: SyncResult,
    ) -> None:
        batch: list[dict[str, Any]] = []
        staged: Dict[str, FileFingerprint] = {}
        for rel, fingerprint in sorted(local.items()):
            if rel in state.local and state.local[rel] == fingerprint and rel in remote:
                continue
            path = self.workspace / rel
            remote_path = f"{self.remote_root}/{rel}"
            if not e2b_remote_path_ok(remote_path):
                result.conflicts.append(
                    _conflict(
                        rel,
                        "WORKSPACE_PATH_REJECTED",
                        "remote path is not safe",
                    )
                )
                continue
            batch.append({"path": remote_path, "data": path.read_bytes()})
            staged[rel] = fingerprint
            result.uploaded.append(rel)
            if len(batch) >= 32:
                write_files(self.sandbox, batch, label="session_sync")
                state.local.update(staged)
                batch = []
                staged = {}
        if batch:
            write_files(self.sandbox, batch, label="session_sync")
            state.local.update(staged)

        if result.conflicts:
            raise WorkspaceSyncError(
                result.error_code(), "workspace push rejected unsafe paths", result
            )


def push_workspace(
    sandbox: Any,
    workspace: Path,
    session_id: str,
    *,
    owner_key: str,
    sandbox_id: str,
    generation: int,
    extra_files: Optional[Dict[str, Path]] = None,
) -> Tuple[int, int, int, SyncState]:
    """兼容入口：用统一镜像层完成一次本地 -> 远端 reconcile。"""
    if extra_files:
        for rel, source in extra_files.items():
            target = workspace / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())
    mirror = SessionWorkspaceMirror(
        sandbox,
        workspace,
        session_id,
        owner_key=owner_key,
        sandbox_id=sandbox_id,
        generation=generation,
    )
    result = mirror.reconcile()
    return (
        len(result.uploaded),
        0,
        len(result.deleted_remote),
        mirror._state,
    )


def record_uploaded(
    workspace: Path,
    rel: str,
    path: Path,
    *,
    owner_key: str,
    sandbox_id: str,
    generation: int,
) -> None:
    manifest = load_manifest(workspace)
    manifest.version = SYNC_STATE_VERSION
    manifest.owner_key = owner_key
    manifest.sandbox_id = sandbox_id
    manifest.generation = generation
    manifest.local[_normalise_rel(rel)] = _file_fingerprint(path)
    save_manifest(workspace, manifest)


def download_changed_files(
    sandbox: Any,
    remote_root: str,
    workspace: Path,
    before: Dict[str, Tuple[int, int]],
    produced_paths: Optional[List[Path]] = None,
    *,
    skip_top: Iterable[str] = ("input", SKILLS_DIR),
) -> List[str]:
    """兼容旧入口：只下载新增/修改文件；删除同步由 mirror 负责。"""
    after = snapshot_remote_files(sandbox, remote_root, skip_top=skip_top)
    if getattr(sandbox, "files", None) is None:
        return []
    changed_rels: List[str] = []
    for rel, signature in sorted(after.items()):
        if before.get(rel) == signature:
            continue
        try:
            data = _read_remote_file(sandbox, f"{remote_root}/{rel}")
            local_path = _write_local_atomic(workspace, rel, data)
            if produced_paths is not None:
                produced_paths.append(local_path)
            changed_rels.append(rel)
        except Exception as exc:
            logger.warning("[e2b_sync] download {} failed: {}", rel, exc)
    return changed_rels
