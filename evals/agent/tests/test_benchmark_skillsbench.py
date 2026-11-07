from __future__ import annotations

import subprocess
from pathlib import Path

from cute_evals.benchmarks.skillsbench.adapter import DockerVerifier, SkillsBenchAdapter
from cute_evals.models import RunTrace


class FakeVerifier:
    def __init__(self) -> None:
        self.materialized: list[tuple[Path, Path]] = []
        self.verified_images: list[str | None] = []

    def materialize(self, task_dir: Path, workspace: Path, out_dir: Path) -> dict:
        self.materialized.append((task_dir, workspace))
        return {"image": "official-image", "status": "materialized"}

    def run(self, task_dir: Path, workspace: Path, out_dir: Path, *, image=None) -> dict:
        self.verified_images.append(image)
        return {"passed": True, "status": "verified", "reward": "1"}


def _write_task(root: Path, task_id: str = "task-a") -> Path:
    task_dir = root / "tasks" / task_id
    (task_dir / "environment" / "skills" / "foo").mkdir(parents=True)
    (task_dir / "verifier").mkdir(parents=True)
    (task_dir / "task.md").write_text(
        "---\n"
        "schema_version: '1.3'\n"
        "metadata:\n"
        "  category: office\n"
        "---\n"
        "Sort these files.\n",
        encoding="utf-8",
    )
    (task_dir / "environment" / "skills" / "foo" / "SKILL.md").write_text(
        "# Foo skill\n",
        encoding="utf-8",
    )
    (task_dir / "verifier" / "test.sh").write_text("#!/bin/bash\n", encoding="utf-8")
    return task_dir


def test_skillsbench_preserves_package_and_scores_skill_trajectory(tmp_path: Path) -> None:
    source = tmp_path / "source"
    _write_task(source)
    manifest = tmp_path / "manifest.yaml"
    manifest.write_text(
        (Path(__file__).parents[1] / "src" / "cute_evals" / "benchmarks" / "skillsbench" / "manifest.yaml")
        .read_text(encoding="utf-8")
        .replace("- organize-messy-files", "- task-a")
        .replace(
            "  - travel-planning\n  - offer-letter-generator\n"
            "  - xlsx-recover-data\n  - python-scala-translation\n",
            "",
        ),
        encoding="utf-8",
    )
    adapter = SkillsBenchAdapter(
        manifest,
        source_root=source,
        data_home=tmp_path / "cache",
    )
    task = adapter.tasks("official-subset")[0]
    prepared = adapter.prepare(task, tmp_path / "workspace")
    trace = RunTrace.from_dict(
        {
            "run": {
                "caseId": "task-a",
                "requestId": "request-a",
                "sessionId": "session-a",
                "status": "SUCCESS",
                "finalSummaryText": "done",
            },
            "llmInvocations": [],
            "toolInvocations": [
                {
                    "toolName": "workspace_read",
                    "inputJson": '{"path":"skills/foo/SKILL.md"}',
                    "toolResult": "# Foo skill",
                    "status": "SUCCESS",
                }
            ],
            "artifacts": [],
            "environment": {},
        }
    )
    run = adapter.collect(
        task,
        trace,
        "trace.json",
        prepared=prepared,
        oracle={"status": "verified", "passed": True, "reward": "1"},
    )
    result = adapter.score(run)

    assert (prepared.workspace / "skills" / "foo" / "SKILL.md").exists()
    assert result.passed is True
    by_name = {score.name: score for score in result.scores}
    assert by_name["official_verifier"].passed is True
    assert by_name["skill_selection"].value == ["foo"]
    assert by_name["skill_load_count"].value == 1


def test_skillsbench_live_prepare_materializes_official_image_and_reuses_it(
    tmp_path: Path,
) -> None:
    source = tmp_path / "source"
    task_dir = _write_task(source)
    manifest = tmp_path / "manifest.yaml"
    manifest.write_text(
        (Path(__file__).parents[1] / "src" / "cute_evals" / "benchmarks" / "skillsbench" / "manifest.yaml")
        .read_text(encoding="utf-8")
        .replace("- organize-messy-files", "- task-a")
        .replace(
            "  - travel-planning\n  - offer-letter-generator\n"
            "  - xlsx-recover-data\n  - python-scala-translation\n",
            "",
        ),
        encoding="utf-8",
    )
    verifier = FakeVerifier()
    adapter = SkillsBenchAdapter(
        manifest,
        source_root=source,
        verifier=verifier,
    )
    task = adapter.tasks("official-subset")[0]

    prepared = adapter.prepare_live(task, tmp_path / "workspace", tmp_path / "env")
    oracle = adapter.verify(
        task,
        prepared.workspace,
        tmp_path / "verify",
        image=prepared.metadata["environment"]["image"],
    )

    assert verifier.materialized == [(task_dir, prepared.workspace)]
    assert verifier.verified_images == ["official-image"]
    assert oracle["status"] == "verified"


def test_docker_verifier_copies_workspace_into_container_instead_of_mounting_root(
    tmp_path: Path,
    monkeypatch,
) -> None:
    task_dir = _write_task(tmp_path / "source")
    (task_dir / "environment" / "Dockerfile").write_text(
        "FROM scratch\n",
        encoding="utf-8",
    )
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    (workspace / "answer.txt").write_text("done", encoding="utf-8")
    out_dir = tmp_path / "out"
    commands: list[list[str]] = []
    verifier = DockerVerifier()

    def fake_run(command: list[str], *, timeout: int):
        commands.append(command)
        if command[0] == "build":
            return subprocess.CompletedProcess(command, 0, "", "")
        if command[0] == "create":
            return subprocess.CompletedProcess(command, 0, "container", "")
        if command[0] in {"start", "cp", "rm"}:
            return subprocess.CompletedProcess(command, 0, "", "")
        if command[0] == "exec":
            reward = out_dir / "logs" / "verifier" / "reward.txt"
            reward.parent.mkdir(parents=True)
            reward.write_text("1", encoding="utf-8")
            return subprocess.CompletedProcess(command, 0, "ok", "")
        raise AssertionError(command)

    monkeypatch.setattr(verifier, "_run", fake_run)
    result = verifier.run(
        task_dir,
        workspace,
        out_dir,
        image="official-image",
    )

    assert result["passed"] is True
    assert [command[0] for command in commands] == [
        "create",
        "start",
        "cp",
        "exec",
        "rm",
    ]
    create = commands[0]
    assert not any(
        str(workspace.resolve()) in part and ":/root" in part
        for part in create
    )
    assert commands[2][1] == f"{workspace.resolve()}/."
