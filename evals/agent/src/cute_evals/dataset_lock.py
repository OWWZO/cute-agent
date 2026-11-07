from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable
from pathlib import Path
from typing import Any


def dataset_hash(cases_dir: Path, extra_files: Iterable[Path] = ()) -> str:
    """Hash case files plus explicitly included fixture files."""

    digest = hashlib.sha256()
    paths = [*sorted(cases_dir.glob("*.yaml")), *sorted(extra_files)]
    for path in paths:
        digest.update(path.relative_to(cases_dir).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return f"sha256:{digest.hexdigest()}"


def load_dataset_manifest(
    cases_dir: Path,
    *,
    extra_files: Iterable[Path] = (),
    manifest_path: Path | None = None,
) -> dict[str, Any]:
    target = manifest_path or cases_dir.parent / "dataset.json"
    if not target.exists():
        raise FileNotFoundError(f"dataset manifest not found: {target}")
    manifest = json.loads(target.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise TypeError(f"dataset manifest must be an object: {target}")
    actual = dataset_hash(cases_dir, extra_files=extra_files)
    expected = manifest.get("datasetHash")
    if expected != actual:
        raise ValueError(f"dataset hash mismatch: expected {expected}, got {actual}")
    return manifest
