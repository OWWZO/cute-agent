from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path

import pytest
import yaml

from cute_evals.judge_benchmarks import JudgeOutputError, LicenseBlockedError
from cute_evals.judge_benchmarks.agent_reward_bench import (
    AgentRewardBenchAdapter,
)
from cute_evals.judge_benchmarks.agent_reward_bench.adapter import (
    _parse_response,
    _success_confusion,
    _validate_response,
)

MANIFEST = (
    Path(__file__).resolve().parents[1]
    / "src"
    / "cute_evals"
    / "judge_benchmarks"
    / "agent_reward_bench"
    / "manifest.yaml"
)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def _fixture_source(tmp_path: Path) -> tuple[Path, Path]:
    source = tmp_path / "source"
    data = source / "agent_reward_bench" / "data"
    scripts = source / "agent_reward_bench" / "judge"
    eval_dir = source / "agent_reward_bench" / "eval"
    script_dir = source / "scripts"
    scripts.mkdir(parents=True)
    eval_dir.mkdir(parents=True)
    script_dir.mkdir(parents=True)

    annotations: list[dict[str, str]] = []
    splits: list[dict[str, str]] = []
    for index in range(8):
        task_id = f"webarena.{index}"
        splits.append(
            {"task_id": task_id, "benchmark": "webarena", "split": "dev"}
        )
        for model in ("model-a", "model-b"):
            annotations.append(
                {
                    "annotator_name": "A",
                    "benchmark": "webarena",
                    "task_id": task_id,
                    "model_name": model,
                    "exp_name": f"{model}_on_webarena",
                    "trajectory_success": "Successful",
                    "trajectory_side_effect": "No",
                    "trajectory_optimality": "4. Completely Optimal",
                    "trajectory_looping": "No",
                }
            )
    for index in range(2):
        task_id = f"webarena.{100 + index}"
        splits.append(
            {"task_id": task_id, "benchmark": "webarena", "split": "test"}
        )
        annotations.append(
            {
                "annotator_name": "A",
                "benchmark": "webarena",
                "task_id": task_id,
                "model_name": "model-a",
                "exp_name": "model-a_on_webarena",
                "trajectory_success": "Unsuccessful",
                "trajectory_side_effect": "No",
                "trajectory_optimality": "2. Suboptimal",
                "trajectory_looping": "No",
            }
        )
    _write_csv(data / "annotations.csv", annotations)
    _write_csv(data / "splits.csv", splits)
    _write_csv(
        data / "complete_task_ids.csv",
        [{"task_id": row["task_id"], "benchmark": "webarena"} for row in splits],
    )

    (scripts / "__init__.py").write_text("", encoding="utf-8")
    (scripts / "defaults.py").write_text(
        'SYSTEM_PROMPT = """official system"""\n'
        'STEP_TEMPLATE = """Step: {step_number}\\nURL: {url}\\n'
        'Action: {action}\\nReasoning: {reasoning}"""\n'
        'GOAL_TEMPLATE = "The user goal is: {goal}\\n"\n'
        'ACTION_TEMPLATE = """The agent performed: {steps}"""\n'
        'AXTREE_TEMPLATE = """The last accessibility tree is: {axtree}"""\n'
        'FINAL_MSG = "Answer the four questions."\n',
        encoding="utf-8",
    )
    (eval_dir / "__init__.py").write_text("", encoding="utf-8")
    (eval_dir / "metrics.py").write_text("# fixture\n", encoding="utf-8")
    (script_dir / "run_judge.py").write_text("# fixture\n", encoding="utf-8")
    (script_dir / "score_judgments.py").write_text(
        "# fixture\n", encoding="utf-8"
    )

    manifest = yaml.safe_load(MANIFEST.read_text(encoding="utf-8"))
    manifest["licenseStatus"] = "approved"
    manifest["scoreLabel"] = "official-subset"
    manifest["splits"] = {"smoke": 5, "dev": -1, "test": -1}
    for name, relative in (
        ("annotations", "agent_reward_bench/data/annotations.csv"),
        ("splits", "agent_reward_bench/data/splits.csv"),
        (
            "completeTaskIds",
            "agent_reward_bench/data/complete_task_ids.csv",
        ),
    ):
        path = source / relative
        manifest["data"][name]["sha256"] = _sha256(path)
        manifest["data"][name]["size"] = path.stat().st_size
    script_paths = {
        "promptDefaults": "agent_reward_bench/judge/defaults.py",
        "judge": "agent_reward_bench/judge/__init__.py",
        "metrics": "agent_reward_bench/eval/metrics.py",
        "evalPackage": "agent_reward_bench/eval/__init__.py",
        "judgeRunner": "scripts/run_judge.py",
        "scorer": "scripts/score_judgments.py",
    }
    for name, relative in script_paths.items():
        path = source / relative
        if name == "judge":
            path = scripts / "__init__.py"
        path.write_text(path.read_text(encoding="utf-8") if path.exists() else "", encoding="utf-8")
        manifest["scripts"][name]["sha256"] = _sha256(path)
    manifest["expectedTargets"] = {"dev": 16, "test": 2}
    manifest_path = tmp_path / "manifest.yaml"
    manifest_path.write_text(
        yaml.safe_dump(manifest, sort_keys=False),
        encoding="utf-8",
    )
    return source, manifest_path


