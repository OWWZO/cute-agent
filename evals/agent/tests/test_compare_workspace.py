from __future__ import annotations

from pathlib import Path

from cute_evals.compare.dataset import load_compare_dataset
from cute_evals.compare.workspace import (
    check_workspace,
    materialize_workspace,
    reset_directory,
    tree_hash,
)


DATASET = (
    Path(__file__).resolve().parents[1]
    / "cases"
    / "compare"
    / "pilot.json"
)


def test_materialize_workspace_is_isolated_and_deterministic(
    tmp_path: Path,
) -> None:
    dataset = load_compare_dataset(DATASET)
    case = next(item for item in dataset.cases if item.id == "compare.code.greeting")

    first = materialize_workspace(case, tmp_path / "first", root=tmp_path)
    second = materialize_workspace(case, tmp_path / "second", root=tmp_path)

    assert first.path != second.path
    assert first.tree_hash == second.tree_hash
    (first.path / "src" / "message.txt").write_text(
        "Hello, new world!",
        encoding="utf-8",
    )
    assert tree_hash(first.path) != second.tree_hash
    assert (second.path / "src" / "message.txt").read_text(
        encoding="utf-8"
    ) == "Hello, old world!\n"


def test_workspace_checks_use_actual_final_files(tmp_path: Path) -> None:
    dataset = load_compare_dataset(DATASET)
    case = next(item for item in dataset.cases if item.id == "compare.code.greeting")
    workspace = materialize_workspace(case, tmp_path / "case", root=tmp_path)

    failed = check_workspace(workspace.path, case.workspace_checks)
    assert failed is not None
    assert failed.passed is False

    (workspace.path / "src" / "message.txt").write_text(
        "Hello, new world!\n",
        encoding="utf-8",
    )
    passed = check_workspace(workspace.path, case.workspace_checks)
    assert passed is not None
    assert passed.passed is True
    assert passed.status == "verified"


def test_workspace_checks_do_not_escape_root(tmp_path: Path) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()

    try:
        check_workspace(workspace, [{"path": "../outside.txt"}])
    except ValueError as exc:
        assert "escapes workspace root" in str(exc)
    else:
        raise AssertionError("path traversal was not rejected")


def test_reset_directory_removes_previous_attempt_files(tmp_path: Path) -> None:
    attempt = tmp_path / "attempts" / "01"
    attempt.mkdir(parents=True)
    (attempt / "stale.txt").write_text("old", encoding="utf-8")

    reset_directory(attempt, root=tmp_path)

    assert attempt.is_dir()
    assert not (attempt / "stale.txt").exists()
