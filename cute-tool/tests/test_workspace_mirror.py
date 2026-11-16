import json
import re
from pathlib import Path
from types import SimpleNamespace

import pytest

from cute_tool.tool.e2b_session_sync import (
    SessionWorkspaceMirror,
    WorkspaceSyncError,
    load_sync_state,
    save_manifest,
)

_REMOTE_ROOT = "/home/user/workspace/sessions/mirror-session"


class _FakeFiles:
    def __init__(self):
        self.store: dict[str, list] = {}

    def write_files(self, files, **kwargs):
        for item in files:
            data = item["data"]
            raw = data.encode("utf-8") if isinstance(data, str) else bytes(data)
            previous = self.store.get(item["path"])
            version = (previous[1] + 1) if previous else 1
            self.store[item["path"]] = [raw, version]

    def read(self, path, format=None):
        return self.store[path][0]

    def remove(self, path):
        self.store.pop(path, None)

    def touch(self, path, data: bytes):
        previous = self.store.get(path)
        version = (previous[1] + 1) if previous else 1
        self.store[path] = [data, version]


class _FakeCommands:
    def __init__(self, files):
        self.files = files

    def run(self, command, cwd=None, timeout=None):
        if "__SESSION_SNAPSHOT__" not in command:
            return SimpleNamespace(exit_code=0, stdout="", stderr="")
        match = re.search(
            r"(/home/user/workspace/sessions/[A-Za-z0-9._-]+)", command
        )
        root = match.group(1).rstrip("/") if match else ""
        files = {}
        for path, (data, version) in self.files.store.items():
            prefix = f"{root}/"
            if not path.startswith(prefix):
                continue
            rel = path[len(prefix) :]
            if (
                not rel
                or rel.startswith("input/")
                or rel.startswith("skills/")
                or any(part.startswith(".") for part in rel.split("/"))
            ):
                continue
            files[rel] = [len(data), version]
        return SimpleNamespace(
            exit_code=0,
            stdout="__SESSION_SNAPSHOT__" + json.dumps(files) + "\n",
            stderr="",
        )


class _FakeSandbox:
    def __init__(self):
        self.files = _FakeFiles()
        self.commands = _FakeCommands(self.files)


def _mirror(workspace: Path, sandbox: _FakeSandbox, generation=1):
    return SessionWorkspaceMirror(
        sandbox,
        workspace,
        "mirror-session",
        owner_key="user:test",
        sandbox_id="sandbox-1",
        generation=generation,
    )


