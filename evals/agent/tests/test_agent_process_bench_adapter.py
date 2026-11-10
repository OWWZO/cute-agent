from __future__ import annotations

import hashlib
import json
from pathlib import Path

import yaml

from cute_evals.judge_benchmarks.agent_process_bench import (
    AgentProcessBenchAdapter,
)
from cute_evals.judge_benchmarks.agent_process_bench.adapter import (
    _parse_compare_output,
)

MANIFEST = (
    Path(__file__).resolve().parents[1]
    / "src"
    / "cute_evals"
    / "judge_benchmarks"
    / "agent_process_bench"
    / "manifest.yaml"
)


def _hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _fixture_source(tmp_path: Path) -> tuple[Path, Path]:
    source = tmp_path / "source"
    source.mkdir()
    data_dir = source / "data" / "AgentProcessBench"
    data_dir.mkdir(parents=True)
    eval_dir = source / "eval"
    eval_dir.mkdir()
    (eval_dir / "llm_annotation.py").write_text("# fixture\n", encoding="utf-8")
    (eval_dir / "compare.py").write_text("# fixture\n", encoding="utf-8")

    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    manifest["licenseStatus"] = "approved"
    manifest["scoreLabel"] = "official-subset"
    for dataset_index, dataset in enumerate(
        ("hotpotqa", "gaia_dev", "bfcl", "tau2")
    ):
        path = data_dir / f"{dataset}.jsonl"
        rows = [
            {
                "total_index": dataset_index * 250 + index,
                "query_index": index,
                "sample_index": 0,
                "messages": [
                    {"role": "user", "content": "question"},
                    {"role": "assistant", "content": "answer"},
                ],
                "tools": [],
            }
            for index in range(6)
        ]
        path.write_text(
            "".join(json.dumps(row) + "\n" for row in rows),
            encoding="utf-8",
        )
        manifest["datasets"][dataset]["sha256"] = _hash(path)
        manifest["datasets"][dataset]["size"] = path.stat().st_size
    manifest["scripts"]["annotate"]["sha256"] = _hash(
        eval_dir / "llm_annotation.py"
    )
    manifest["scripts"]["compare"]["sha256"] = _hash(eval_dir / "compare.py")
    manifest_path = tmp_path / "manifest.yaml"
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    return source, manifest_path


def test_official_manifest_is_local_research_only() -> None:
    adapter = AgentProcessBenchAdapter(
        source_dir=Path("."),
        manifest_path=MANIFEST,
    )

    metadata = adapter.report_metadata()
    assert metadata["licenseStatus"] == "local-research"
    assert metadata["usagePolicy"] == {
        "mode": "local-research",
        "officialScore": False,
        "publicationAllowed": False,
        "gateEligible": False,
    }
    adapter.assert_run_allowed()


def test_smoke_split_selects_without_mutating_official_rows(
    tmp_path: Path,
) -> None:
    source, manifest_path = _fixture_source(tmp_path)
    adapter = AgentProcessBenchAdapter(source, manifest_path)
    paths = [
        source / "data" / "AgentProcessBench" / f"{name}.jsonl"
        for name in ("hotpotqa", "gaia_dev", "bfcl", "tau2")
    ]
    before = [path.read_bytes() for path in paths]

    tasks = adapter.tasks("smoke")

    assert len(tasks) == 20
    assert {task.category for task in tasks} == {
        "hotpotqa",
        "gaia_dev",
        "bfcl",
        "tau2",
    }
    assert [path.read_bytes() for path in paths] == before
    assert adapter.verify_assets()["datasets"]["hotpotqa"]["sha256"] == _hash(
        paths[0]
    )


def test_parse_compare_output_requires_average_row() -> None:
    output = """dataset  ref_n  pred_n  missing_or_failed_pct  step_micro_acc  step_exact_acc  first_neg1_idx_acc
hotpotqa      5       5                   0.00%         80.00%          60.00%              80.00%
AVG           5       5                   0.00%         80.00%          60.00%              80.00%
"""

    metrics = _parse_compare_output(output, ["hotpotqa"])

    assert metrics["AVG"]["stepMicroAccuracy"] == "80.00%"
    assert metrics["hotpotqa"]["predictionRecords"] == 5
