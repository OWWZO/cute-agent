from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import re
import shutil
from pathlib import Path
from types import ModuleType
from typing import Any

import yaml

from ...models import (
    BudgetSpec,
    CaseResult,
    EvalCase,
    ExpectedSpec,
    RunTrace,
    ScoreRecord,
)
from ..base import BenchmarkRun, BenchmarkTask, PreparedTask

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _json_hash(payload: Any) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    ).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


def _contract_line(contract: dict[str, Any] | None, answer: str) -> str:
    if not contract:
        return answer
    prefix = str(contract.get("linePrefix") or "FINAL_ANSWER:").strip()
    return f"{prefix} {answer}".strip()


def _extract_final_answer(
    text: str,
    contract: dict[str, Any] | None,
) -> str | None:
    if not contract:
        return text
    prefix = str(contract.get("linePrefix") or "FINAL_ANSWER:").strip()
    pattern = re.compile(
        rf"(?im)^\s*{re.escape(prefix)}\s*(.+?)\s*$",
    )
    matches = pattern.findall(text or "")
    return matches[-1].strip() if matches else None


class GAIAAdapter:
    name = "gaia"

    def __init__(
        self,
        manifest_path: Path = DEFAULT_MANIFEST,
        *,
        data_home: Path | None = None,
        metadata_path: Path | None = None,
    ) -> None:
        self.manifest_path = manifest_path.resolve()
        self.manifest = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(self.manifest, dict):
            raise TypeError(f"invalid GAIA manifest: {self.manifest_path}")
        self.version = str(self.manifest["version"])
        self.upstream = str(self.manifest["upstream"])
        self.score_label = str(self.manifest["scoreLabel"])
        revision = str(self.manifest["dataset"]["revision"])
        self.data_home = (
            data_home
            or Path(
                os.environ.get("EVAL_DATA_HOME", Path.home() / ".cache" / "cute-evals")
            )
        ).resolve()
        self.cache_dir = self.data_home / "gaia" / revision
        self.metadata_path = metadata_path
        self._tasks: dict[str, list[BenchmarkTask]] = {}
        self._rows: dict[str, list[dict[str, Any]]] = {}
        self._active_split = "official-subset"
        self._scorer: ModuleType | None = None

    def _official_scorer(self) -> ModuleType:
        if self._scorer is not None:
            return self._scorer
        scorer_path = PACKAGE_ROOT / str(self.manifest["scorer"]["file"])
        expected = str(self.manifest["scorer"]["sha256"]).lower()
        actual = _sha256(scorer_path)
        if actual != expected:
            raise ValueError(
                f"GAIA official scorer hash mismatch: expected {expected}, got {actual}"
            )
        spec = importlib.util.spec_from_file_location(
            "cute_evals_gaia_official_scorer",
            scorer_path,
        )
        if spec is None or spec.loader is None:
            raise ImportError(f"cannot load GAIA scorer: {scorer_path}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self._scorer = module
        return module

    def _load_rows(self, split: str) -> list[dict[str, Any]]:
        if split in self._rows:
            return list(self._rows[split])
        if self.metadata_path is not None:
            rows = self._read_jsonl(self.metadata_path)
            selected = self._select_rows(rows, split)
            self._rows[split] = selected
            return list(selected)

        cached = self.cache_dir / "metadata.jsonl"
        if cached.exists():
            selected = self._select_rows(self._read_jsonl(cached), split)
            self._rows[split] = selected
            return list(selected)

        try:
            from datasets import load_dataset
        except ImportError as exc:
            raise RuntimeError(
                "GAIA dataset loading requires the optional 'datasets' package; "
                "install the phase3 extra or provide EVAL_DATA_HOME metadata"
            ) from exc

        rows: list[dict[str, Any]] = []
        revision = str(self.manifest["dataset"]["revision"])
        for config in self.manifest["dataset"]["configs"]:
            dataset = load_dataset(
                str(self.manifest["dataset"]["repo"]),
                config,
                split="validation",
                revision=revision,
            )
            rows.extend(dict(row) for row in dataset)
        selected = self._select_rows(rows, split)
        self._rows[split] = selected
        return list(selected)

    @staticmethod
    def _read_jsonl(path: Path) -> list[dict[str, Any]]:
        rows = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError(f"GAIA row must be an object: {path}")
                rows.append(row)
        return rows

    def _select_rows(
        self,
        rows: list[dict[str, Any]],
        split: str,
    ) -> list[dict[str, Any]]:
        if split not in {"official-subset", "official-full"}:
            raise ValueError(f"unsupported GAIA split: {split}")
        selected: list[dict[str, Any]] = []
        configs = self.manifest["dataset"]["configs"]
        subset_limits = self.manifest["dataset"].get("subsetLimits") or configs
        for config in configs:
            level = str(config).removeprefix("2023_level")
            level_rows = [
                row for row in rows
                if str(row.get("Level", "")) == level
                or str(row.get("level", "")) == level
            ]
            level_rows.sort(key=lambda row: str(row.get("task_id", row.get("taskId", ""))))
            selected.extend(
                level_rows[: int(subset_limits[config])]
                if split == "official-subset"
                else level_rows
            )
        if not selected:
            raise ValueError("GAIA selection produced no tasks")
        return sorted(
            selected,
            key=lambda row: str(row.get("task_id", row.get("taskId", ""))),
        )

    def tasks(self, split: str) -> list[BenchmarkTask]:
        if split not in {"official-subset", "official-full"}:
            raise ValueError(f"unsupported GAIA split: {split}")
        self._active_split = split
        if split in self._tasks:
            return list(self._tasks[split])
        tasks: list[BenchmarkTask] = []
        for row in self._load_rows(split):
            task_id = str(row.get("task_id") or row.get("taskId") or "")
            question = str(row.get("Question") or row.get("question") or "")
            answer = str(row.get("Final answer") or row.get("final_answer") or "")
            level = str(row.get("Level") or row.get("level") or "")
            file_name = str(row.get("file_name") or row.get("fileName") or "")
            file_path = str(row.get("file_path") or row.get("filePath") or "")
            answer_contract = self.manifest.get("answerContract")
            if not task_id or not question or not answer:
                raise ValueError(f"invalid GAIA task row: {task_id or row}")
            tasks.append(
                BenchmarkTask(
                    id=task_id,
                    category=f"gaia-level-{level}",
                    question=question,
                    functions=[],
                    ground_truth=[{"answer": answer}],
                    raw=row,
                    turns=[{"query": question}],
                    metadata={
                        "level": level,
                        "answer": answer,
                        "file_name": file_name or None,
                        "file_path": file_path or None,
                        "answer_contract": answer_contract,
                        "split": split,
                    },
                )
            )
        self._tasks[split] = tasks
        return list(tasks)

    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask:
        workspace.mkdir(parents=True, exist_ok=True)
        attachment = self._materialize_attachment(task, workspace)
        return PreparedTask(
            task=task,
            workspace=workspace,
            metadata={"attachment": attachment},
        )

    def _materialize_attachment(
        self,
        task: BenchmarkTask,
        workspace: Path,
    ) -> dict[str, Any] | None:
        file_name = task.metadata.get("file_name")
        file_path = task.metadata.get("file_path")
        if not file_name:
            return None
        safe_name = Path(str(file_name)).name
        cached = self.cache_dir / "files" / safe_name
        if not cached.exists():
            try:
                from huggingface_hub import hf_hub_download
            except ImportError:
                return {
                    "fileName": file_name,
                    "available": False,
                    "reason": "huggingface_hub is not installed",
                }
            try:
                downloaded = hf_hub_download(
                    repo_id=str(self.manifest["dataset"]["repo"]),
                    filename=str(file_path or file_name),
                    repo_type="dataset",
                    revision=str(self.manifest["dataset"]["revision"]),
                )
                cached.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(downloaded, cached)
            except Exception as exc:  # noqa: BLE001 - surface provider failure as unverified
                return {
                    "fileName": file_name,
                    "available": False,
                    "reason": str(exc),
                }
        target = workspace / safe_name
        shutil.copy2(cached, target)
        digest = _sha256(target)
        manifest = {
            "fileName": str(file_name),
            "available": True,
            "sha256": digest,
            "size": target.stat().st_size,
            "officialRevision": self.manifest["dataset"]["revision"],
        }
        (workspace / "attachment-manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return manifest

    def fixture_script(self, task: BenchmarkTask) -> dict[str, Any]:
        answer = str(task.metadata.get("answer") or "")
        contract = task.metadata.get("answer_contract")
        return {
            "id": task.id,
            "query": task.question,
            "script": [
                {"type": "final", "content": _contract_line(contract, answer)}
            ],
            "tools": [],
        }

    def case_for_task(self, task: BenchmarkTask) -> EvalCase:
        budgets = self.manifest["budgets"]
        contract = task.metadata.get("answer_contract")
        split = str(task.metadata.get("split") or "official-subset")
        return EvalCase(
            id=task.id,
            suite=f"gaia-{split}",
            version=1,
            hard_gate=True,
            query=task.question,
            expected=ExpectedSpec(max_steps=int(budgets["maxSteps"])),
            budgets=BudgetSpec(
                max_total_tokens=int(budgets["maxTokens"]),
                max_wall_ms=int(budgets["maxWallMs"]),
                max_tool_calls=(
                    int(budgets["maxToolCalls"])
                    if budgets.get("maxToolCalls") is not None
                    else None
                ),
            ),
            script=[
                {
                    "type": "final",
                    "content": _contract_line(
                        contract,
                        str(task.metadata["answer"]),
                    ),
                }
            ],
            source_path=str(self.manifest_path),
            tools=[],
            answer_contract=contract,
            metadata={
                "level": str(task.metadata.get("level") or ""),
                "split": split,
            },
        )

    def collect(
        self,
        task: BenchmarkTask,
        trace: RunTrace,
        trace_path: str,
        *,
        prepared: PreparedTask | None = None,
    ) -> BenchmarkRun:
        return BenchmarkRun(
            task=task,
            trace=trace,
            trace_path=trace_path,
            calls=[],
            conversation=trace.conversation,
            environment_events=trace.environment_events,
            environment_snapshots=trace.environment_snapshots,
            metadata={"attachment": (prepared.metadata if prepared else {}).get("attachment")},
        )

    def score(self, run: BenchmarkRun) -> CaseResult:
        scorer = self._official_scorer()
        expected = str(run.task.metadata["answer"])
        raw_answer = run.trace.final_text
        contract = run.task.metadata.get("answer_contract")
        final_answer = _extract_final_answer(raw_answer, contract)
        contract_present = contract is None or final_answer is not None
        answer_for_score = final_answer if final_answer is not None else ""
        matched = contract_present and bool(
            scorer.question_scorer(answer_for_score, expected)
        )
        attachment = run.metadata.get("attachment")
        attachment_required = bool(run.task.metadata.get("file_name"))
        attachment_available = not attachment_required or bool(
            attachment and attachment.get("available")
        )
        scores = [
            ScoreRecord(
                case_id=run.task.id,
                scorer="gaia.official",
                name="official_exact_match",
                value=matched,
                unit="boolean",
                passed=matched,
                hard_gate=True,
                status="verified" if attachment_available else "unverified",
                evidence={
                    "officialScorer": "question_scorer",
                    "expectedAnswer": expected,
                    "actualAnswer": answer_for_score,
                    "rawAnswer": raw_answer,
                    "answerContractPresent": contract_present,
                    "answerContractVersion": (
                        str(contract.get("version")) if contract else None
                    ),
                    "attachmentAvailable": attachment_available,
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="gaia.metrics",
                name="gaia_level",
                value=int(run.task.metadata.get("level") or 0),
                unit="level",
                passed=None,
                hard_gate=False,
                status="verified",
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="gaia.metrics",
                name="attachment_available",
                value=attachment_available,
                unit="boolean",
                passed=None if not attachment_required else attachment_available,
                hard_gate=False,
                status="verified",
                evidence={"attachment": attachment},
            ),
        ]
        if contract is not None:
            scores.insert(
                1,
                ScoreRecord(
                    case_id=run.task.id,
                    scorer="gaia.contract",
                    name="answer_contract",
                    value=contract_present,
                    unit="boolean",
                    passed=contract_present,
                    hard_gate=True,
                    status="verified",
                    evidence={
                        "version": contract.get("version"),
                        "linePrefix": contract.get("linePrefix"),
                        "rawAnswer": raw_answer,
                        "finalAnswer": final_answer,
                    },
                ),
            )
        return CaseResult(
            case=self.case_for_task(run.task),
            trace=run.trace,
            scores=scores,
            trace_path=run.trace_path,
        )

    def dataset_hash(self) -> str:
        rows = self._load_rows(self._active_split)
        scorer_path = PACKAGE_ROOT / str(self.manifest["scorer"]["file"])
        return _json_hash(
            {
                "name": self.name,
                "version": self.version,
                "revision": self.manifest["dataset"]["revision"],
                "rows": rows,
                "scorerSha256": _sha256(scorer_path),
                "answerContract": self.manifest.get("answerContract"),
            }
        )

    def report_metadata(self) -> dict[str, Any]:
        split = self._active_split
        score_label = (
            split
            if split == "official-full"
            else str(self.manifest.get("scoreLabel") or split)
        )
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "license": self.manifest["license"],
            "split": split,
            "scoreLabel": score_label,
            "officialEvaluator": True,
            "runnerMode": self.manifest["runnerMode"],
            "revision": self.manifest["dataset"]["revision"],
            "selection": (
                self.manifest["dataset"]["selection"]
                if split == "official-subset"
                else "all"
            ),
            "selectedTaskIds": [task.id for task in self.tasks(split)],
            "scorerSha256": self.manifest["scorer"]["sha256"],
            "answerContract": self.manifest.get("answerContract"),
            "answerContractHash": _json_hash(self.manifest.get("answerContract")),
            "datasetHash": self.dataset_hash(),
        }