def test_remote_delete_removes_local_file(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    target = workspace / "tmp" / "remove-me.txt"
    target.parent.mkdir()
    target.write_text("remove", encoding="utf-8")
    sandbox = _FakeSandbox()
    mirror = _mirror(workspace, sandbox)
    mirror.reconcile()
    before = mirror.snapshot_remote()

    sandbox.files.remove(f"{_REMOTE_ROOT}/tmp/remove-me.txt")
    result = mirror.apply_remote_changes(before)

    assert result.deleted_local == ["tmp/remove-me.txt"]
    assert not target.exists()


def test_remote_delete_does_not_remove_local_input(tmp_path):
    workspace = tmp_path / "workspace"
    source = workspace / "input" / "source.csv"
    source.parent.mkdir(parents=True)
    source.write_text("a,1\n", encoding="utf-8")
    sandbox = _FakeSandbox()
    mirror = _mirror(workspace, sandbox)
    mirror.reconcile()

    result = mirror.apply_remote_changes(
        {"input/source.csv": (4, 1)}
    )

    assert result.ignored_deletes == ["input/source.csv"]
    assert source.is_file()


def test_remote_delete_with_concurrent_local_write_is_conflict(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    target = workspace / "a.txt"
    target.write_text("before", encoding="utf-8")
    sandbox = _FakeSandbox()
    mirror = _mirror(workspace, sandbox)
    mirror.reconcile()
    before = mirror.snapshot_remote()

    sandbox.files.remove(f"{_REMOTE_ROOT}/a.txt")
    target.write_text("local-edit", encoding="utf-8")
    result = mirror.apply_remote_changes(before)

    assert result.error_code() == "WORKSPACE_DELETE_CONFLICT"
    assert target.read_text(encoding="utf-8") == "local-edit"


def test_crash_after_remote_write_is_idempotent(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    target = workspace / "a.txt"
    target.write_text("before", encoding="utf-8")
    sandbox = _FakeSandbox()
    mirror = _mirror(workspace, sandbox)
    mirror.reconcile()

    sandbox.files.touch(f"{_REMOTE_ROOT}/a.txt", b"remote-new")
    target.write_text("remote-new", encoding="utf-8")
    result = _mirror(workspace, sandbox).reconcile()

    assert result.conflicts == []
    assert target.read_text(encoding="utf-8") == "remote-new"
    assert not result.uploaded


def test_remote_write_with_concurrent_local_delete_is_conflict(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    target = workspace / "a.txt"
    target.write_text("before", encoding="utf-8")
    sandbox = _FakeSandbox()
    mirror = _mirror(workspace, sandbox)
    mirror.reconcile()
    before = mirror.snapshot_remote()

    sandbox.files.touch(f"{_REMOTE_ROOT}/a.txt", b"remote-new")
    target.unlink()
    result = mirror.apply_remote_changes(before)

    assert result.error_code() == "WORKSPACE_DELETE_CONFLICT"
    assert not target.exists()


def test_local_delete_is_pushed_to_remote(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    target = workspace / "old.txt"
    target.write_text("old", encoding="utf-8")
    sandbox = _FakeSandbox()
    mirror = _mirror(workspace, sandbox)
    mirror.reconcile()

    target.unlink()
    result = _mirror(workspace, sandbox).reconcile()

    assert result.deleted_remote == ["old.txt"]
    assert f"{_REMOTE_ROOT}/old.txt" not in sandbox.files.store


def test_remote_delete_rejects_path_traversal(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    sandbox = _FakeSandbox()
    mirror = _mirror(workspace, sandbox)
    mirror.reconcile()

    with pytest.raises(WorkspaceSyncError, match="unsafe workspace path"):
        mirror.apply_remote_changes({"../outside.txt": (1, 1)})

    assert not (tmp_path / "outside.txt").exists()


def test_corrupt_state_rebuilds_without_inferring_deletes(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    local = workspace / "local.txt"
    local.write_text("local", encoding="utf-8")
    stale_remote = f"{_REMOTE_ROOT}/stale.txt"
    sandbox = _FakeSandbox()
    sandbox.files.touch(stale_remote, b"stale")
    state_path = workspace / ".cute" / "e2b-sync.json"
    state_path.parent.mkdir()
    state_path.write_text("{broken", encoding="utf-8")

    result = _mirror(workspace, sandbox).reconcile()

    assert result.deleted_local == []
    assert result.deleted_remote == []
    assert local.is_file()
    assert stale_remote in sandbox.files.store
    assert any("unreadable" in warning for warning in result.warnings)


def test_state_v1_is_readable_as_local_baseline(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    state_path = workspace / ".cute" / "e2b-sync.json"
    state_path.parent.mkdir()
    state_path.write_text(
        json.dumps(
            {
                "ownerKey": "user:test",
                "sandboxId": "sandbox-1",
                "generation": 1,
                "files": {
                    "a.txt": {"size": 1, "mtimeNs": 2, "sha256": "abc"}
                },
            }
        ),
        encoding="utf-8",
    )

    state, usable, warning = load_sync_state(workspace)

    assert usable
    assert warning == ""
    assert state.version == 1
    assert state.local["a.txt"].sha256 == "abc"


def test_new_sandbox_generation_does_not_infer_remote_deletes(tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    local = workspace / "a.txt"
    local.write_text("a", encoding="utf-8")
    sandbox = _FakeSandbox()
    old = _mirror(workspace, sandbox, generation=1)
    old.reconcile()
    save_manifest(workspace, old._state)
    sandbox.files.remove(f"{_REMOTE_ROOT}/a.txt")

    result = _mirror(workspace, sandbox, generation=2).reconcile()

    assert result.deleted_local == []
    assert local.is_file()
    assert f"{_REMOTE_ROOT}/a.txt" in sandbox.files.store


def test_deletion_sync_can_be_temporarily_disabled(tmp_path, monkeypatch):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    target = workspace / "a.txt"
    target.write_text("a", encoding="utf-8")
    sandbox = _FakeSandbox()
    mirror = _mirror(workspace, sandbox)
    monkeypatch.setenv("E2B_WORKSPACE_DELETE_SYNC_ENABLED", "false")
    mirror.reconcile()
    before = mirror.snapshot_remote()

    sandbox.files.remove(f"{_REMOTE_ROOT}/a.txt")
    result = mirror.apply_remote_changes(before)

    assert result.deletion_sync_enabled is False
    assert result.ignored_deletes == ["a.txt"]
    assert target.is_file()
