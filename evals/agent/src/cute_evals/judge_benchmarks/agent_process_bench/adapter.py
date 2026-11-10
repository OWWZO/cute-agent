from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from ..base import (
    JUDGE_SCORE_NAMESPACE,
    JudgeBenchmarkRecord,
    JudgeBenchmarkTask,
    JudgePrediction,
    LicenseBlockedError,
    RUNNABLE_LICENSE_STATUSES,
    judge_score_label,
    judge_usage_policy,
)
from ..client import OpenAICompatibleJudge

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"
DATASET_NAMES = ("hotpotqa", "gaia_dev", "bfcl", "tau2")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _iter_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_no, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(),
        start=1,
    ):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{line_no}: invalid JSON: {exc}") from exc
        if not isinstance(row, dict):
            raise ValueError(f"{path}:{line_no}: expected a JSON object")
        rows.append(row)
    return rows


def _record_id(dataset: str, row: dict[str, Any]) -> str:
    record_id = row.get("record_id")
    if isinstance(record_id, str) and record_id.strip():
        return record_id.strip()
    total_index = row.get("total_index")
    if isinstance(total_index, int):
        return f"{dataset}:{total_index}"
    query_index = row.get("query_index")
    sample_index = row.get("sample_index")
    if isinstance(query_index, int) and isinstance(sample_index, int):
        return f"{dataset}:q{query_index}:s{sample_index}"
    raise ValueError(f"{dataset} row cannot be identified by record_id/index")


def _assistant_indices(messages: Any) -> list[int]:
    if not isinstance(messages, list):
        return []
    return [
        index
        for index, message in enumerate(messages)
        if isinstance(message, dict) and message.get("role") == "assistant"
    ]


def _model_tag(model: str) -> str:
    tag = re.sub(r"[^A-Za-z0-9_.-]+", "_", model).strip("_")
    return tag or "judge"


def _parse_compare_output(
    output: str,
    datasets: list[str],
) -> dict[str, Any]:
    rows: dict[str, dict[str, Any]] = {}
    for line in output.splitlines():
        parts = line.split()
        if not parts or parts[0] not in {*datasets, "AVG"}:
            continue
        if len(parts) < 7:
            continue
        try:
            rows[parts[0]] = {
                "referenceRecords": int(parts[1]),
                "predictionRecords": int(parts[2]),
                "missingOrFailedPct": parts[3],
                "stepMicroAccuracy": parts[4],
                "stepExactAccuracy": parts[5],
                "firstErrorAccuracy": parts[6],
            }
        except ValueError as exc:
            raise ValueError(f"invalid AgentProcessBench score row: {line}") from exc
    if "AVG" not in rows:
        raise ValueError("AgentProcessBench compare.py did not emit an AVG row")
    return rows


