from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pytest
import yaml
from jsonschema import validate

from cute_evals.benchmarks.marble import MarbleAdapter
from cute_evals.benchmarks.marble.bootstrap import (
    KNOWN_EVALUATOR_SYNTAX_ERROR,
    KNOWN_EVALUATOR_SYNTAX_REPAIR,
    _patched_evaluator_text,
)
from cute_evals.benchmarks.marble.runner import (
    MarbleRunBlocked,
    MarbleRunConfig,
    _result_metrics,
    official_source_diagnostics,
    run_marble,
)

MANIFEST = (
    Path(__file__).resolve().parents[1]
    / "src"
    / "cute_evals"
    / "benchmarks"
    / "marble"
    / "manifest.yaml"
)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _fixture_source(
    tmp_path: Path,
    *,
    evaluator_text: str = "def evaluator():\n    return 1\n",
) -> tuple[Path, Path]:
    source = tmp_path / "source"
    for domain in ("bargaining", "coding", "database", "minecraft", "research"):
        path = source / "multiagentbench" / domain / f"{domain}_main.jsonl"
        path.parent.mkdir(parents=True)
        rows = [
            {
                "scenario": domain,
                "task_id": 1,
                "agents": [{"agent_id": "agent1"}, {"agent_id": "agent2"}],
            }
        ]
        path.write_text(json.dumps(rows[0]) + "\n", encoding="utf-8")
    scripts = {
        "entrypoint": (source / "marble" / "main.py", "print('main')\n"),
        "evaluator": (
            source / "marble" / "evaluator" / "evaluator.py",
            evaluator_text,
        ),
        "databaseBatchEvaluator": (
            source / "scripts" / "database" / "batch_eval.py",
            "print('db')\n",
        ),
        "taskConverter": (
            source / "multiagentbench" / "jsonl2yaml.py",
            "print('convert')\n",
        ),
    }
    for path, content in scripts.values():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    manifest["licenseStatus"] = "approved"
    manifest["scoreLabel"] = "official-subset"
    for domain, spec in manifest["domains"].items():
        path = source / spec["file"]
        spec["sha256"] = _sha256(path)
        spec["size"] = path.stat().st_size
    for name, spec in manifest["scripts"].items():
        path = scripts[name][0]
        spec["sha256"] = _sha256(path)
    manifest_path = tmp_path / "manifest.yaml"
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    return source, manifest_path


def test_marble_source_preflight_detects_known_syntax_error(
    tmp_path: Path,
) -> None:
    broken = (
        "def parse():\n"
        "        try:\n"
        "            content = '{}'\n"
        "            if content:\n"
        "                return {}\n"
        f"{KNOWN_EVALUATOR_SYNTAX_ERROR}\n"
        "            else:\n"
        "                return {}\n"
    )
    source, manifest_path = _fixture_source(
        tmp_path,
        evaluator_text=broken,
    )
    adapter = MarbleAdapter(source, manifest_path)

    diagnostics = official_source_diagnostics(adapter)

    assert diagnostics["evaluator"]["status"] == "syntax_error"
    assert diagnostics["entrypoint"]["status"] == "ok"


def test_known_marble_syntax_repair_compiles(tmp_path: Path) -> None:
    broken = (
        "def parse():\n"
        "        try:\n"
        "            content = '{}'\n"
        "            if content:\n"
        "                return {}\n"
        f"{KNOWN_EVALUATOR_SYNTAX_ERROR}\n"
        "        else:\n"
        "            return {}\n"
    )
    path = tmp_path / "evaluator.py"
    path.write_text(broken, encoding="utf-8")

    repaired = _patched_evaluator_text(path)

    assert KNOWN_EVALUATOR_SYNTAX_REPAIR in repaired
    compile(repaired, str(path), "exec")


def test_result_metrics_marks_missing_agent_results_as_degraded() -> None:
    metrics = _result_metrics(
        {
            "iterations": [
                {
                    "task_results": [],
                    "communications": [],
                }
            ],
            "token_usage": 123,
            "task_evaluation": {"innovation": 4},
            "planning_scores": [-1],
            "communication_scores": [-1],
        },
        expected_agent_count=2,
    )

    assert metrics["executionStatus"] == "degraded"
    assert metrics["agentResultCount"] == 0
    assert metrics["observedTaskEvaluation"] == {"innovation": 4}


