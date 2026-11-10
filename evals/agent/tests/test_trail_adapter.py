from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from pathlib import Path

import yaml

from cute_evals.judge_benchmarks.trail import TrailAdapter
from cute_evals.judge_benchmarks.trail.adapter import (
    _parse_metric_files,
    _validate_trail_response,
)

MANIFEST = (
    Path(__file__).resolve().parents[1]
    / "src"
    / "cute_evals"
    / "judge_benchmarks"
    / "trail"
    / "manifest.yaml"
)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _git(repo: Path, *args: str) -> str:
    git = shutil.which("git.exe") or shutil.which("git")
    assert git is not None
    result = subprocess.run(
        [git, "-C", str(repo), *args],
        text=True,
        capture_output=True,
        check=True,
    )
    return result.stdout


def _tree_hash(repo: Path, path: str) -> str:
    lines = _git(repo, "ls-tree", "-r", "HEAD", "--", path).splitlines()
    return hashlib.sha256(
        ("\n".join(lines) + "\n").encode("utf-8")
    ).hexdigest()


def _fixture_source(tmp_path: Path) -> tuple[Path, Path]:
    source = tmp_path / "source"
    gaia = source / "benchmarking" / "data" / "GAIA"
    swe = source / "benchmarking" / "data" / "SWE Bench"
    gaia.mkdir(parents=True)
    swe.mkdir(parents=True)
    gaia_gt = source / "benchmarking" / "processed_annotations_gaia"
    swe_gt = source / "benchmarking" / "processed_annotations_swe_bench"
    gaia_gt.mkdir(parents=True)
    swe_gt.mkdir(parents=True)
    scripts = source / "benchmarking"
    (scripts / "run_eval.py").write_text(
        'def get_prompt(trace: str):\n'
        '    prompt = """Analyze this trace:\\n{trace}"""\n'
        "    return prompt.format(trace=trace)\n",
        encoding="utf-8",
    )
    (scripts / "calculate_scores.py").write_text(
        "print('fixture scorer')\n",
        encoding="utf-8",
    )
    for index in range(3):
        name = f"gaia-{index}.json"
        (gaia / name).write_text(
            json.dumps({"trace_id": f"gaia-{index}", "spans": []}),
            encoding="utf-8",
        )
        (gaia_gt / name).write_text(
            json.dumps({"errors": [], "scores": [{"overall": 5}]}),
            encoding="utf-8",
        )
    for index in range(2):
        name = f"swe-{index}.json"
        (swe / name).write_text(
            json.dumps({"trace_id": f"swe-{index}", "spans": []}),
            encoding="utf-8",
        )
        (swe_gt / name).write_text(
            json.dumps({"errors": [], "scores": [{"overall": 5}]}),
            encoding="utf-8",
        )
    _git(source, "init")
    _git(source, "config", "user.email", "test@example.com")
    _git(source, "config", "user.name", "Test")
    _git(source, "add", ".")
    _git(source, "commit", "-m", "fixture")
    commit = _git(source, "rev-parse", "HEAD").strip()

    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    manifest["upstreamCommit"] = commit
    manifest["version"] = commit
    manifest["licenseStatus"] = "approved"
    manifest["scoreLabel"] = "official-subset"
    manifest["splits"] = {
        "smoke": {"GAIA": 2, "SWE Bench": 0},
        "official-subset": {"GAIA": 1, "SWE Bench": 1},
        "official-full": {"GAIA": -1, "SWE Bench": -1},
    }
    manifest["assets"]["data"]["GAIA"]["treeSha256"] = _tree_hash(
        source,
        "benchmarking/data/GAIA",
    )
    manifest["assets"]["data"]["SWE Bench"]["treeSha256"] = _tree_hash(
        source,
        "benchmarking/data/SWE Bench",
    )
    manifest["assets"]["annotations"]["GAIA"]["treeSha256"] = _tree_hash(
        source,
        "benchmarking/processed_annotations_gaia",
    )
    manifest["assets"]["annotations"]["SWE Bench"]["treeSha256"] = _tree_hash(
        source,
        "benchmarking/processed_annotations_swe_bench",
    )
    manifest["scripts"]["prompt"]["sha256"] = _sha256(
        scripts / "run_eval.py"
    )
    manifest["scripts"]["scorer"]["sha256"] = _sha256(
        scripts / "calculate_scores.py"
    )
    manifest_path = tmp_path / "manifest.yaml"
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    return source, manifest_path


def test_official_manifest_is_local_research_only() -> None:
    adapter = TrailAdapter(Path("."), MANIFEST)

    metadata = adapter.report_metadata()
    assert metadata["licenseStatus"] == "local-research"
    assert metadata["usagePolicy"] == {
        "mode": "local-research",
        "officialScore": False,
        "publicationAllowed": False,
        "gateEligible": False,
    }
    adapter.assert_run_allowed()


def test_tasks_select_official_files_without_mutating_them(
    tmp_path: Path,
) -> None:
    source, manifest_path = _fixture_source(tmp_path)
    adapter = TrailAdapter(source, manifest_path)
    before = {
        path: path.read_bytes()
        for path in (source / "benchmarking" / "data").rglob("*.json")
    }

    smoke = adapter.tasks("smoke")
    subset = adapter.tasks("official-subset")

    assert len(smoke) == 2
    assert {task.domain for task in smoke} == {"GAIA"}
    assert len(subset) == 2
    assert {task.domain for task in subset} == {"GAIA", "SWE Bench"}
    assert {
        path: path.read_bytes() for path in before
    } == before
    assert "Analyze this trace" in adapter.build_prompt(smoke[0])
    assert "gaia-0" in adapter.build_prompt(smoke[0])


def test_trail_response_requires_errors_and_scores() -> None:
    assert _validate_trail_response(
        json.dumps({"errors": [], "scores": [{"overall": 5}]})
    ) is None
    assert _validate_trail_response("not json")["kind"] == "invalid_json"
    assert _validate_trail_response('{"errors": []}')["kind"] == "invalid_schema"


def test_metric_files_are_parsed(tmp_path: Path) -> None:
    metric_file = tmp_path / "outputs_model-GAIA-metrics.txt"
    metric_file.write_text(
        "Weighted F1: 0.5123\n"
        "Average Location Accuracy: 0.2500\n"
        "Average Location-Category Joint Accuracy: 0.1250\n",
        encoding="utf-8",
    )

    metrics = _parse_metric_files(tmp_path)

    assert metrics["outputs_model-GAIA"]["weightedF1"] == 0.5123
    assert metrics["outputs_model-GAIA"]["jointAccuracy"] == 0.125