@dataclass(slots=True)
class AgentProcessBenchAdapter:
    source_dir: Path
    manifest_path: Path = DEFAULT_MANIFEST
    name: str = "agent-process-bench"
    version: str = ""
    upstream: str = ""
    score_label: str = "blocked-license"
    score_namespace: str = JUDGE_SCORE_NAMESPACE
    manifest: dict[str, Any] = field(init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        self.source_dir = self.source_dir.resolve()
        self.manifest_path = self.manifest_path.resolve()
        raw = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise TypeError(f"invalid AgentProcessBench manifest: {self.manifest_path}")
        self.manifest: dict[str, Any] = raw
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
        verified: dict[str, Any] = {"datasets": {}, "scripts": {}}
        for dataset, spec in self.manifest["datasets"].items():
            path = self._path(str(spec["file"]))
            if not path.exists():
                raise FileNotFoundError(path)
            actual = _sha256(path)
            if actual != str(spec["sha256"]).lower():
                raise ValueError(
                    f"AgentProcessBench {dataset} hash mismatch: "
                    f"expected {spec['sha256']}, got {actual}"
                )
            verified["datasets"][dataset] = {
                "path": str(path),
                "sha256": actual,
                "size": path.stat().st_size,
            }
        for name, spec in self.manifest["scripts"].items():
            path = self._path(str(spec["file"]))
            if not path.exists():
                raise FileNotFoundError(path)
            actual = _sha256(path)
            if actual != str(spec["sha256"]).lower():
                raise ValueError(
                    f"AgentProcessBench {name} script hash mismatch: "
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

    def _limit(self, split: str) -> int:
        splits = self.manifest.get("splits") or {}
        if split not in splits:
            raise ValueError(f"unsupported AgentProcessBench split: {split}")
        return int(splits[split])

    def _dataset_rows(self, dataset: str) -> list[dict[str, Any]]:
        spec = self.manifest["datasets"][dataset]
        return _iter_jsonl(self._path(str(spec["file"])))

    def tasks(self, split: str) -> list[JudgeBenchmarkTask]:
        limit = self._limit(split)
        tasks: list[JudgeBenchmarkTask] = []
        for dataset in DATASET_NAMES:
            rows = self._dataset_rows(dataset)
            selected = rows if limit < 0 else rows[:limit]
            for row in selected:
                messages = row.get("messages")
                indices = _assistant_indices(messages)
                if not indices:
                    raise ValueError(
                        f"AgentProcessBench task has no assistant steps: "
                        f"{_record_id(dataset, row)}"
                    )
                tasks.append(
                    JudgeBenchmarkTask(
                        id=_record_id(dataset, row),
                        category=dataset,
                        messages=list(messages),
                        assistant_message_indices=indices,
                        tools=[
                            item
                            for item in row.get("tools") or []
                            if isinstance(item, dict)
                        ],
                        raw=row,
                        metadata={
                            "dataset": dataset,
                            "split": split,
                            "groundTruthStepLabels": row.get("step_labels") or {},
                            "groundTruthFinalLabel": row.get("final_label"),
                        },
                    )
                )
        return tasks

    def _write_subset_references(
        self,
        *,
        output_dir: Path,
        datasets: list[str],
        limit: int,
    ) -> Path:
        reference_dir = output_dir / "reference-subset"
        reference_dir.mkdir(parents=True, exist_ok=True)
        for dataset in datasets:
            rows = self._dataset_rows(dataset)
            selected = rows if limit < 0 else rows[:limit]
            target = reference_dir / f"{dataset}.jsonl"
            target.write_text(
                "".join(
                    json.dumps(row, ensure_ascii=False) + "\n"
                    for row in selected
                ),
                encoding="utf-8",
            )
        return reference_dir

    def run(
        self,
        judge: OpenAICompatibleJudge,
        *,
        split: str,
        output_dir: Path,
        concurrency: int = 1,
        timeout_seconds: int | None = None,
    ) -> dict[str, Any]:
        self.assert_run_allowed()
        if concurrency < 1:
            raise ValueError("concurrency must be >= 1")
        if importlib.util.find_spec("openai") is None:
            raise RuntimeError(
                "AgentProcessBench official harness requires the 'openai' "
                "package in the active Python environment"
            )
        assets = self.verify_assets()
        limit = self._limit(split)
        output_dir = output_dir.resolve()
        predictions_dir = output_dir / "predictions"
        raw_dir = output_dir / "raw"
        predictions_dir.mkdir(parents=True, exist_ok=True)
        raw_dir.mkdir(parents=True, exist_ok=True)
        reference_dir = self._write_subset_references(
            output_dir=output_dir,
            datasets=list(DATASET_NAMES),
            limit=limit,
        )

        annotate_script = Path(assets["scripts"]["annotate"]["path"])
        compare_script = Path(assets["scripts"]["compare"]["path"])
        model_tag = _model_tag(judge.model)
        env = os.environ.copy()
        env["OPENAI_BASE_URL"] = judge.base_url
        env["OPENAI_API_KEY"] = judge.api_key
        commands: list[dict[str, Any]] = []
        for dataset in DATASET_NAMES:
            output_path = predictions_dir / f"{dataset}__blind_{model_tag}.jsonl"
            command = [
                sys.executable,
                str(annotate_script),
                "--input_path",
                str(self._path(str(self.manifest["datasets"][dataset]["file"]))),
                "--dataset",
                dataset,
                "--model",
                judge.model,
                "--temperature",
                str(judge.temperature),
                "--top_p",
                str(judge.top_p),
                "--max_tokens",
                str(judge.max_tokens),
                "--timeout_s",
                str(timeout_seconds or judge.timeout_seconds),
                "--output_path",
                str(output_path),
                "--raw_output_dir",
                str(raw_dir),
                "--concurrency",
                str(concurrency),
                "--start",
                "0",
                "--end",
                str(limit),
            ]
            result = subprocess.run(
                command,
                cwd=self.source_dir,
                env=env,
                text=True,
                capture_output=True,
                timeout=None,
                check=False,
            )
            commands.append(
                {
                    "dataset": dataset,
                    "returnCode": result.returncode,
                    "stdoutTail": result.stdout[-2000:],
                    "stderrTail": result.stderr[-2000:],
                }
            )
            if result.returncode != 0:
                raise RuntimeError(
                    f"AgentProcessBench annotate failed for {dataset}: "
                    f"{result.stderr[-1000:]}"
                )

        expected_records = int(
            self.manifest["expectedRecordsPerDataset"]
            if limit < 0
            else limit
        )
        compare_command = [
            sys.executable,
            str(compare_script),
            "--reference_dir",
            str(reference_dir),
            "--models_root_dir",
            str(predictions_dir),
            "--datasets",
            ",".join(DATASET_NAMES),
            "--expected_reference_records",
            str(expected_records),
            "--run_name_grouping",
            "raw",
        ]
        compared = subprocess.run(
            compare_command,
            cwd=self.source_dir,
            text=True,
            capture_output=True,
            check=False,
        )
        if compared.returncode != 0:
            raise RuntimeError(
                f"AgentProcessBench compare failed: {compared.stderr[-1000:]}"
            )
        metrics = _parse_compare_output(compared.stdout, list(DATASET_NAMES))
        records = self._prediction_records(predictions_dir, model_tag)
        score_label = judge_score_label(
            self.license_status,
            "official-full" if limit < 0 else "official-subset",
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
            },
            "benchmark": {
                "name": self.name,
                "version": self.version,
                "upstream": self.upstream,
                "upstreamCommit": self.manifest.get("upstreamCommit"),
                "datasetHash": self.dataset_hash(assets),
                "evaluatorHash": assets["scripts"]["compare"]["sha256"],
                "licenseStatus": self.license_status,
            },
            "scoreNamespace": self.score_namespace,
            "scoreLabel": score_label,
            "usagePolicy": judge_usage_policy(self.license_status),
            "tasks": [record.to_dict() for record in records],
            "metrics": {
                "datasets": metrics,
                "split": split,
            },
            "environment": {
                "commands": commands,
                "compareStdout": compared.stdout,
                "compareStderr": compared.stderr,
            },
        }
        (output_dir / "report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return report

    def _prediction_records(
        self,
        predictions_dir: Path,
        model_tag: str,
    ) -> list[JudgeBenchmarkRecord]:
        records: list[JudgeBenchmarkRecord] = []
        for dataset in DATASET_NAMES:
            path = predictions_dir / f"{dataset}__blind_{model_tag}.jsonl"
            for row in _iter_jsonl(path):
                comment = str(row.get("comment") or "")
                failed = comment.startswith("llm_annotate_failed:")
                prediction = None
                if not failed:
                    prediction = JudgePrediction(
                        task_id=_record_id(dataset, row),
                        step_labels={
                            str(key): int(value)
                            for key, value in (row.get("step_labels") or {}).items()
                            if value in {-1, 0, 1}
                        },
                        final_label=int(row.get("final_label") or 0),
                        explanations=dict(row.get("explanations") or {}),
                        raw=row,
                    )
                records.append(
                    JudgeBenchmarkRecord(
                        task_id=_record_id(dataset, row),
                        status="failed" if failed else "verified",
                        prediction=prediction,
                        error=(
                            {"kind": "judge_failed", "message": comment}
                            if failed
                            else None
                        ),
                        metadata={"dataset": dataset},
                    )
                )
        return records

    @staticmethod
    def dataset_hash(assets: dict[str, Any]) -> str:
        payload = "\n".join(
            f"{name}:{spec['sha256']}"
            for name, spec in sorted(assets["datasets"].items())
        )
        return "sha256:" + hashlib.sha256(payload.encode("utf-8")).hexdigest()

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
