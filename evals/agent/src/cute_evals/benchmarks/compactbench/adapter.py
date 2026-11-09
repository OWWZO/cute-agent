from __future__ import annotations

import importlib.util
import inspect
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"
DEFAULT_METHOD_FILE = PACKAGE_ROOT / "java_method.py"


@dataclass(slots=True)
class CompactBenchRun:
    returncode: int
    output_path: Path
    traces_path: Path | None
    command: list[str]


class CompactBenchAdapter:
    """Bridge the Java compactor into CompactBench's official runner."""

    name = "compactbench"

    def __init__(self, manifest_path: Path = DEFAULT_MANIFEST) -> None:
        self.manifest_path = manifest_path.resolve()
        raw = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise TypeError(f"invalid CompactBench manifest: {self.manifest_path}")
        self.manifest: dict[str, Any] = raw
        self.version = str(raw["version"])
        self.upstream = str(raw["upstream"])
        self.score_label = str(raw["scoreLabel"])
        self.suite_version = str(raw["suiteVersion"])

    @property
    def method_spec(self) -> str:
        return f"{DEFAULT_METHOD_FILE}:{self.manifest['methodClass']}"

    def require_official_package(self) -> None:
        if importlib.util.find_spec("compactbench") is None:
            raise RuntimeError(
                "CompactBench 0.3 is not installed. Install the pinned source "
                "snapshot described in "
                f"{self.manifest_path} (PyPI 0.1.0 uses the obsolete protocol)."
            )
        from compactbench.compactors import Compactor
        from compactbench.contracts import CompactionRequest

        signature = inspect.signature(Compactor.compact)
        if "request" not in signature.parameters or CompactionRequest is None:
            raise RuntimeError(
                "installed CompactBench uses the obsolete compactor protocol; "
                "install the 0.3 source snapshot"
            )

    def command(
        self,
        *,
        source: Path | None = None,
        suite: str = "starter",
        output_path: Path,
        traces_path: Path | None = None,
        budget: int = 2048,
        cases: int | None = None,
        drift_cycles: int | None = None,
        controls: bool = False,
        reader_provider: str | None = None,
        reader_model: str | None = None,
        python_executable: str | None = None,
    ) -> list[str]:
        if source is None:
            self.require_official_package()
            executable = [python_executable or sys.executable, "-m", "compactbench"]
        else:
            executable = [
                "uv",
                "run",
                "--with",
                str(source.resolve()),
                "--with",
                "openai>=3.42",
                "python",
                "-m",
                "compactbench",
            ]
        command = [
            *executable,
            "run",
            "--method",
            self.method_spec,
            "--suite",
            suite,
            "--provider",
            "mock",
            "--model",
            "mock-deterministic",
            "--budget",
            str(budget),
            "--output",
            str(output_path),
        ]
        if traces_path is not None:
            command.extend(["--traces", str(traces_path)])
        if cases is not None:
            command.extend(["--cases", str(cases)])
        if drift_cycles is not None:
            command.extend(["--drift-cycles", str(drift_cycles)])
        if controls:
            command.append("--controls")
        if reader_provider is not None:
            command.extend(["--reader-provider", reader_provider])
        if reader_model is not None:
            command.extend(["--reader-model", reader_model])
        return command

    def run(
        self,
        *,
        source: Path | None = None,
        suite: str = "starter",
        output_path: Path,
        traces_path: Path | None = None,
        budget: int = 2048,
        cases: int | None = None,
        drift_cycles: int | None = None,
        controls: bool = False,
        reader_provider: str | None = None,
        reader_model: str | None = None,
    ) -> CompactBenchRun:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        if traces_path is not None:
            traces_path.parent.mkdir(parents=True, exist_ok=True)
        command = self.command(
            source=source,
            suite=suite,
            output_path=output_path,
            traces_path=traces_path,
            budget=budget,
            cases=cases,
            drift_cycles=drift_cycles,
            controls=controls,
            reader_provider=reader_provider,
            reader_model=reader_model,
        )
        result = subprocess.run(
            command,
            cwd=Path.cwd(),
            check=False,
            env=os.environ.copy(),
        )
        return CompactBenchRun(
            returncode=result.returncode,
            output_path=output_path,
            traces_path=traces_path,
            command=command,
        )
