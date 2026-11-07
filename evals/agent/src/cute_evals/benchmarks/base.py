from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

from ..models import CaseResult, RunTrace


@dataclass(frozen=True, slots=True)
class BenchmarkTask:
    id: str
    category: str
    question: str
    functions: list[dict[str, Any]]
    ground_truth: list[dict[str, Any]] | None
    raw: dict[str, Any] = field(repr=False)
    turns: list[dict[str, Any]] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class PreparedTask:
    task: BenchmarkTask
    workspace: Path
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class BenchmarkRun:
    task: BenchmarkTask
    trace: RunTrace
    trace_path: str
    calls: list[dict[str, Any]]
    conversation: list[dict[str, Any]] = field(default_factory=list)
    environment_events: list[dict[str, Any]] = field(default_factory=list)
    environment_snapshots: list[dict[str, Any]] = field(default_factory=list)
    oracle: dict[str, Any] = field(default_factory=dict)
    metadata: dict[str, Any] = field(default_factory=dict)


class BenchmarkAdapter(Protocol):
    name: str
    version: str
    upstream: str
    score_label: str

    def tasks(self, split: str) -> list[BenchmarkTask]: ...

    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask: ...

    def fixture_script(self, task: BenchmarkTask) -> dict[str, Any]: ...

    def collect(
        self,
        task: BenchmarkTask,
        trace: RunTrace,
        trace_path: str,
    ) -> BenchmarkRun: ...

    def score(self, run: BenchmarkRun) -> CaseResult: ...

    def dataset_hash(self) -> str: ...

    def report_metadata(self) -> dict[str, Any]: ...
