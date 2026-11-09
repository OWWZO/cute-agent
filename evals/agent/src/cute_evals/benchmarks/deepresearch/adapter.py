from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any
from urllib.request import Request, urlopen

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
from .official import evaluate_official

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"
URL_PATTERN = re.compile(r"https?://[^\s)>]+")


def _canonical_text_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def _json_hash(payload: Any) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    ).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


class DeepResearchAdapter:
    name = "deepresearch"

    def __init__(
        self,
        manifest_path: Path = DEFAULT_MANIFEST,
        *,
        data_home: Path | None = None,
        source_dir: Path | None = None,
        query_path: Path | None = None,
        target_model: str = "cute-agent",
        limit: int | None = None,
        expected_task_count: int | None = None,
    ) -> None:
        self.manifest_path = manifest_path.resolve()
        raw = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise TypeError(f"invalid DeepResearch Bench manifest: {self.manifest_path}")
        self.manifest: dict[str, Any] = raw
        self.version = str(raw["version"])
        self.upstream = str(raw["upstream"])
        self.score_label = str(raw["scoreLabel"])
        self.data_home = (
            data_home
            or Path(
                os.environ.get("EVAL_DATA_HOME", Path.home() / ".cache" / "cute-evals")
            )
        ).resolve()
        self.source_dir = source_dir.resolve() if source_dir else None
        self.query_path = query_path.resolve() if query_path else None
        self.target_model = target_model
        self.limit = limit
        self.expected_task_count = (
            expected_task_count
            if expected_task_count is not None
            else int(raw["dataset"]["officialTaskCount"])
        )
        self._rows: list[dict[str, Any]] | None = None
        self._tasks: list[BenchmarkTask] | None = None
        self._official_results: list[dict[str, Any]] = []

    def _query_file(self) -> Path:
        if self.query_path is not None:
            return self.query_path
        if self.source_dir is not None:
            candidate = self.source_dir / str(
                self.manifest["dataset"]["queryFile"]
            )
            if candidate.exists():
                return candidate
        cache = (
            self.data_home
            / "deepresearch"
            / self.version
            / "data"
            / "query.jsonl"
        )
        if cache.exists():
            return cache
        cache.parent.mkdir(parents=True, exist_ok=True)
        url = (
            "https://raw.githubusercontent.com/Ayanami0730/"
            f"deep_research_bench/{self.version.removeprefix('gh-')}/"
            f"{self.manifest['dataset']['queryFile']}"
        )
        request = Request(url, headers={"User-Agent": "cute-evals"})
        with urlopen(request, timeout=300) as response:
            cache.write_bytes(response.read())
        return cache

    def _load_rows(self) -> list[dict[str, Any]]:
        if self._rows is not None:
            return list(self._rows)
        path = self._query_file()
        expected = str(self.manifest["dataset"]["querySha256"]).lower()
        actual = _canonical_text_sha256(path).lower()
        if actual != expected:
            raise ValueError(
                "DeepResearch Bench query hash mismatch: "
                f"expected {expected}, got {actual}"
            )
        rows: list[dict[str, Any]] = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError("DeepResearch Bench query row must be an object")
            task_id = row.get("id")
            prompt = str(row.get("prompt") or "")
            if task_id is None or not prompt:
                raise ValueError("DeepResearch Bench query requires id and prompt")
            rows.append(row)
        if self.limit is not None:
            if self.limit < 1:
                raise ValueError("--limit must be >= 1")
            rows = rows[: self.limit]
        if self.limit is None and len(rows) != self.expected_task_count:
            raise ValueError(
                "DeepResearch Bench official-full requires "
                f"{self.expected_task_count} tasks, got {len(rows)}"
            )
        counts: dict[str, int] = {}
        for row in rows:
            language = str(row.get("language") or "")
            counts[language] = counts.get(language, 0) + 1
        if self.limit is None:
            expected_languages = {
                str(key): int(value)
                for key, value in (
                    self.manifest["dataset"].get("languages") or {}
                ).items()
            }
            if counts != expected_languages:
                raise ValueError(
                    "DeepResearch Bench language distribution mismatch: "
                    f"expected {expected_languages}, got {counts}"
                )
        self._rows = rows
        return list(rows)

    def tasks(self, split: str) -> list[BenchmarkTask]:
        if split != "official-full":
            raise ValueError(
                "DeepResearch Bench only supports --split official-full; "
                "use --limit for a non-official smoke subset"
            )
        if self._tasks is not None:
            return list(self._tasks)
        tasks: list[BenchmarkTask] = []
        for row in self._load_rows():
            task_id = str(row["id"])
            prompt = str(row["prompt"])
            language = str(row.get("language") or "")
            topic = str(row.get("topic") or "")
            tasks.append(
                BenchmarkTask(
                    id=task_id,
                    category=topic or language,
                    question=prompt,
                    functions=[],
                    ground_truth=None,
                    raw=row,
                    turns=[{"query": prompt}],
                    metadata={
                        "officialTaskId": row["id"],
                        "language": language,
                        "topic": topic,
                        "split": split,
                        "answerContract": self.manifest["answerContract"],
                    },
                )
            )
        self._tasks = tasks
        return list(tasks)

    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask:
        workspace.mkdir(parents=True, exist_ok=True)
        return PreparedTask(task=task, workspace=workspace, metadata={})

    def fixture_script(self, task: BenchmarkTask) -> dict[str, Any]:
        return {
            "id": task.id,
            "query": task.question,
            "script": [
                {
                    "type": "final",
                    "content": (
                        "# Fixture research report\n\n"
                        "This is a deterministic DeepResearch Bench delivery "
                        "fixture. [source](https://example.com/source)\n"
                    ),
                }
            ],
            "tools": [],
        }

    def case_for_task(self, task: BenchmarkTask) -> EvalCase:
        budgets = self.manifest["budgets"]
        return EvalCase(
            id=task.id,
            suite="deepresearch-official-full",
            version=1,
            hard_gate=True,
            query=task.question,
            expected=ExpectedSpec(max_steps=int(budgets["maxSteps"])),
            budgets=BudgetSpec(
                max_total_tokens=int(budgets["maxTokens"]),
                max_wall_ms=int(budgets["maxWallMs"]),
                max_tool_calls=int(budgets["maxToolCalls"]),
            ),
            script=self.fixture_script(task)["script"],
            source_path=str(self.manifest_path),
            answer_contract=self.manifest["answerContract"],
            metadata={
                "source": "official",
                "officialTaskId": task.metadata["officialTaskId"],
                "language": task.metadata["language"],
                "topic": task.metadata["topic"],
                "split": task.metadata["split"],
                "limit": self.limit,
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
            metadata={"workspace": str(prepared.workspace) if prepared else None},
        )

    def score(self, run: BenchmarkRun) -> CaseResult:
        article = run.trace.final_text.strip()
        citations = sorted(set(URL_PATTERN.findall(article)))
        article_present = bool(article)
        official_evidence = (
            self._official_results[-1]
            if self._official_results
            else {"status": "unverified", "reason": "official evaluator not run"}
        )
        official_status = (
            "verified"
            if official_evidence.get("status") == "complete"
            else "unverified"
        )
        scores = [
            ScoreRecord(
                case_id=run.task.id,
                scorer="deepresearch.contract",
                name="article_present",
                value=article_present,
                unit="boolean",
                passed=article_present,
                hard_gate=True,
                status="verified",
                evidence={
                    "articleLength": len(article),
                    "finalText": article,
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="deepresearch.contract",
                name="citation_present",
                value=bool(citations),
                unit="boolean",
                passed=None,
                hard_gate=False,
                status="verified",
                evidence={
                    "citationCount": len(citations),
                    "citations": citations[:20],
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="deepresearch.official",
                name="official_evaluation",
                value=(
                    (official_evidence.get("race") or {}).get("Overall Score")
                    if isinstance(official_evidence.get("race"), dict)
                    else None
                ),
                unit="aggregate",
                passed=None,
                hard_gate=True,
                status=official_status,
                evidence={
                    "scope": "aggregate",
                    "result": official_evidence,
                },
            ),
        ]
        return CaseResult(
            case=self.case_for_task(run.task),
            trace=run.trace,
            scores=scores,
            trace_path=run.trace_path,
        )

    def write_submission(
        self,
        runs: list[BenchmarkRun],
        path: Path,
    ) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8", newline="\n") as stream:
            for run in runs:
                row = {
                    "id": run.task.raw["id"],
                    "prompt": run.task.question,
                    "article": run.trace.final_text,
                }
                stream.write(json.dumps(row, ensure_ascii=False) + "\n")
        return path

    def evaluate_official(
        self,
        *,
        raw_data_path: Path,
        output_dir: Path,
        race_model: str | None,
        clean_model: str | None,
        fact_model: str | None,
        base_url: str | None,
        api_key: str | None,
        jina_api_key: str | None,
        max_workers: int = 10,
    ) -> dict[str, Any]:
        evaluator = self.manifest["officialEvaluator"]
        models = evaluator["models"]
        result = evaluate_official(
            source_dir=self.source_dir,
            data_home=self.data_home,
            revision=str(evaluator["revision"]),
            query_sha256=str(evaluator["querySha256"]),
            raw_data_path=raw_data_path,
            target_model=self.target_model,
            output_dir=output_dir,
            race_model=race_model or str(models["race"]),
            clean_model=clean_model or str(models["clean"]),
            fact_model=fact_model or str(models["fact"]),
            base_url=base_url,
            api_key=api_key,
            jina_api_key=jina_api_key,
            max_workers=max_workers,
            limit=self.limit,
        )
        if result.get("status") == "complete":
            effective_race = race_model or str(models["race"])
            effective_clean = clean_model or str(models["clean"])
            effective_fact = fact_model or str(models["fact"])
            official_bases = {
                None,
                "https://api.openai.com/v1",
                "https://openrouter.ai/api/v1",
            }
            official_models = (
                effective_race in {
                    str(models["race"]),
                    f"openai/{models['race']}",
                }
                and effective_clean in {
                    str(models["clean"]),
                    f"openai/{models['clean']}",
                }
                and effective_fact in {
                    str(models["fact"]),
                    f"openai/{models['fact']}",
                }
            )
            if (
                self.limit is not None
                or not official_models
                or base_url not in official_bases
            ):
                result["status"] = "compatibility-subset"
                result["reason"] = (
                    "limited runs, non-official judge models, and custom judge "
                    "endpoints are not official DeepResearch Bench scores"
                )
        self._official_results.append(result)
        return result

    def record_official_result(self, result: dict[str, Any]) -> None:
        self._official_results.append(result)

    def apply_official_results(self, results: list[CaseResult]) -> None:
        if not self._official_results:
            return
        latest = self._official_results[-1]
        status = "verified" if latest.get("status") == "complete" else "unverified"
        for result in results:
            for score in result.scores:
                if score.name != "official_evaluation":
                    continue
                score.status = status
                score.evidence = {
                    "scope": "aggregate",
                    "result": latest,
                }
                if isinstance(latest.get("race"), dict):
                    score.value = latest["race"].get("Overall Score")

    def dataset_hash(self) -> str:
        return _json_hash(
            {
                "name": self.name,
                "version": self.version,
                "rows": self._load_rows(),
                "officialEvaluator": self.manifest["officialEvaluator"],
                "answerContract": self.manifest.get("answerContract"),
            }
        )

    def report_metadata(self) -> dict[str, Any]:
        evaluator = self.manifest["officialEvaluator"]
        split = "official-full" if self.limit is None else "compatibility-subset"
        complete = (
            self.limit is None
            and bool(self._official_results)
            and all(
                item.get("status") == "complete"
                for item in self._official_results
            )
        )
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "datasetUpstream": self.manifest.get("datasetUpstream"),
            "license": self.manifest["license"],
            "split": split,
            "scoreLabel": (
                str(self.manifest["scoreLabel"])
                if complete
                else "submission-contract"
            ),
            "officialEvaluator": True,
            "runnerMode": self.manifest["runnerMode"],
            "revision": evaluator["revision"],
            "selectedTaskIds": [task.id for task in self.tasks("official-full")],
            "taskCount": len(self.tasks("official-full")),
            "limit": self.limit,
            "targetModel": self.target_model,
            "querySha256": self.manifest["dataset"]["querySha256"],
            "datasetHash": self.dataset_hash(),
            "officialEvaluation": (
                self._official_results[-1] if self._official_results else None
            ),
            "officialEvaluations": list(self._official_results),
        }