def test_run_marble_blocks_when_official_source_cannot_compile(
    tmp_path: Path,
) -> None:
    broken = (
        "def parse():\n"
        "    try:\n"
        "        content = '{}'\n"
        "        if content:\n"
        "            return {}\n"
        f"{KNOWN_EVALUATOR_SYNTAX_ERROR}\n"
        "        else:\n"
        "            return {}\n"
    )
    source, manifest_path = _fixture_source(
        tmp_path,
        evaluator_text=broken,
    )
    adapter = MarbleAdapter(source, manifest_path)

    with pytest.raises(MarbleRunBlocked, match="syntax error"):
        run_marble(
            adapter,
            MarbleRunConfig(
                python_executable=Path(sys.executable),
                domain="research",
                task_ids=(1,),
                llm_model="openai/group/deepseek",
                output_dir=tmp_path / "out",
            ),
        )


def test_run_marble_writes_compatibility_report(
    tmp_path: Path,
    monkeypatch,
) -> None:
    source, manifest_path = _fixture_source(tmp_path)
    adapter = MarbleAdapter(source, manifest_path)
    output_dir = tmp_path / "out"

    def fake_run(command, **kwargs):
        if any("jsonl2yaml.py" in str(item) for item in command):
            output_folder = Path(command[command.index("--output_folder") + 1])
            output_folder.mkdir(parents=True, exist_ok=True)
            (output_folder / "task_1.yaml").write_text(
                "task_id: 1\n",
                encoding="utf-8",
            )
            return subprocess.CompletedProcess(command, 0, "converted", "")
        output_dir.mkdir(parents=True, exist_ok=True)
        (output_dir / "official-output.jsonl").write_text(
            json.dumps(
                {
                    "iterations": [
                        {
                            "task_results": [{"agent1": "a"}, {"agent2": "b"}],
                            "communications": ["handoff"],
                        }
                    ],
                    "token_usage": 321,
                    "task_evaluation": {
                        "innovation": 4,
                        "safety": 5,
                        "feasibility": 3,
                    },
                    "planning_scores": [4],
                    "communication_scores": [4],
                }
            )
            + "\n",
            encoding="utf-8",
        )
        return subprocess.CompletedProcess(command, 0, "ran", "")

    monkeypatch.setattr(subprocess, "run", fake_run)
    report = run_marble(
        adapter,
        MarbleRunConfig(
            python_executable=Path(sys.executable),
            domain="research",
            task_ids=(1,),
            llm_model="openai/group/deepseek",
            output_dir=output_dir,
            compatibility_local=True,
            offline_tools=True,
        ),
    )

    assert report["scoreNamespace"] == "agent-benchmark"
    assert report["scoreLabel"] == "compatibility-subset"
    assert report["usagePolicy"]["officialScore"] is False
    assert any(
        "external research tools were disabled" in item
        for item in report["limitations"]
    )
    assert report["tasks"][0]["status"] == "verified"
    assert report["metrics"]["observedOverallScore"] == 4.0

    schema_path = (
        Path(__file__).resolve().parents[1]
        / "schemas"
        / "agent_benchmark.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    validate(report, schema)


def test_run_marble_writes_unverified_report_on_timeout(
    tmp_path: Path,
    monkeypatch,
) -> None:
    source, manifest_path = _fixture_source(tmp_path)
    adapter = MarbleAdapter(source, manifest_path)
    output_dir = tmp_path / "timeout-out"

    def fake_run(command, **kwargs):
        if any("jsonl2yaml.py" in str(item) for item in command):
            output_folder = Path(command[command.index("--output_folder") + 1])
            output_folder.mkdir(parents=True, exist_ok=True)
            (output_folder / "task_1.yaml").write_text(
                "task_id: 1\n",
                encoding="utf-8",
            )
            return subprocess.CompletedProcess(command, 0, "", "")
        raise subprocess.TimeoutExpired(command, timeout=1, output="partial")

    monkeypatch.setattr(subprocess, "run", fake_run)
    report = run_marble(
        adapter,
        MarbleRunConfig(
            python_executable=Path(sys.executable),
            domain="research",
            task_ids=(1,),
            llm_model="openai/group/deepseek",
            output_dir=output_dir,
            compatibility_local=True,
            timeout_seconds=1,
        ),
    )

    assert report["scoreLabel"] == "unverified"
    assert report["tasks"][0]["status"] == "failed"
    assert report["environment"]["timedOut"] is True
    assert (output_dir / "report.json").is_file()
