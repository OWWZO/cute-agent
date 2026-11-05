"""Shared validation for externally supplied filesystem path segments."""

from __future__ import annotations

import re
from pathlib import Path

_SAFE_SEGMENT_RE = re.compile(r"^(?!\.)[\w .-]+$", re.UNICODE)


def safe_path_segment(value: object, *, label: str = "path segment") -> str:
    """Return a single safe path component or raise ``ValueError``."""
    text = str(value).strip() if value is not None else ""
    if text in {"", ".", ".."} or not _SAFE_SEGMENT_RE.fullmatch(text):
        raise ValueError(f"{label} must be a single safe path segment")
    return text


def resolve_within_root(
    root: str | Path, *parts: str, label: str = "path"
) -> Path:
    """Resolve ``root / *parts`` and require the result to stay below root."""
    resolved_root = Path(root).expanduser().resolve()
    resolved = resolved_root.joinpath(*parts).resolve()
    if not resolved.is_relative_to(resolved_root):
        raise ValueError(f"{label} escapes allowed root")
    return resolved


def is_path_within_root(path: str | Path, root: str | Path) -> bool:
    """Return whether ``path`` resolves inside ``root``."""
    try:
        Path(path).expanduser().resolve().relative_to(
            Path(root).expanduser().resolve()
        )
    except (OSError, ValueError):
        return False
    return True
