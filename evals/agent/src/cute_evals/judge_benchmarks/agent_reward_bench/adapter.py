from __future__ import annotations

import ast
import base64
import csv
import hashlib
import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from ..base import (
    JUDGE_SCORE_NAMESPACE,
    JudgeBenchmarkRecord,
    JudgeOutputError,
    JudgeOutputKind,
    LicenseBlockedError,
    RUNNABLE_LICENSE_STATUSES,
    judge_score_label,
    judge_usage_policy,
)
from ..client import OpenAICompatibleJudge

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"
LABEL_NAMES = (
    "trajectory_success",
    "trajectory_side_effect",
    "trajectory_optimality",
    "trajectory_looping",
)
PROMPT_CONSTANTS = (
    "SYSTEM_PROMPT",
    "STEP_TEMPLATE",
    "GOAL_TEMPLATE",
    "ACTION_TEMPLATE",
    "AXTREE_TEMPLATE",
    "FINAL_MSG",
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _normalize_task_id(task_id: str) -> str:
    return task_id.replace(".resized", "").replace(".improved", "")


def _model_tag(model: str) -> str:
    tag = re.sub(r"[^A-Za-z0-9_.-]+", "_", model).strip("_")
    return tag or "judge"


def _extract_tag(text: str, tag: str) -> str | None:
    start = text.find(f"<{tag}>")
    end = text.find(f"</{tag}>", start + len(tag) + 2)
    if start < 0 or end < 0:
        return None
    return text[start + len(tag) + 2 : end].strip()


def _parse_official_constants(path: Path) -> dict[str, str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    values: dict[str, str] = {}
    for node in tree.body:
        if not isinstance(node, ast.Assign) or len(node.targets) != 1:
            continue
        target = node.targets[0]
        if not isinstance(target, ast.Name) or target.id not in PROMPT_CONSTANTS:
            continue
        try:
            value = ast.literal_eval(node.value)
        except (ValueError, TypeError):
            continue
        if isinstance(value, str):
            values[target.id] = value
    missing = sorted(set(PROMPT_CONSTANTS) - set(values))
    if missing:
        raise ValueError(
            f"official AgentRewardBench prompt constants missing: {', '.join(missing)}"
        )
    for name in (
        "SYSTEM_PROMPT",
        "STEP_TEMPLATE",
        "ACTION_TEMPLATE",
        "AXTREE_TEMPLATE",
    ):
        values[name] = values[name].strip()
    return values


def _validate_response(response: str) -> dict[str, Any] | None:
    values = {
        name: _extract_tag(response, name)
        for name in ("reasoning", "success", "side", "optimal", "loop")
    }
    if not values["reasoning"]:
        return {
            "kind": "missing_reasoning",
            "message": "judge response has no <reasoning> content",
        }
    expected = {
        "success": {"Successful", "Unsuccessful"},
        "side": {"Yes", "No"},
        "optimal": {
            "1. Complete Failure",
            "2. Suboptimal",
            "3. Somewhat Optimal",
            "4. Completely Optimal",
        },
        "loop": {"Yes", "No"},
    }
    for name, allowed in expected.items():
        value = values[name]
        if value not in allowed:
            return {
                "kind": "invalid_label",
                "field": name,
                "value": value,
                "allowed": sorted(allowed),
            }
    return None


def _parse_response(response: str) -> dict[str, Any]:
    error = _validate_response(response)
    if error is not None:
        raise JudgeOutputError(
            JudgeOutputKind.INVALID_LABEL,
            str(error.get("message") or error),
            details=error,
        )
    values = {
        name: _extract_tag(response, name)
        for name in ("reasoning", "success", "side", "optimal", "loop")
    }
    return {
        "reasoning": values["reasoning"],
        "trajectory_success": values["success"],
        "trajectory_side_effect": values["side"],
        "trajectory_optimality": values["optimal"],
        "trajectory_looping": values["loop"],
    }


@dataclass(frozen=True, slots=True)
class AgentRewardBenchTask:
    id: str
    benchmark: str
    model_name: str
    task_id: str
    split: str
    labels: dict[str, str]
    annotator_count: int
    raw: dict[str, str] = field(repr=False)


@dataclass(frozen=True, slots=True)
class AgentRewardBenchPrediction:
    task_id: str
    labels: dict[str, str]
    reasoning: str
    raw: dict[str, Any] = field(repr=False)

    def to_dict(self) -> dict[str, Any]:
        return {
            "taskId": self.task_id,
            "labels": self.labels,
            "reasoning": self.reasoning,
        }


@dataclass(slots=True)
class AgentRewardBenchAdapter:
    source_dir: Path
    manifest_path: Path = DEFAULT_MANIFEST
    trajectory_dir: Path | None = None
    name: str = "agent-reward-bench"
    version: str = ""
    upstream: str = ""
    score_label: str = "blocked-license"
    score_namespace: str = JUDGE_SCORE_NAMESPACE
    manifest: dict[str, Any] = field(init=False, repr=False, compare=False)
    _constants: dict[str, str] | None = field(
        init=False,
        default=None,
        repr=False,
        compare=False,
    )

    def __post_init__(self) -> None:
        self.source_dir = self.source_dir.resolve()
        self.manifest_path = self.manifest_path.resolve()
        if self.trajectory_dir is not None:
            self.trajectory_dir = self.trajectory_dir.resolve()
        raw = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise TypeError(f"invalid AgentRewardBench manifest: {self.manifest_path}")
        self.manifest = raw
        self.name = str(raw["name"])
        self.version = str(raw["version"])
        self.upstream = str(raw["upstream"])
        self.score_label = str(raw["scoreLabel"])
        self.score_namespace = str(raw["scoreNamespace"])

    @property
    def license_status(self) -> str:
        return str(self.manifest.get("licenseStatus") or "unverified")

    def _path(self, relative: str) -> Path:
        path = (self.source_dir / relative).resolve()
        if self.source_dir not in path.parents and path != self.source_dir:
            raise ValueError(f"official asset escapes source directory: {relative}")
        return path

    def verify_assets(self) -> dict[str, Any]:
        verified: dict[str, Any] = {"data": {}, "scripts": {}}
        for group in ("data", "scripts"):
            for name, spec in self.manifest[group].items():
                path = self._path(str(spec["file"]))
                if not path.is_file():
                    raise FileNotFoundError(path)
                actual = _sha256(path)
                if actual != str(spec["sha256"]).lower():
                    raise ValueError(
                        f"AgentRewardBench {group}/{name} hash mismatch: "
                        f"expected {spec['sha256']}, got {actual}"
                    )
                verified[group][name] = {
                    "path": str(path),
                    "sha256": actual,
                    "size": path.stat().st_size,
                }
        return verified

    def assert_run_allowed(self) -> None:
        if self.license_status not in RUNNABLE_LICENSE_STATUSES:
            raise LicenseBlockedError(
                self.name,
                self.license_status,
                str(self.manifest.get("licenseEvidence") or ""),
            )

    def _csv_rows(self, spec_name: str) -> list[dict[str, str]]:
        spec = self.manifest["data"][spec_name]
        path = self._path(str(spec["file"]))
        with path.open("r", encoding="utf-8", newline="") as stream:
            return list(csv.DictReader(stream))

    def _split_map(self) -> dict[str, str]:
        return {
            _normalize_task_id(row["task_id"]): row["split"]
            for row in self._csv_rows("splits")
        }

    def _rows_for_split(self, split: str) -> list[dict[str, str]]:
        split_map = self._split_map()
        rows = [
            row
            for row in self._csv_rows("annotations")
            if split_map.get(_normalize_task_id(row["task_id"])) == split
        ]
        return rows

    def tasks(self, split: str) -> list[AgentRewardBenchTask]:
        requested = "dev" if split == "smoke" else split
        if requested not in {"dev", "test"}:
            raise ValueError(f"unsupported AgentRewardBench split: {split}")
        rows = self._rows_for_split(requested)
        counts: dict[tuple[str, str, str], int] = {}
        for row in rows:
            key = (row["benchmark"], row["model_name"], row["task_id"])
            counts[key] = counts.get(key, 0) + 1
        tasks: list[AgentRewardBenchTask] = []
        seen: set[tuple[str, str, str]] = set()
        limit = (
            int(self.manifest["splits"]["smoke"])
            if split == "smoke"
            else -1
        )
        for row in rows:
            key = (row["benchmark"], row["model_name"], row["task_id"])
            if key in seen:
                continue
            seen.add(key)
            if limit >= 0 and len(tasks) >= limit:
                break
            tasks.append(
                AgentRewardBenchTask(
                    id=f"{row['benchmark']}:{row['model_name']}:{row['task_id']}",
                    benchmark=row["benchmark"],
                    model_name=row["model_name"],
                    task_id=row["task_id"],
                    split=split,
                    labels={
                        name: row.get(name, "") for name in LABEL_NAMES
                    },
                    annotator_count=counts[key],
                    raw=row,
                )
            )
        return tasks

    def _trajectory_path(self, task: AgentRewardBenchTask) -> Path:
        if self.trajectory_dir is None:
            raise ValueError(
                "AgentRewardBench trajectory_dir is required for a real run"
            )
        base = self.trajectory_dir / "cleaned"
        if not base.is_dir():
            base = self.trajectory_dir
        model_dir = base / task.benchmark / task.model_name
        if not model_dir.is_dir():
            raise FileNotFoundError(
                f"AgentRewardBench trajectory model directory not found: {model_dir}"
            )
        exact = sorted(model_dir.rglob(f"{task.task_id}.json"))
        if len(exact) == 1:
            return exact[0]
        normalized = sorted(
            path
            for path in model_dir.rglob("*.json")
            if _normalize_task_id(path.stem) == _normalize_task_id(task.task_id)
        )
        if len(normalized) == 1:
            return normalized[0]
        if not normalized:
            raise FileNotFoundError(
                f"AgentRewardBench trajectory not found for {task.task_id}"
            )
        raise ValueError(
            f"AgentRewardBench trajectory is ambiguous for {task.task_id}"
        )

    def _official_constants(self) -> dict[str, str]:
        if self._constants is not None:
            return self._constants
        spec = self.manifest["scripts"]["promptDefaults"]
        self._constants = _parse_official_constants(
            self._path(str(spec["file"]))
        )
        return self._constants

    def _screenshot_path(
        self,
        task: AgentRewardBenchTask,
        step: dict[str, Any],
    ) -> Path:
        relative = str(step.get("screenshot_path") or "")
        if not relative:
            raise ValueError(f"trajectory has no screenshot_path: {task.task_id}")
        candidates = [self.trajectory_dir / relative]
        if relative.startswith("trajectories/"):
            candidates.append(self.trajectory_dir / relative.removeprefix("trajectories/"))
        candidates.append(self._trajectory_path(task).parent / relative)
        for candidate in candidates:
            if candidate.is_file():
                return candidate
        raise FileNotFoundError(
            f"AgentRewardBench screenshot not found for {task.task_id}: {relative}"
        )

    def build_messages(
        self,
        task: AgentRewardBenchTask,
        trajectory: dict[str, Any],
        *,
        judge_variant: str,
    ) -> list[dict[str, Any]]:
        if judge_variant not in {"axtree", "screen", "both", "none"}:
            raise ValueError(f"unsupported judge variant: {judge_variant}")
        constants = self._official_constants()
        steps = trajectory.get("steps")
        if not isinstance(steps, list) or not steps:
            raise ValueError(f"trajectory has no steps: {task.task_id}")
        last_step = steps[-1]
        if not isinstance(last_step, dict):
            raise ValueError(f"trajectory last step is invalid: {task.task_id}")
        formatted_steps = "".join(
            constants["STEP_TEMPLATE"].format(
                step_number=index + 1,
                url=step.get("url", ""),
                action=step.get("action", ""),
                reasoning=step.get("reasoning", ""),
            )
            for index, step in enumerate(steps)
            if isinstance(step, dict)
        )
        user_content: list[dict[str, Any]] = [
            {
                "type": "text",
                "text": constants["GOAL_TEMPLATE"].format(
                    goal=trajectory.get("goal", "")
                ),
            },
            {
                "type": "text",
                "text": constants["ACTION_TEMPLATE"].format(
                    steps=formatted_steps
                ),
            },
        ]
        if judge_variant in {"axtree", "both"}:
            axtree = last_step.get("axtree")
            if not isinstance(axtree, str) or not axtree:
                raise ValueError(
                    f"trajectory has no accessibility tree: {task.task_id}"
                )
            user_content.append(
                {
                    "type": "text",
                    "text": constants["AXTREE_TEMPLATE"].format(axtree=axtree),
                }
            )
        if judge_variant in {"screen", "both"}:
            image_path = self._screenshot_path(task, last_step)
            mime = "png" if image_path.suffix.lower() == ".png" else "jpeg"
            encoded = base64.b64encode(image_path.read_bytes()).decode("ascii")
            user_content.extend(
                [
                    {
                        "type": "text",
                        "text": "Here is the screenshot of the last step.",
                    },
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/{mime};base64,{encoded}"},
                    },
                ]
            )
        user_content.append(
            {"type": "text", "text": constants["FINAL_MSG"]}
        )
        return [
            {"role": "system", "content": constants["SYSTEM_PROMPT"]},
            {"role": "user", "content": user_content},
        ]

    def _judgment_payload(
        self,
        task: AgentRewardBenchTask,
        trajectory: dict[str, Any],
        response: str,
        parsed: dict[str, Any],
        judge: OpenAICompatibleJudge,
        messages: list[dict[str, Any]],
        judge_tag: str,
        judge_variant: str,
    ) -> dict[str, Any]:
        return {
            "benchmark": task.benchmark,
            "goal": trajectory.get("goal", ""),
            "agent": task.model_name,
            "judge": judge_tag,
            "judge_model_name": judge.model,
            "provider": "openai-compatible",
            "judge_args": {"variant": judge_variant},
            "completion_args": {
                "max_completion_tokens": judge.max_tokens,
                "temperature": judge.temperature,
                "top_p": judge.top_p,
            },
            "cost": 0,
            "response": {
                "choices": [{"message": {"content": response}}],
            },
            "chat_messages": messages,
            "trajectory_info": {
                "valid": trajectory.get("valid"),
                "model": trajectory.get("model"),
                "trajectory_dir": trajectory.get("trajectory_dir"),
                "seed": trajectory.get("seed"),
                "model_args": trajectory.get("model_args"),
                "flags": trajectory.get("flags"),
                "summary_info": trajectory.get("summary_info") or {},
            },
            "parsed_judgment": parsed,
        }

    def run(
        self,
        judge: OpenAICompatibleJudge,
        *,
        split: str,
        output_dir: Path,
        judge_variant: str = "axtree",
        concurrency: int = 1,
        timeout_seconds: int | None = None,
    ) -> dict[str, Any]:
        self.assert_run_allowed()
        if concurrency < 1:
            raise ValueError("concurrency must be >= 1")
        assets = self.verify_assets()
        tasks = self.tasks(split)
        if not tasks:
            raise ValueError("AgentRewardBench selected no tasks")
        output_dir = output_dir.resolve()
        judgments_dir = output_dir / "judgments"
        results_dir = output_dir / "artifacts"
        results_dir.mkdir(parents=True, exist_ok=True)
        judge_tag = f"{_model_tag(judge.model)}__{judge_variant}"
        records: list[JudgeBenchmarkRecord] = []
        scorer_split = "dev" if split == "smoke" else split

        def _run_task(
            task: AgentRewardBenchTask,
        ) -> tuple[AgentRewardBenchTask, str | None, dict[str, Any] | None, dict[str, Any] | None]:
            try:
                trajectory_path = self._trajectory_path(task)
                trajectory = json.loads(
                    trajectory_path.read_text(encoding="utf-8")
                )
                if trajectory.get("valid") is False:
                    raise ValueError("official trajectory is marked invalid")
                messages = self.build_messages(
                    task,
                    trajectory,
                    judge_variant=judge_variant,
                )
                response = judge.complete(
                    messages,
                    timeout_seconds=timeout_seconds,
                )
                error = _validate_response(response)
                parsed = _parse_response(response) if error is None else None
                judgment = None
                if error is None:
                    judgment = self._judgment_payload(
                        task,
                        trajectory,
                        response,
                        parsed or {},
                        judge,
                        messages,
                        judge_tag,
                        judge_variant,
                    )
                return task, response, error, judgment
            except Exception as exc:
                return task, None, {
                    "kind": "judge_or_trajectory_error",
                    "message": str(exc),
                }, None

        with ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = [executor.submit(_run_task, task) for task in tasks]
            for future in as_completed(futures):
                task, response, error, judgment = future.result()
                record_status = "verified" if error is None else "failed"
                prediction = None
                if judgment is not None:
                    prediction = AgentRewardBenchPrediction(
                        task_id=task.id,
                        labels={
                            name: judgment["parsed_judgment"][name]
                            for name in LABEL_NAMES
                        },
                        reasoning=judgment["parsed_judgment"]["reasoning"],
                        raw=judgment,
                    )
                    target = (
                        judgments_dir
                        / task.benchmark
                        / task.model_name
                        / judge_tag
                    )
                    target.mkdir(parents=True, exist_ok=True)
                    (target / f"{task.task_id}.json").write_text(
                        json.dumps(judgment, ensure_ascii=True, indent=2),
                        encoding="utf-8",
                    )
                records.append(
                    JudgeBenchmarkRecord(
                        task_id=task.id,
                        status=record_status,
                        prediction=prediction,
                        raw_response=response,
                        error=error,
                        metadata={
                            "benchmark": task.benchmark,
                            "modelName": task.model_name,
                            "taskId": task.task_id,
                            "split": split,
                        },
                    )
                )

        records.sort(key=lambda item: item.task_id)
        score_error: str | None = None
        metrics: dict[str, Any] = {}
        agreement: dict[str, Any] = {}
        confusion: dict[str, Any] = {}
        if any(record.status == "verified" for record in records):
            score_error = self._run_official_scorer(
                scorer_split=scorer_split,
                judgments_dir=judgments_dir,
                results_dir=results_dir,
                judge_tag=judge_tag,
            )
            if score_error is None:
                metrics = _read_metrics_csv(
                    results_dir / f"metrics_{scorer_split}.csv"
                )
                agreement = _read_json(
                    results_dir / "agreement_rate.json"
                )
                confusion = _success_confusion(
                    _read_json(results_dir / "results.json")
                )
        complete = all(record.status == "verified" for record in records)
        expected = self.manifest.get("expectedTargets", {}).get(scorer_split)
        if complete and score_error is None and expected and len(tasks) == int(expected):
            score_label = (
                "official-test" if scorer_split == "test" else "official-subset"
            )
        elif complete and score_error is None:
            score_label = "official-subset"
        else:
            score_label = "partial-not-official"
        score_label = judge_score_label(self.license_status, score_label)
        report = {
            "schemaVersion": "1.0",
            "subject": {
                "type": "judge",
                "model": judge.model,
                "baseUrl": judge.base_url,
                "temperature": judge.temperature,
                "topP": judge.top_p,
                "maxTokens": judge.max_tokens,
                "variant": judge_variant,
            },
            "benchmark": {
                "name": self.name,
                "version": self.version,
                "upstream": self.upstream,
                "upstreamCommit": self.manifest.get("upstreamCommit"),
                "trajectoryDataset": self.manifest.get("trajectoryDataset"),
                "trajectoryDatasetRevision": self.manifest.get(
                    "trajectoryDatasetRevision"
                ),
                "datasetHash": self.dataset_hash(assets),
                "evaluatorHash": assets["scripts"]["scorer"]["sha256"],
                "licenseStatus": self.license_status,
            },
            "scoreNamespace": self.score_namespace,
            "scoreLabel": score_label,
            "usagePolicy": judge_usage_policy(self.license_status),
            "tasks": [record.to_dict() for record in records],
            "metrics": {
                "split": split,
                "scorerSplit": scorer_split,
                "taskCount": len(tasks),
                "metricsRows": metrics,
                "agreementRate": agreement,
                "successConfusion": confusion,
                "scorerError": score_error,
            },
            "environment": {
                "workspace": str(output_dir),
                "judgments": str(judgments_dir),
                "sourceDir": str(self.source_dir),
                "trajectoryDir": str(self.trajectory_dir)
                if self.trajectory_dir
                else None,
            },
        }
        (output_dir / "report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return report

    def _run_official_scorer(
        self,
        *,
        scorer_split: str,
        judgments_dir: Path,
        results_dir: Path,
        judge_tag: str,
    ) -> str | None:
        scorer = self._path(
            str(self.manifest["scripts"]["scorer"]["file"])
        )
        wrapper = results_dir / "_run_official_scorer.py"
        wrapper.write_text(
            "import runpy\n"
            "from agent_reward_bench.judge import utils\n"
            f"utils.get_judges = lambda: [{judge_tag!r}]\n"
            f"runpy.run_path({str(scorer)!r}, run_name='__main__')\n",
            encoding="utf-8",
        )
        env = os.environ.copy()
        env["PYTHONUTF8"] = "1"
        existing = env.get("PYTHONPATH")
        env["PYTHONPATH"] = (
            str(self.source_dir)
            if not existing
            else str(self.source_dir) + os.pathsep + existing
        )
        result = subprocess.run(
            [
                sys.executable,
                str(wrapper),
                "--split",
                scorer_split,
                "--judgments_base_dir",
                str(judgments_dir),
                "--results_save_dir",
                str(results_dir),
            ],
            cwd=self.source_dir,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            return result.stderr[-4000:] or result.stdout[-4000:]
        return None

    @staticmethod
    def dataset_hash(assets: dict[str, Any]) -> str:
        payload = "\n".join(
            f"{group}/{name}:{spec['sha256']}"
            for group in ("data", "scripts")
            for name, spec in sorted(assets[group].items())
        )
        return "sha256:" + hashlib.sha256(
            payload.encode("utf-8")
        ).hexdigest()

    def report_metadata(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "upstreamCommit": self.manifest.get("upstreamCommit"),
            "trajectoryDataset": self.manifest.get("trajectoryDataset"),
            "trajectoryDatasetRevision": self.manifest.get(
                "trajectoryDatasetRevision"
            ),
            "licenseStatus": self.license_status,
            "scoreNamespace": self.score_namespace,
            "scoreLabel": self.score_label,
            "usagePolicy": judge_usage_policy(self.license_status),
        }


def _read_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    return payload if isinstance(payload, dict) else {}


def _read_metrics_csv(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    with path.open("r", encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    numeric = {"accuracy", "precision", "recall", "f1", "npv", "tnr", "total", "unsures"}
    result: list[dict[str, Any]] = []
    for row in rows:
        item: dict[str, Any] = {}
        for key, value in row.items():
            if key in numeric and value not in (None, ""):
                try:
                    item[key] = float(value)
                    continue
                except ValueError:
                    pass
            item[key] = value
        result.append(item)
    return result


def _success_confusion(results: dict[str, Any]) -> dict[str, Any]:
    summary = {
        "truePositive": 0,
        "trueNegative": 0,
        "falsePass": 0,
        "falseFail": 0,
        "invalid": 0,
    }
    per_target: list[dict[str, Any]] = []
    for judge_name, benchmarks in results.items():
        if not isinstance(benchmarks, dict):
            continue
        for benchmark, agents in benchmarks.items():
            if not isinstance(agents, dict):
                continue
            for agent, categories in agents.items():
                if not isinstance(categories, dict):
                    continue
                success = categories.get("trajectory_success")
                if not isinstance(success, dict):
                    continue
                human = success.get("y_human")
                predicted = success.get("y_judge")
                if not isinstance(human, list) or not isinstance(predicted, list):
                    continue
                counts = {
                    "truePositive": 0,
                    "trueNegative": 0,
                    "falsePass": 0,
                    "falseFail": 0,
                    "invalid": 0,
                }
                for expected, actual in zip(human, predicted):
                    if actual not in (0, 1):
                        counts["invalid"] += 1
                    elif expected == 1 and actual == 1:
                        counts["truePositive"] += 1
                    elif expected == 0 and actual == 0:
                        counts["trueNegative"] += 1
                    elif expected == 0 and actual == 1:
                        counts["falsePass"] += 1
                    elif expected == 1 and actual == 0:
                        counts["falseFail"] += 1
                for key, value in counts.items():
                    summary[key] += value
                per_target.append(
                    {
                        "judge": judge_name,
                        "benchmark": benchmark,
                        "agent": agent,
                        **counts,
                    }
                )
    return {"total": summary, "targets": per_target}