def _trajectory_root(tmp_path: Path) -> Path:
    root = tmp_path / "trajectories"
    path = (
        root
        / "cleaned"
        / "webarena"
        / "model-a"
        / "model-a_on_webarena"
        / "webarena.0.json"
    )
    path.parent.mkdir(parents=True)
    path.write_text(
        json.dumps(
            {
                "goal": "find the answer",
                "valid": True,
                "steps": [
                    {
                        "url": "https://example.test",
                        "action": "click('submit')",
                        "reasoning": "submit the form",
                        "axtree": "[1] button 'submit'",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    return root


def test_official_manifest_is_license_blocked() -> None:
    adapter = AgentRewardBenchAdapter(Path("."), MANIFEST)

    assert adapter.report_metadata()["licenseStatus"] == "blocked-license"
    with pytest.raises(LicenseBlockedError, match="license status is blocked-license"):
        adapter.assert_run_allowed()


def test_smoke_uses_unique_official_targets_without_mutating_csv(
    tmp_path: Path,
) -> None:
    source, manifest_path = _fixture_source(tmp_path)
    adapter = AgentRewardBenchAdapter(source, manifest_path)
    before = (source / "agent_reward_bench" / "data" / "annotations.csv").read_bytes()

    tasks = adapter.tasks("smoke")

    assert len(tasks) == 5
    assert len({task.id for task in tasks}) == 5
    assert all(task.split == "smoke" for task in tasks)
    assert (source / "agent_reward_bench" / "data" / "annotations.csv").read_bytes() == before
    assert adapter.verify_assets()["data"]["annotations"]["sha256"] == _sha256(
        source / "agent_reward_bench" / "data" / "annotations.csv"
    )


def test_prompt_uses_official_constants_and_axtree(tmp_path: Path) -> None:
    source, manifest_path = _fixture_source(tmp_path)
    adapter = AgentRewardBenchAdapter(
        source,
        manifest_path,
        trajectory_dir=_trajectory_root(tmp_path),
    )
    task = adapter.tasks("smoke")[0]
    trajectory = json.loads(adapter._trajectory_path(task).read_text(encoding="utf-8"))

    messages = adapter.build_messages(
        task,
        trajectory,
        judge_variant="axtree",
    )

    assert messages[0]["content"] == "official system"
    user_text = "\n".join(
        item["text"]
        for item in messages[1]["content"]
        if item["type"] == "text"
    )
    assert "The user goal is: find the answer\n" in user_text
    assert "click('submit')" in user_text
    assert "The last accessibility tree is: [1] button 'submit'" in user_text
    assert "image_url" not in {item["type"] for item in messages[1]["content"]}


def test_response_parser_rejects_missing_or_invalid_labels() -> None:
    assert _validate_response(
        "<reasoning>ok</reasoning><success>Successful</success>"
        "<side>No</side><optimal>4. Completely Optimal</optimal><loop>No</loop>"
    ) is None
    assert _validate_response(
        "<reasoning>ok</reasoning><success>Maybe</success>"
        "<side>No</side><optimal>4. Completely Optimal</optimal><loop>No</loop>"
    )["kind"] == "invalid_label"
    with pytest.raises(JudgeOutputError):
        _parse_response("<reasoning>ok</reasoning><success>Successful</success>")


def test_success_confusion_separates_false_pass_from_false_fail() -> None:
    confusion = _success_confusion(
        {
            "judge": {
                "webarena": {
                    "model-a": {
                        "trajectory_success": {
                            "y_human": [1, 0, 1, 0],
                            "y_judge": [1, 1, 0, 0],
                        }
                    }
                }
            }
        }
    )

    assert confusion["total"] == {
        "truePositive": 1,
        "trueNegative": 1,
        "falsePass": 1,
        "falseFail": 1,
        "invalid": 0,
    }
