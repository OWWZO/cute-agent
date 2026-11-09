from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest
import yaml

from cute_evals.benchmarks.marble import MarbleAdapter
from cute_evals.judge_benchmarks import LicenseBlockedError

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


def _fixture_source(tmp_path: Path) -> tuple[Path, Path]:
    source = tmp_path / "source"
    for domain in ("bargaining", "coding", "database", "minecraft", "research"):
        path = source / "multiagentbench" / domain / f"{domain}_main.jsonl"
        path.parent.mkdir(parents=True)
        rows = [
            {
                "scenario": domain,
                "task_id": index,
                "agents": [{"agent_id": "agent1"}, {"agent_id": "agent2"}],
            }
            for index in range(2)
        ]
        path.write_text(
            "".join(json.dumps(row) + "\n" for row in rows),
            encoding="utf-8",
        )
    scripts = {
        "entrypoint": source / "marble" / "main.py",
        "evaluator": source / "marble" / "evaluator" / "evaluator.py",
        "databaseBatchEvaluator": source / "scripts" / "database" / "batch_eval.py",
        "taskConverter": source / "multiagentbench" / "jsonl2yaml.py",
    }
    for path in scripts.values():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# fixture\n", encoding="utf-8")

    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    manifest["licenseStatus"] = "approved"
    manifest["scoreLabel"] = "official-subset"
    for domain, spec in manifest["domains"].items():
        path = source / spec["file"]
        spec["sha256"] = _sha256(path)
        spec["size"] = path.stat().st_size
    for name, spec in manifest["scripts"].items():
        path = scripts[name]
        spec["sha256"] = _sha256(path)
    manifest_path = tmp_path / "manifest.yaml"
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    return source, manifest_path


def test_official_manifest_rejects_license_conflict() -> None:
    adapter = MarbleAdapter(Path("."), MANIFEST)

    assert adapter.report_metadata()["licenseStatus"] == "blocked-license"
    with pytest.raises(LicenseBlockedError, match="license status is blocked-license"):
        adapter.assert_run_allowed()


def test_smoke_selects_one_task_per_official_domain(tmp_path: Path) -> None:
    source, manifest_path = _fixture_source(tmp_path)
    adapter = MarbleAdapter(source, manifest_path)

    tasks = adapter.tasks("smoke")

    assert len(tasks) == 5
    assert {task.domain for task in tasks} == {
        "bargaining",
        "coding",
        "database",
        "minecraft",
        "research",
    }
    assert all(task.agent_count == 2 for task in tasks)
    assert adapter.verify_assets()["domains"]["coding"]["sha256"] == _sha256(
        source / "multiagentbench" / "coding" / "coding_main.jsonl"
    )
