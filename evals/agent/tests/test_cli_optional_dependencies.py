from __future__ import annotations

import builtins
import os
import subprocess
import sys
from pathlib import Path

import pytest

from cute_evals import cli


def test_cli_import_does_not_require_rapidfuzz() -> None:
    src = Path(__file__).resolve().parents[1] / "src"
    script = """
import sys

class BlockRapidFuzz:
    def find_spec(self, fullname, path=None, target=None):
        if fullname == "rapidfuzz" or fullname.startswith("rapidfuzz."):
            raise ModuleNotFoundError(
                "No module named 'rapidfuzz'",
                name="rapidfuzz",
            )
        return None

sys.meta_path.insert(0, BlockRapidFuzz())
from cute_evals.cli import build_parser
args = build_parser().parse_args(["benchmark", "--name", "gaia"])
assert args.name == "gaia"
"""
    env = os.environ.copy()
    env["PYTHONPATH"] = os.pathsep.join(
        [str(src), env.get("PYTHONPATH", "")]
    ).rstrip(os.pathsep)
    completed = subprocess.run(
        [sys.executable, "-c", script],
        text=True,
        capture_output=True,
        env=env,
        check=False,
    )

    assert completed.returncode == 0, completed.stderr


def test_memoryagentbench_reports_missing_rapidfuzz_install(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    real_import = builtins.__import__

    def guarded_import(name, *args, **kwargs):
        if name == "rapidfuzz" or name.startswith("rapidfuzz."):
            raise ModuleNotFoundError(
                "No module named 'rapidfuzz'",
                name="rapidfuzz",
            )
        return real_import(name, *args, **kwargs)

    for module in (
        "cute_evals.benchmarks.memoryagentbench",
        "cute_evals.benchmarks.memoryagentbench.adapter",
        "cute_evals.benchmarks.memoryagentbench.official_recsys",
    ):
        monkeypatch.delitem(sys.modules, module, raising=False)
    monkeypatch.setattr(builtins, "__import__", guarded_import)

    with pytest.raises(
        RuntimeError,
        match=r"install.*cute-evals\[memoryagentbench\]",
    ):
        cli._load_memory_agent_bench_adapter()
