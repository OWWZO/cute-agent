from __future__ import annotations

import ast
import hashlib
import importlib.util
import json
import os
import re
import shutil
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
    LicenseBlockedError,
    RUNNABLE_LICENSE_STATUSES,
    judge_score_label,
    judge_usage_policy,
)
from ..client import JudgeRequestError, OpenAICompatibleJudge

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"
DOMAIN_TOKENS = {"GAIA": "gaia", "SWE Bench": "swe_bench"}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _model_tag(model: str) -> str:
    tag = re.sub(r"[^A-Za-z0-9_.-]+", "_", model).strip("_")
    return tag or "judge"


@dataclass(frozen=True, slots=True)
class TrailTask:
    id: str
    domain: str
    trace_path: Path
    annotation_path: Path
    trace_text: str
    split: str


@dataclass(slots=True)
class TrailAdapter:
    source_dir: Path
    manifest_path: Path = DEFAULT_MANIFEST
    name: str = "trail"
    version: str = ""
    upstream: str = ""
    score_label: str = "blocked-license"
    score_namespace: str = JUDGE_SCORE_NAMESPACE
    manifest: dict[str, Any] = field(init=False, repr=False, compare=False)
    _prompt_template: str | None = field(
        init=False,
        default=None,
        repr=False,
        compare=False,
    )

    def __post_init__(self) -> None:
        self.source_dir = self.source_dir.resolve()
        self.manifest_path = self.manifest_path.resolve()
        raw = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise TypeError(f"invalid TRAIL manifest: {self.manifest_path}")
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

    def _git_lines(self, *args: str) -> list[str]:
        git = shutil.which("git.exe") or shutil.which("git")
        if git is None:
            raise RuntimeError("TRAIL verification requires git")
        result = subprocess.run(
            [git, "-C", str(self.source_dir), *args],
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(f"git verification failed: {result.stderr.strip()}")
        return result.stdout.splitlines()

    def _tree_hash(self, relative: str) -> str:
        mode = str(self.manifest.get("verification") or "git-tree")
        if mode == "git-tree":
            commit = str(self.manifest["upstreamCommit"])
            lines = self._git_lines("ls-tree", "-r", commit, "--", relative)
            payload = ("\n".join(lines) + "\n").encode("utf-8")
            return hashlib.sha256(payload).hexdigest()
        if mode == "content":
            path = self._path(relative)
            lines = [
                f"{file.relative_to(path).as_posix()}:{_sha256(file)}"
                for file in sorted(path.rglob("*"))
                if file.is_file()
            ]
            return hashlib.sha256(
                ("\n".join(lines) + "\n").encode("utf-8")
            ).hexdigest()
        raise ValueError(f"unsupported TRAIL verification mode: {mode}")

    def verify_assets(self) -> dict[str, Any]:
        mode = str(self.manifest.get("verification") or "git-tree")
        if mode == "git-tree":
            actual_commit = self._git_lines("rev-parse", "HEAD")[0].strip()
            expected_commit = str(self.manifest["upstreamCommit"])
            if actual_commit != expected_commit:
                raise ValueError(
                    f"TRAIL checkout commit mismatch: expected {expected_commit}, "
                    f"got {actual_commit}"
                )
        verified: dict[str, Any] = {
            "data": {},
            "annotations": {},
            "scripts": {},
        }
        for group in ("data", "annotations"):
            for domain, spec in self.manifest["assets"][group].items():
                path = self._path(str(spec["path"]))
                if not path.is_dir():
                    raise FileNotFoundError(path)
                actual = self._tree_hash(str(spec["path"]))
                if actual != str(spec["treeSha256"]).lower():
                    raise ValueError(
                        f"TRAIL {group}/{domain} hash mismatch: "
                        f"expected {spec['treeSha256']}, got {actual}"
                    )
                verified[group][domain] = {
                    "path": str(path),
                    "treeSha256": actual,
                    "fileCount": sum(
                        1 for item in path.rglob("*.json") if item.is_file()
                    ),
                }
        for name, spec in self.manifest["scripts"].items():
            path = self._path(str(spec["file"]))
            if not path.is_file():
                raise FileNotFoundError(path)
            actual = _sha256(path)
            if actual != str(spec["sha256"]).lower():
                raise ValueError(
                    f"TRAIL {name} script hash mismatch: "
                    f"expected {spec['sha256']}, got {actual}"
                )
            verified["scripts"][name] = {
                "path": str(path),
                "sha256": actual,
            }
        return verified

    def assert_run_allowed(self) -> None:
        if self.license_status not in RUNNABLE_LICENSE_STATUSES:
            raise LicenseBlockedError(
                self.name,
                self.license_status,
                str(self.manifest.get("licenseEvidence") or ""),
            )

    def _split_limits(self, split: str) -> dict[str, int]:
        splits = self.manifest.get("splits") or {}
        if split not in splits:
            raise ValueError(f"unsupported TRAIL split: {split}")
        return {
            str(domain): int(limit)
            for domain, limit in splits[split].items()
        }

    def _tasks_for_domain(
        self,
        *,
        domain: str,
        limit: int,
        split: str,
    ) -> list[TrailTask]:
        if limit == 0:
            return []
        data_dir = self._path(
            str(self.manifest["assets"]["data"][domain]["path"])
        )
        annotation_dir = self._path(
            str(self.manifest["assets"]["annotations"][domain]["path"])
        )
        files = sorted(data_dir.glob("*.json"))
        selected = files if limit < 0 else files[:limit]
        tasks: list[TrailTask] = []
        for trace_path in selected:
            annotation_path = annotation_dir / trace_path.name
            if not annotation_path.is_file():
                raise FileNotFoundError(
                    f"TRAIL annotation missing for {trace_path.name}"
                )
            tasks.append(
                TrailTask(
                    id=f"{DOMAIN_TOKENS[domain]}:{trace_path.stem}",
                    domain=domain,
                    trace_path=trace_path,
                    annotation_path=annotation_path,
                    trace_text=trace_path.read_text(encoding="utf-8"),
                    split=split,
                )
            )
        return tasks

    def tasks(self, split: str) -> list[TrailTask]:
        tasks: list[TrailTask] = []
        for domain, limit in self._split_limits(split).items():
            tasks.extend(
                self._tasks_for_domain(
                    domain=domain,
                    limit=limit,
                    split=split,
                )
            )
        return tasks

    def _official_prompt_template(self) -> str:
        if self._prompt_template is not None:
            return self._prompt_template
        spec = self.manifest["scripts"]["prompt"]
        path = self._path(str(spec["file"]))
        tree = ast.parse(path.read_text(encoding="utf-8"))
        prompt_function = str(spec["promptFunction"])
        for node in ast.walk(tree):
            if not isinstance(node, ast.FunctionDef) or node.name != prompt_function:
                continue
            for item in node.body:
                if not isinstance(item, ast.Assign):
                    continue
                if not any(
                    isinstance(target, ast.Name) and target.id == "prompt"
                    for target in item.targets
                ):
                    continue
                try:
                    value = ast.literal_eval(item.value)
                except (ValueError, TypeError):
                    continue
                if isinstance(value, str) and "{trace}" in value:
                    self._prompt_template = value
                    return value
        raise ValueError(
            f"cannot extract official TRAIL prompt from {path}"
        )

    def build_prompt(self, task: TrailTask) -> str:
        return self.official_prompt_template().format(trace=task.trace_text)

    def official_prompt_template(self) -> str:
        return self._official_prompt_template()

    def run(
        self,
        judge: OpenAICompatibleJudge,
        *,
        split: str,
        output_dir: Path,
        concurrency: int = 1,
    ) -> dict[str, Any]:
        self.assert_run_allowed()
        if concurrency < 1:
            raise ValueError("concurrency must be >= 1")
        for dependency in ("numpy", "sklearn", "scipy"):
            if importlib.util.find_spec(dependency) is None:
                raise RuntimeError(
                    f"TRAIL official scorer requires the '{dependency}' package"
                )
        assets = self.verify_assets()
        tasks = self.tasks(split)
        if not tasks:
            raise ValueError("TRAIL selected no tasks")
        output_dir = output_dir.resolve()
        workspace = output_dir / "score-workspace"
        results_dir = workspace / "results"
        results_dir.mkdir(parents=True, exist_ok=True)
        model_tag = _model_tag(judge.model)
        records: list[JudgeBenchmarkRecord] = []

        def _run_task(task: TrailTask) -> tuple[TrailTask, str, dict[str, Any] | None]:
            prompt = self.build_prompt(task)
            try:
                response = judge.complete(
                    [{"role": "user", "content": prompt}]
                )
            except JudgeRequestError as exc:
                return task, "", {
                    "kind": exc.kind,
                    "message": str(exc),
                }
            error = _validate_trail_response(response)
            return task, response, error

        with ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = [executor.submit(_run_task, task) for task in tasks]
            for future in as_completed(futures):
                task, response, error = future.result()
                domain_token = DOMAIN_TOKENS[task.domain]
                annotation_dir = (
                    workspace / f"processed_annotations_{domain_token}"
                )
                result_dir = results_dir / f"outputs_{model_tag}-{task.domain}"
                annotation_dir.mkdir(parents=True, exist_ok=True)
                result_dir.mkdir(parents=True, exist_ok=True)
                shutil.copy2(
                    task.annotation_path,
                    annotation_dir / task.annotation_path.name,
                )
                if response:
                    (result_dir / task.trace_path.name).write_text(
                        response,
                        encoding="utf-8",
                    )
                records.append(
                    JudgeBenchmarkRecord(
                        task_id=task.id,
                        status="failed" if error else "verified",
                        raw_response=response,
                        error=error,
                        metadata={"domain": task.domain},
                    )
                )

        records.sort(key=lambda item: item.task_id)
        scorer = Path(assets["scripts"]["scorer"]["path"])
        scored = subprocess.run(
            [
                sys.executable,
                str(scorer),
                "--results_dir",
                "results",
            ],
            cwd=workspace,
            text=True,
            capture_output=True,
            check=False,
        )
        if scored.returncode != 0:
            raise RuntimeError(
                f"TRAIL official scorer failed: {scored.stderr[-2000:]}"
            )
        metrics = _parse_metric_files(results_dir)
        complete = all(record.status == "verified" for record in records)
        score_label = judge_score_label(
            self.license_status,
            (
                "official-full" if split == "official-full"
                else "official-subset"
            ) if complete else "partial-not-official",
        )
        report = {
            "schemaVersion": "1.0",
            "subject": {
                "type": "judge",
                "model": judge.model,
                "baseUrl": judge.base_url,
                "temperature": judge.temperature,
                "topP": judge.top_p,
                "maxTokens": judge.max_tokens,
                "promptHash": assets["scripts"]["prompt"]["sha256"],
            },
            "benchmark": {
                "name": self.name,
                "version": self.version,
                "upstream": self.upstream,
                "upstreamCommit": self.manifest.get("upstreamCommit"),
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
                "domains": metrics,
            },
            "environment": {
                "scorerStdout": scored.stdout,
                "scorerStderr": scored.stderr,
                "workspace": str(workspace),
            },
        }
        (output_dir / "report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return report

    @staticmethod
    def dataset_hash(assets: dict[str, Any]) -> str:
        entries: list[str] = []
        for group in ("data", "annotations"):
            for domain, spec in sorted(assets[group].items()):
                entries.append(f"{group}/{domain}:{spec['treeSha256']}")
        return "sha256:" + hashlib.sha256(
            ("\n".join(entries) + "\n").encode("utf-8")
        ).hexdigest()

    def report_metadata(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "upstreamCommit": self.manifest.get("upstreamCommit"),
            "licenseStatus": self.license_status,
            "scoreNamespace": self.score_namespace,
            "scoreLabel": self.score_label,
            "usagePolicy": judge_usage_policy(self.license_status),
        }


def _validate_trail_response(response: str) -> dict[str, Any] | None:
    try:
        payload = json.loads(response)
    except json.JSONDecodeError as exc:
        return {"kind": "invalid_json", "message": str(exc)}
    if not isinstance(payload, dict):
        return {"kind": "invalid_json", "message": "response must be an object"}
    if not isinstance(payload.get("errors"), list):
        return {"kind": "invalid_schema", "message": "errors must be a list"}
    if not isinstance(payload.get("scores"), list) or not payload["scores"]:
        return {
            "kind": "invalid_schema",
            "message": "scores must be a non-empty list",
        }
    return None


def _parse_metric_files(results_dir: Path) -> dict[str, Any]:
    metrics: dict[str, Any] = {}
    for path in sorted(results_dir.glob("*-metrics.txt")):
        text = path.read_text(encoding="utf-8")
        values: dict[str, Any] = {"raw": text}
        patterns = {
            "weightedF1": r"Weighted F1:\s*([0-9.]+)",
            "locationAccuracy": r"Average Location Accuracy:\s*([0-9.]+)",
            "jointAccuracy": (
                r"Average Location-Category Joint Accuracy:\s*([0-9.]+)"
            ),
        }
        for name, pattern in patterns.items():
            match = re.search(pattern, text)
            if match:
                values[name] = float(match.group(1))
        metrics[path.name.removesuffix("-metrics.txt")] = values
    if not metrics:
        raise ValueError("TRAIL official scorer produced no metric files")
    return metrics
