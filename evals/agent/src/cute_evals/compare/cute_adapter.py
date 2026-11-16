from __future__ import annotations

from pathlib import Path

from ..java_runner import run_java_cases
from ..models import EvalCase, RunTrace


def run_cute_batch(
    cases: list[EvalCase],
    workspaces: dict[str, Path],
    output_dir: Path,
    *,
    mode: str,
    profile: str,
    timeout_seconds: int,
    build: bool,
    tool_mode: str,
    model_id: str | None,
) -> dict[str, RunTrace]:
    """Run the existing Java harness once and keep per-case Ledger traces."""
    return run_java_cases(
        cases,
        output_dir,
        mode=mode,
        profile=profile,
        model_id=model_id,
        build=build,
        timeout_seconds=timeout_seconds,
        workspace_roots=workspaces,
        tool_mode=tool_mode,
    )
