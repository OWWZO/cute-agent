from __future__ import annotations

import hashlib
import json
import os
import re
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import yaml

from ...models import (
    BudgetSpec,
    CaseResult,
    EvalCase,
    ExpectedSpec,
    JudgeConfig,
    RunTrace,
    ScoreRecord,
)
from ..longmemeval.judge import judge_longmemeval_answer
from ..base import BenchmarkRun, BenchmarkTask, PreparedTask
from .official_chunking import chunk_text_into_sentences
from .official_metrics import (
    eventqa_recall,
    normalize_answer,
    ruler_recall,
    score_answer,
)
from .official_recsys import (
    load_entity_catalog,
    score_recsys_recommendations,
)
from .official_summarization import score_infbench_summary
from .official_templates import query_for_source

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"
CATEGORY_ORDER = (
    "Accurate_Retrieval",
    "Test_Time_Learning",
    "Long_Range_Understanding",
    "Conflict_Resolution",
)


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


def _safe_token(value: str, limit: int) -> str:
    token = re.sub(r"[^A-Za-z0-9_-]+", "-", value).strip("-")
    return token[:limit] or "task"


def _extract_final_answer(text: str, prefix: str) -> str | None:
    matches = re.findall(
        rf"(?im){re.escape(prefix)}\s*(.+?)\s*$",
        text or "",
    )
    return matches[-1].strip() if matches else None


def _extract_multiline_answer(text: str, prefix: str) -> str | None:
    index = (text or "").rfind(prefix)
    if index < 0:
        return None
    value = text[index + len(prefix) :].strip()
    return value or None


def _sum_optional(values: list[int | None]) -> int | None:
    if not values or any(value is None for value in values):
        return None
    return sum(int(value) for value in values if value is not None)


def _answer_key(value: str) -> str:
    """Compare formatting variants without changing the official scorer."""
    return "".join(normalize_answer(value).split())


def _format_normalized_match(
    prediction: str,
    answers: list[str],
    metric: str,
) -> bool:
    predicted = _answer_key(prediction)
    expected = [_answer_key(answer) for answer in answers]
    if metric == "substring_exact_match":
        return any(value and value in predicted for value in expected)
    return any(value and value == predicted for value in expected)


def _search_diagnostics(tool_invocations: list[dict[str, Any]]) -> dict[str, Any]:
    discover = 0
    zero_hit = 0
    single_hit = 0
    fallback = 0
    scroll = 0
    failed = 0
    for item in tool_invocations:
        if str(item.get("toolName")) != "session_search":
            continue
        if str(item.get("status", "")).upper() != "SUCCESS":
            failed += 1
            continue
        raw = item.get("toolResult")
        try:
            payload = raw if isinstance(raw, dict) else json.loads(str(raw or "{}"))
        except (TypeError, ValueError, json.JSONDecodeError):
            failed += 1
            continue
        mode = str(payload.get("mode") or "")
        if mode == "scroll":
            scroll += 1
            continue
        if mode != "discover":
            continue
        discover += 1
        count = int(payload.get("count") or 0)
        if count == 0:
            zero_hit += 1
        elif count == 1:
            single_hit += 1
        if payload.get("fallback") is True:
            fallback += 1
    return {
        "discoverSearches": discover,
        "scrollSearches": scroll,
        "zeroHitSearches": zero_hit,
        "singleHitSearches": single_hit,
        "fallbackSearches": fallback,
        "failedSearches": failed,
        "zeroHitRate": zero_hit / discover if discover else None,
        "singleHitRate": single_hit / discover if discover else None,
        "fallbackRate": fallback / discover if discover else None,
    }


class MemoryAgentBenchAdapter:
    name = "memoryagentbench"

    def __init__(
        self,
        manifest_path: Path = DEFAULT_MANIFEST,
        *,
        data_home: Path | None = None,
        source_dir: Path | None = None,
        data_paths: dict[str, Path] | None = None,
        judge: JudgeConfig | None = None,
        recsys_catalog_path: Path | None = None,
    ) -> None:
        self.manifest_path = manifest_path.resolve()
        raw = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise TypeError(f"invalid MemoryAgentBench manifest: {self.manifest_path}")
        self.manifest: dict[str, Any] = raw
        self.version = str(raw["version"])
        self.upstream = str(raw["upstream"])
        self.score_labels = {
            str(key): str(value)
            for key, value in (raw.get("scoreLabels") or {}).items()
        }
        self.score_label = self.score_labels.get("official-subset", "official-subset")
        self.revision = str(raw["dataset"]["revision"])
        self.data_home = (
            data_home
            or Path(
                os.environ.get(
                    "EVAL_DATA_HOME",
                    Path.home() / ".cache" / "cute-evals",
                )
            )
        ).resolve()
        self.source_dir = source_dir.resolve() if source_dir else None
        self.data_paths = {
            key: value.resolve()
            for key, value in (data_paths or {}).items()
        }
        self.judge = judge or JudgeConfig()
        self.recsys_catalog_path = (
            recsys_catalog_path.resolve()
            if recsys_catalog_path
            else None
        )
        self._rows: dict[str, list[dict[str, Any]]] = {}
        self._tasks: dict[str, list[BenchmarkTask]] = {}
        self._recsys_catalog: tuple[dict[int, str], list[str]] | None = None
        self._active_split = "official-subset"

    def _dataset_dir(self) -> Path:
        return self.data_home / "memoryagentbench" / self.revision

    def _file_spec(self, category: str) -> dict[str, Any]:
        files = self.manifest["dataset"]["files"]
        spec = files.get(category)
        if not isinstance(spec, dict):
            raise ValueError(f"MemoryAgentBench category is not pinned: {category}")
        return spec

    def dataset_path(self, category: str) -> Path:
        if category in self.data_paths:
            return self.data_paths[category]
        spec = self._file_spec(category)
        name = Path(str(spec["path"])).name
        if self.source_dir is not None:
            for candidate in (
                self.source_dir / name,
                self.source_dir / f"{category}.parquet",
            ):
                if candidate.exists():
                    return candidate
        dataset_dir = self._dataset_dir()
        for candidate in (
            dataset_dir / name,
            dataset_dir / f"{category}.parquet",
        ):
            if candidate.exists():
                return candidate
        target = dataset_dir / name
        target.parent.mkdir(parents=True, exist_ok=True)
        url = (
            "https://huggingface.co/datasets/"
            f"{self.manifest['dataset']['repo']}/resolve/{self.revision}/"
            f"{spec['path']}"
        )
        endpoint = os.environ.get("HF_ENDPOINT")
        if endpoint:
            url = (
                endpoint.rstrip("/")
                + "/datasets/"
                + str(self.manifest["dataset"]["repo"])
                + f"/resolve/{self.revision}/{spec['path']}"
            )
        headers = {"User-Agent": "cute-evals"}
        token = os.environ.get("HF_TOKEN")
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                target.write_bytes(response.read())
        except (urllib.error.URLError, TimeoutError) as exc:
            raise RuntimeError(
                f"failed to download MemoryAgentBench {category}: {exc}"
            ) from exc
        return target

    def recsys_catalog_file(self) -> Path:
        if self.recsys_catalog_path is not None:
            return self.recsys_catalog_path
        spec = (
            (self.manifest["dataset"].get("auxiliaryFiles") or {})
            .get("recsysCatalog")
            or {}
        )
        relative = str(spec.get("path") or "entity2id.json")
        target = self._dataset_dir() / relative
        if target.exists():
            return target
        target.parent.mkdir(parents=True, exist_ok=True)
        url = (
            "https://huggingface.co/datasets/"
            f"{self.manifest['dataset']['repo']}/resolve/{self.revision}/"
            f"{relative}"
        )
        endpoint = os.environ.get("HF_ENDPOINT")
        if endpoint:
            url = (
                endpoint.rstrip("/")
                + "/datasets/"
                + str(self.manifest["dataset"]["repo"])
                + f"/resolve/{self.revision}/{relative}"
            )
        headers = {"User-Agent": "cute-evals"}
        token = os.environ.get("HF_TOKEN")
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=300) as response:
                target.write_bytes(response.read())
        except (urllib.error.URLError, TimeoutError) as exc:
            raise RuntimeError(
                f"failed to download MemoryAgentBench {relative}: {exc}"
            ) from exc
        return target

    def _load_category(self, category: str) -> list[dict[str, Any]]:
        if category in self._rows:
            return self._rows[category]
        path = self.dataset_path(category)
        expected = str(self._file_spec(category)["sha256"]).lower()
        actual = _sha256(path)
        if actual != expected:
            raise ValueError(
                f"MemoryAgentBench {category} hash mismatch: "
                f"expected {expected}, got {actual}"
            )
        if path.suffix.lower() == ".json":
            rows = json.loads(path.read_text(encoding="utf-8"))
        elif path.suffix.lower() == ".parquet":
            try:
                import pyarrow.parquet as pq
            except ImportError as exc:
                raise RuntimeError(
                    "MemoryAgentBench parquet loading requires the "
                    "'memoryagentbench' extra"
                ) from exc
            rows = pq.read_table(path).to_pylist()
        else:
            raise ValueError(f"unsupported MemoryAgentBench data file: {path}")
        if not isinstance(rows, list) or not rows:
            raise ValueError(f"MemoryAgentBench dataset is empty: {path}")
        self._rows[category] = rows
        return rows

    def _source_specs(
        self,
        category: str,
        split: str,
    ) -> list[dict[str, Any]]:
        selection = self.manifest["selection"][split]
        category_spec = selection["categories"][category]
        sources = category_spec.get("sources")
        if isinstance(sources, list) and sources:
            return [dict(item) for item in sources]
        if category_spec.get("source"):
            return [dict(category_spec)]
        raise ValueError(
            f"MemoryAgentBench selection has no sources: {split}/{category}"
        )

    def _selected_contexts(
        self,
        category: str,
        split: str,
    ) -> list[tuple[int, dict[str, Any], dict[str, Any]]]:
        selection = self.manifest["selection"][split]
        limit_value = selection.get("maxContextsPerCategory")
        limit = int(limit_value) if limit_value is not None else None
        selected: list[tuple[int, dict[str, Any], dict[str, Any]]] = []
        for source_spec in self._source_specs(category, split):
            source = str(source_spec["source"])
            rows = [
                (index, row)
                for index, row in enumerate(self._load_category(category))
                if str((row.get("metadata") or {}).get("source") or "")
                == source
            ]
            if not rows:
                raise ValueError(
                    f"MemoryAgentBench source not found: {category}/{source}"
                )
            selected.extend(
                (index, row, source_spec)
                for index, row in (
                    rows[:limit] if limit is not None else rows
                )
            )
        return selected

    def _selected_questions(
        self,
        category: str,
        split: str,
        row: dict[str, Any],
    ) -> tuple[
        list[str],
        list[list[str]],
        list[str],
        list[str],
        list[str],
        list[bool],
    ]:
        selection = self.manifest["selection"][split]
        questions = [str(value) for value in row.get("questions") or []]
        answers = [
            [str(answer) for answer in group]
            if isinstance(group, list)
            else [str(group)]
            for group in row.get("answers") or []
        ]
        metadata = row.get("metadata") or {}
        qa_pair_ids = [
            str(value)
            for value in metadata.get("qa_pair_ids") or []
        ]
        question_ids = [
            str(value)
            for value in metadata.get("question_ids") or []
        ]
        question_types = [
            str(value)
            for value in metadata.get("question_types") or []
        ]
        limit_value = selection.get(
            "questionsPerSource",
            selection.get("questionsPerCategory"),
        )
        limit = int(limit_value) if limit_value is not None else len(questions)
        if not questions or len(questions) < limit or len(answers) < limit:
            raise ValueError(
                f"MemoryAgentBench context has fewer than {limit} questions"
            )
        if not qa_pair_ids:
            qa_pair_ids = [
                f"{metadata.get('source') or category}_q{index}"
                for index in range(len(questions))
            ]
        elif len(qa_pair_ids) < len(questions):
            qa_pair_ids.extend(
                f"{metadata.get('source') or category}_q{index}"
                for index in range(len(qa_pair_ids), len(questions))
            )
        if not question_ids:
            question_ids = list(qa_pair_ids)
        if not question_types:
            question_types = ["unknown"] * len(questions)
        if len(question_ids) < limit or len(question_types) < limit:
            raise ValueError("MemoryAgentBench question metadata is incomplete")
        return (
            questions[:limit],
            answers[:limit],
            qa_pair_ids[:limit],
            question_ids[:limit],
            question_types[:limit],
            [
                question_id.endswith("_abs")
                for question_id in question_ids[:limit]
            ],
        )

    def tasks(self, split: str) -> list[BenchmarkTask]:
        self._active_split = split
        if split in self._tasks:
            return list(self._tasks[split])
        if split not in (self.manifest.get("selection") or {}):
            raise ValueError(f"unsupported MemoryAgentBench split: {split}")

        tasks: list[BenchmarkTask] = []
        for category in CATEGORY_ORDER:
            for row_index, row, source_spec in self._selected_contexts(
                category,
                split,
            ):
                (
                    questions,
                    answers,
                    qa_pair_ids,
                    question_ids,
                    question_types,
                    abstentions,
                ) = self._selected_questions(category, split, row)
                source = str(source_spec["source"])
                context = str(row.get("context") or "")
                if not context:
                    raise ValueError(
                        f"MemoryAgentBench context is empty: {category}/{source}"
                    )
                task_id = _safe_token(
                    f"{category}-{source}-ctx{row_index:03d}",
                    72,
                )
                tasks.append(
                    BenchmarkTask(
                        id=task_id,
                        category=category,
                        question=questions[0],
                        functions=[],
                        ground_truth=[
                            {
                                "qaPairId": qa_pair_ids[index],
                                "answers": answers[index],
                            }
                            for index in range(len(questions))
                        ],
                        raw=row,
                        turns=[
                            {
                                "query": query_for_source(
                                    source,
                                    question,
                                    str(self.manifest["queryTemplateStyle"]),
                                ),
                                "question": question,
                                "qaPairId": qa_pair_ids[index],
                                "questionId": question_ids[index],
                                "questionType": question_types[index],
                            }
                            for index, question in enumerate(questions)
                        ],
                        metadata={
                            "category": category,
                            "source": source,
                            "scorer": str(source_spec.get("scorer") or "text"),
                            "metric": str(source_spec.get("metric") or "exact_match"),
                            "split": split,
                            "questions": questions,
                            "answers": answers,
                            "qaPairIds": qa_pair_ids,
                            "questionIds": question_ids,
                            "questionTypes": question_types,
                            "abstentions": abstentions,
                            "questionCount": len(questions),
                            "contextSha256": hashlib.sha256(
                                context.encode("utf-8")
                            ).hexdigest(),
                        },
                    )
                )
        expected_contexts = (self.manifest.get("officialFull") or {}).get(
            "expectedContexts"
        )
        expected_questions = (self.manifest.get("officialFull") or {}).get(
            "expectedQuestions"
        )
        if split == "official-subset" and len(tasks) != 4:
            raise ValueError(
                f"MemoryAgentBench official-subset expected 4 contexts, got {len(tasks)}"
            )
        if (
            split == "official-full"
            and expected_contexts is not None
            and len(tasks) != int(expected_contexts)
        ):
            raise ValueError(
                "MemoryAgentBench official-full context count mismatch: "
                f"expected {expected_contexts}, got {len(tasks)}"
            )
        task_question_count = sum(
            int(task.metadata["questionCount"])
            for task in tasks
        )
        if (
            split == "official-full"
            and expected_questions is not None
            and task_question_count != int(expected_questions)
        ):
            raise ValueError(
                "MemoryAgentBench official-full question count mismatch: "
                f"expected {expected_questions}, got {task_question_count}"
            )
        self._tasks[split] = tasks
        return list(tasks)

    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask:
        workspace.mkdir(parents=True, exist_ok=True)
        chunking = self.manifest["chunking"]
        chunks = chunk_text_into_sentences(
            str(task.raw["context"]),
            model_name=str(chunking["model"]),
            chunk_size=int(chunking["size"]),
        )
        if not chunks:
            raise ValueError(f"MemoryAgentBench produced no chunks: {task.id}")
        return PreparedTask(
            task=task,
            workspace=workspace,
            metadata={
                "chunks": chunks,
                "chunkCount": len(chunks),
                "seedMode": self.manifest["seedMode"],
            },
        )

    def case_for_task(
        self,
        task: BenchmarkTask,
        *,
        namespace: str,
        prepared: PreparedTask | None = None,
    ) -> EvalCase:
        budgets = self.manifest["budgets"]
        task_token = _safe_token(task.id, 28)
        namespace_token = _safe_token(namespace, 24)
        user_id = f"eval-mab-{task_token}-{namespace_token}"[:64]
        chunks = (
            list((prepared.metadata if prepared else {}).get("chunks") or [])
            or chunk_text_into_sentences(
                str(task.raw["context"]),
                model_name=str(self.manifest["chunking"]["model"]),
                chunk_size=int(self.manifest["chunking"]["size"]),
            )
        )
        seed_config = self.manifest["seedTimestamp"]
        start = datetime.strptime(
            str(seed_config["start"]),
            "%Y-%m-%d %H:%M:%S",
        )
        step = timedelta(seconds=int(seed_config["stepSeconds"]))
        sessions = []
        for index, chunk in enumerate(chunks):
            session_id = (
                f"eval-mab-{task_token}-{namespace_token}-s{index + 1:04d}"
            )[:128]
            sessions.append(
                {
                    "sessionId": session_id,
                    "timestamp": (start + step * index).strftime(
                        "%Y-%m-%d %H:%M:%S"
                    ),
                    "title": (
                        f"MemoryAgentBench {task.id} chunk #{index + 1}"
                    ),
                    "turns": [
                        {"role": "user", "content": chunk},
                        {
                            "role": "assistant",
                            "content": "I have memorized this context chunk.",
                        },
                    ],
                }
            )
        scorer = str(task.metadata.get("scorer") or "text")
        contract_mode = (
            self.manifest.get("answerContractByScorer") or {}
        ).get(scorer, "concise")
        contract = (
            self.manifest["answerContract"]
            if contract_mode == "concise"
            else dict(contract_mode)
        )
        return EvalCase(
            id=task.id,
            suite=f"memoryagentbench-{self._active_split}",
            version=1,
            hard_gate=True,
            query=str(task.turns[0]["query"]),
            expected=ExpectedSpec(max_steps=int(budgets["maxSteps"])),
            budgets=BudgetSpec(
                max_total_tokens=int(budgets["maxTokens"]),
                max_wall_ms=int(budgets["maxWallMs"]),
                max_tool_calls=int(budgets["maxToolCalls"]),
                soft_limit_percent=float(budgets.get("softLimitPercent", 0.65)),
            ),
            script=[{"type": "final", "content": "FINAL_ANSWER: fixture"}],
            source_path=str(self.manifest_path),
            tools=[],
            answer_contract=contract,
            metadata={
                "source": "official",
                "category": task.category,
                "questionCount": int(task.metadata["questionCount"]),
                "qaPairIds": task.metadata["qaPairIds"],
                "seedMode": self.manifest["seedMode"],
                "scorer": scorer,
                "metric": task.metadata["metric"],
            },
            user_id=user_id,
            memory_seed={
                "userId": user_id,
                "namespace": f"eval-mab-{task_token}-{namespace_token}",
                "sessions": sessions,
            },
        )

    def collect(
        self,
        task: BenchmarkTask,
        trace: RunTrace,
        trace_path: str,
        *,
        prepared: PreparedTask | None = None,
        turn_results: list[dict[str, Any]] | None = None,
        traces: list[RunTrace] | None = None,
    ) -> BenchmarkRun:
        turns = list(turn_results or [])
        trace_items = list(traces or [trace])
        if turns and len(trace_items) != len(turns):
            raise ValueError(
                f"MemoryAgentBench trace/turn count mismatch for {task.id}: "
                f"{len(trace_items)} != {len(turns)}"
            )
        aggregate = trace
        if turns:
            expected_turns = int(task.metadata["questionCount"])
            completed = (
                len(turns) == expected_turns
                and all(
                    str(turn.get("status") or "").upper() == "SUCCESS"
                    for turn in turns
                )
            )
            final_text = str(turns[-1].get("finalText") or "") if turns else ""
            aggregate = RunTrace(
                case_id=task.id,
                request_id=str(turns[0].get("requestId") or task.id),
                status="SUCCESS" if completed else "FAILED",
                final_text=final_text,
                total_tokens=_sum_optional(
                    [item.total_tokens for item in trace_items]
                ),
                duration_ms=_sum_optional(
                    [item.duration_ms for item in trace_items]
                ),
                run_uid=trace_items[-1].run_uid if trace_items else None,
                user_id_hash=(
                    trace_items[-1].user_id_hash if trace_items else None
                ),
                llm_invocations=[
                    invocation
                    for item in trace_items
                    for invocation in item.llm_invocations
                ],
                tool_invocations=[
                    invocation
                    for item in trace_items
                    for invocation in item.tool_invocations
                ],
                artifacts=[
                    artifact
                    for item in trace_items
                    for artifact in item.artifacts
                ],
                raw={
                    "run": {
                        "caseId": task.id,
                        "requestId": str(turns[0].get("requestId") or task.id),
                        "status": "SUCCESS" if completed else "FAILED",
                        "finalSummaryText": final_text,
                        "totalTokens": _sum_optional(
                            [item.total_tokens for item in trace_items]
                        ),
                        "durationMs": _sum_optional(
                            [item.duration_ms for item in trace_items]
                        ),
                    },
                    "turnResults": turns,
                    "tracePaths": [item.raw.get("run") for item in trace_items],
                },
                conversation=[
                    message
                    for item in trace_items
                    for message in item.conversation
                ],
                conversation_source=trace_items[-1].conversation_source
                if trace_items
                else None,
                context_events=[
                    event
                    for item in trace_items
                    for event in item.context_events
                ],
                memory_events=[
                    event
                    for item in trace_items
                    for event in item.memory_events
                ],
                environment_events=[
                    event
                    for item in trace_items
                    for event in item.environment_events
                ],
                environment_source=trace_items[-1].environment_source
                if trace_items
                else None,
                environment_snapshots=[
                    snapshot
                    for item in trace_items
                    for snapshot in item.environment_snapshots
                ],
                snapshot_source=trace_items[-1].snapshot_source
                if trace_items
                else None,
            )
        return BenchmarkRun(
            task=task,
            trace=aggregate,
            trace_path=trace_path,
            calls=[],
            conversation=aggregate.conversation,
            environment_events=aggregate.environment_events,
            environment_snapshots=aggregate.environment_snapshots,
            metadata={
                "prepared": prepared.metadata if prepared else {},
                "turnResults": turns,
                "traceCount": len(trace_items),
            },
        )

    def _recsys_catalog_data(self) -> tuple[dict[int, str], list[str]]:
        if self._recsys_catalog is not None:
            return self._recsys_catalog
        path = self.recsys_catalog_file()
        spec = (
            (self.manifest["dataset"].get("auxiliaryFiles") or {})
            .get("recsysCatalog")
            or {}
        )
        expected = str(spec.get("sha256") or "").lower()
        actual = _sha256(path)
        if expected and actual != expected:
            raise ValueError(
                "MemoryAgentBench Recsys catalog hash mismatch: "
                f"expected {expected}, got {actual}"
            )
        self._recsys_catalog = load_entity_catalog(path)
        return self._recsys_catalog

    def _evaluate_question(
        self,
        task: BenchmarkTask,
        index: int,
        final_answer: str | None,
    ) -> dict[str, Any]:
        scorer = str(task.metadata["scorer"])
        metric = str(task.metadata["metric"])
        answers = list(task.metadata["answers"][index])
        base = {
            "qaPairId": task.metadata["qaPairIds"][index],
            "questionId": task.metadata["questionIds"][index],
            "questionType": task.metadata["questionTypes"][index],
            "expectedAnswers": answers,
            "finalAnswer": final_answer,
        }
        if final_answer is None:
            return {
                **base,
                "name": (
                    "official_question_accuracy"
                    if scorer in {"text", "longmemeval_judge"}
                    else (
                        "official_question_ruler_recall"
                        if scorer == "ruler_recall"
                        else (
                            "official_question_infbench_f1"
                            if scorer == "summary_judge"
                            else "official_question_recall_at_5"
                        )
                    )
                ),
                "value": (
                    False
                    if scorer in {"text", "longmemeval_judge"}
                    else 0.0
                ),
                "passed": False if scorer in {"text", "ruler_recall"} else None,
                "status": "verified",
                "details": {**base, "error": "missing FINAL_ANSWER"},
            }

        if scorer == "text":
            passed = score_answer(metric, final_answer, answers)
            details = {**base, "metric": metric, "passed": passed}
            if task.metadata["source"].startswith("eventqa_"):
                details["eventqaRecall"] = eventqa_recall(
                    final_answer,
                    answers,
                )
            return {
                **base,
                "name": "official_question_accuracy",
                "value": passed,
                "passed": passed,
                "status": "verified",
                "details": details,
            }

        if scorer == "ruler_recall":
            value = ruler_recall(final_answer, answers)
            return {
                **base,
                "name": "official_question_ruler_recall",
                "value": value,
                "passed": value == 1.0,
                "status": "verified",
                "details": {**base, "metric": "ruler_recall", "value": value},
            }

        if scorer == "longmemeval_judge":
            answer = answers[0] if len(answers) == 1 else " | ".join(answers)
            try:
                result = judge_longmemeval_answer(
                    judge=self.judge,
                    task=str(task.metadata["questionTypes"][index]),
                    question=str(task.metadata["questions"][index]),
                    answer=answer,
                    response=final_answer,
                    abstention=bool(task.metadata["abstentions"][index]),
                )
            except Exception as exc:  # noqa: BLE001 - judge boundary
                return {
                    **base,
                    "name": "official_question_accuracy",
                    "value": None,
                    "passed": None,
                    "status": "unverified",
                    "details": {
                        **base,
                        "judgeModel": self.judge.model,
                        "judgeError": str(exc),
                    },
                }
            return {
                **base,
                "name": "official_question_accuracy",
                "value": bool(result["label"]),
                "passed": bool(result["label"]),
                "status": "verified",
                "details": {
                    **base,
                    "judgeModel": self.judge.model,
                    "judgeRawLabel": result["rawLabel"],
                    "judgePrompt": result["prompt"],
                },
            }

        if scorer == "summary_judge":
            keypoints = [
                str(value)
                for value in (
                    (task.raw.get("metadata") or {}).get("keypoints") or []
                )
            ]
            try:
                result = score_infbench_summary(
                    judge=self.judge,
                    summary=final_answer,
                    expert_summary=answers[0],
                    keypoints=keypoints,
                )
            except Exception as exc:  # noqa: BLE001 - judge boundary
                return {
                    **base,
                    "name": "official_question_infbench_f1",
                    "value": None,
                    "passed": None,
                    "status": "unverified",
                    "details": {
                        **base,
                        "judgeModel": self.judge.model,
                        "judgeError": str(exc),
                    },
                }
            return {
                **base,
                "name": "official_question_infbench_f1",
                "value": result["f1"],
                "passed": None,
                "status": "verified",
                "details": {
                    **base,
                    "judgeModel": self.judge.model,
                    **result,
                },
            }

        if scorer == "recsys_recall":
            try:
                id_to_name, candidates = self._recsys_catalog_data()
                result = score_recsys_recommendations(
                    prediction=final_answer,
                    ground_truth_ids=answers,
                    id_to_name=id_to_name,
                    candidate_movies=candidates,
                )
            except Exception as exc:  # noqa: BLE001 - scorer boundary
                return {
                    **base,
                    "name": "official_question_recall_at_5",
                    "value": None,
                    "passed": None,
                    "status": "unverified",
                    "details": {**base, "scorerError": str(exc)},
                }
            return {
                **base,
                "name": "official_question_recall_at_5",
                "value": result["recall@5"],
                "passed": None,
                "status": "verified",
                "details": {**base, **result},
            }

        raise ValueError(f"unsupported MemoryAgentBench scorer: {scorer}")

    def score(self, run: BenchmarkRun) -> CaseResult:
        prepared_metadata = dict(run.metadata.get("prepared") or {})
        case = self.case_for_task(
            run.task,
            namespace="report",
            prepared=PreparedTask(
                task=run.task,
                workspace=Path("."),
                metadata=prepared_metadata,
            )
            if prepared_metadata.get("chunks")
            else None,
        )
        scorer = str(run.task.metadata["scorer"])
        contract_mode = (
            self.manifest.get("answerContractByScorer") or {}
        ).get(scorer, "concise")
        contract = (
            self.manifest["answerContract"]
            if contract_mode == "concise"
            else dict(contract_mode)
        )
        prefix = str(contract["linePrefix"])
        source = str(run.task.metadata["source"])
        metric_name = str(run.task.metadata["metric"])
        questions = list(run.task.metadata["questions"])
        turns = list(run.metadata.get("turnResults") or [])
        evaluations: list[dict[str, Any]] = []
        question_scores: list[ScoreRecord] = []
        answered = 0

        for index in range(len(questions)):
            turn = turns[index] if index < len(turns) else {}
            raw_answer = str(turn.get("finalText") or "")
            final_answer = (
                _extract_multiline_answer(raw_answer, prefix)
                if contract.get("multiline")
                else _extract_final_answer(raw_answer, prefix)
            )
            if final_answer is not None:
                answered += 1
            evaluation = self._evaluate_question(
                run.task,
                index,
                final_answer,
            )
            evaluation["details"]["rawAnswer"] = raw_answer
            evaluations.append(evaluation)
            question_scores.append(ScoreRecord(
                case_id=run.task.id,
                scorer=f"memoryagentbench.official-{scorer}",
                name=str(evaluation["name"]),
                value=evaluation["value"],
                unit=(
                    "boolean"
                    if isinstance(evaluation["value"], bool)
                    else "ratio"
                ),
                passed=evaluation["passed"],
                hard_gate=False,
                status=str(evaluation["status"]),
                evidence=evaluation["details"],
            ))

        verified_values = [
            item["value"]
            for item in evaluations
            if item["status"] == "verified" and item["value"] is not None
        ]
        unverified = any(
            item["status"] == "unverified"
            for item in evaluations
        )
        primary_name = {
            "text": "official_accuracy",
            "longmemeval_judge": "official_accuracy",
            "ruler_recall": "official_ruler_recall",
            "summary_judge": "official_infbench_f1",
            "recsys_recall": "official_recall_at_5",
        }[scorer]
        primary_value = (
            sum(float(value) for value in verified_values) / len(verified_values)
            if verified_values
            else None
        )
        primary_passed: bool | None
        if scorer in {"text", "longmemeval_judge", "ruler_recall"}:
            primary_passed = (
                None
                if unverified
                else bool(evaluations) and all(
                    bool(item["passed"])
                    for item in evaluations
                )
            )
        else:
            primary_passed = None
        expected_tokens = (
            case.budgets.max_total_tokens * len(questions)
            if case.budgets.max_total_tokens is not None
            else None
        )
        expected_wall_ms = (
            case.budgets.max_wall_ms * len(questions)
            if case.budgets.max_wall_ms is not None
            else None
        )
        scores = [
            ScoreRecord(
                case_id=run.task.id,
                scorer=f"memoryagentbench.official-{scorer}",
                name=primary_name,
                value=primary_value,
                unit="ratio",
                passed=primary_passed,
                hard_gate=True,
                status="unverified" if unverified else "verified",
                evidence={
                    "questionCount": len(questions),
                    "metric": metric_name,
                    "category": run.task.category,
                    "source": source,
                    "scorer": scorer,
                    "judgeModel": self.judge.model,
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="memoryagentbench.contract",
                name="answer_contract",
                value=answered,
                unit="count",
                passed=answered == len(questions),
                hard_gate=True,
                status="verified",
                evidence={
                    "linePrefix": prefix,
                    "multiline": bool(contract.get("multiline")),
                    "expected": len(questions),
                    "answered": answered,
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="memoryagentbench.metrics",
                name="session_search_calls",
                value=sum(
                    1
                    for item in run.trace.tool_invocations
                    if str(item.get("toolName")) == "session_search"
                ),
                unit="count",
                passed=None,
                hard_gate=False,
                status="verified",
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="memoryagentbench.metrics",
                name="token_budget",
                value=run.trace.total_tokens,
                unit="tokens",
                passed=(
                    expected_tokens is None
                    or run.trace.total_tokens is None
                    or run.trace.total_tokens <= expected_tokens
                ),
                hard_gate=True,
                status=(
                    "verified"
                    if run.trace.total_tokens is not None
                    else "unverified"
                ),
                evidence={"maxTotalTokens": expected_tokens},
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="memoryagentbench.metrics",
                name="wall_time",
                value=run.trace.duration_ms,
                unit="ms",
                passed=(
                    expected_wall_ms is None
                    or run.trace.duration_ms is None
                    or run.trace.duration_ms <= expected_wall_ms
                ),
                hard_gate=True,
                status=(
                    "verified"
                    if run.trace.duration_ms is not None
                    else "unverified"
                ),
                evidence={"maxWallMs": expected_wall_ms},
            ),
        ]
        search_diagnostics = _search_diagnostics(run.trace.tool_invocations)
        format_checked = 0
        format_correct = 0
        for evaluation in evaluations:
            details = evaluation.get("details") or {}
            if scorer != "text":
                continue
            format_checked += 1
            if _format_normalized_match(
                str(details.get("finalAnswer") or ""),
                [str(value) for value in (details.get("expectedAnswers") or [])],
                str(details.get("metric") or metric_name),
            ):
                format_correct += 1
        scores.extend([
            ScoreRecord(
                case_id=run.task.id,
                scorer="memoryagentbench.diagnostic",
                name="diagnostic_format_normalized_accuracy",
                value=(
                    format_correct / format_checked
                    if format_checked
                    else None
                ),
                unit="ratio",
                passed=None,
                hard_gate=False,
                status=(
                    "verified"
                    if format_checked
                    else "unverified"
                ),
                evidence={
                    "correct": format_correct,
                    "checked": format_checked,
                    "note": (
                        "Advisory only: ignores punctuation and whitespace; "
                        "official exact match is unchanged."
                    ),
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="memoryagentbench.diagnostic",
                name="diagnostic_session_search_zero_hit_rate",
                value=search_diagnostics["zeroHitRate"],
                unit="ratio",
                passed=None,
                hard_gate=False,
                status=(
                    "verified"
                    if search_diagnostics["discoverSearches"]
                    else "unverified"
                ),
                evidence=search_diagnostics,
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="memoryagentbench.diagnostic",
                name="diagnostic_session_search_single_hit_rate",
                value=search_diagnostics["singleHitRate"],
                unit="ratio",
                passed=None,
                hard_gate=False,
                status=(
                    "verified"
                    if search_diagnostics["discoverSearches"]
                    else "unverified"
                ),
                evidence=search_diagnostics,
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="memoryagentbench.diagnostic",
                name="diagnostic_tokens_per_question",
                value=(
                    run.trace.total_tokens / len(questions)
                    if run.trace.total_tokens is not None and questions
                    else None
                ),
                unit="tokens",
                passed=None,
                hard_gate=False,
                status=(
                    "verified"
                    if run.trace.total_tokens is not None and questions
                    else "unverified"
                ),
                evidence={
                    "totalTokens": run.trace.total_tokens,
                    "questionCount": len(questions),
                },
            ),
        ])
        if scorer == "recsys_recall":
            for cutoff in (1, 10):
                values = [
                    float(item["details"][f"recall@{cutoff}"])
                    for item in evaluations
                    if item["status"] == "verified"
                    and f"recall@{cutoff}" in item["details"]
                ]
                scores.append(ScoreRecord(
                    case_id=run.task.id,
                    scorer="memoryagentbench.official-recsys",
                    name=f"official_recall_at_{cutoff}",
                    value=(
                        sum(values) / len(values)
                        if values
                        else None
                    ),
                    unit="ratio",
                    passed=None,
                    hard_gate=False,
                    status="unverified" if unverified else "verified",
                    evidence={"questionCount": len(questions)},
                ))
        scores.extend(question_scores if questions else [])
        return CaseResult(
            case=case,
            trace=run.trace,
            scores=scores,
            trace_path=run.trace_path,
        )

    def fixture_script(self, task: BenchmarkTask) -> dict[str, Any]:
        return {
            "id": task.id,
            "query": str(task.turns[0]["query"]),
            "script": [{"type": "final", "content": "FINAL_ANSWER: fixture"}],
            "tools": [],
        }

    def dataset_hash(self) -> str:
        return _json_hash(
            {
                "name": self.name,
                "version": self.version,
                "split": self._active_split,
                "tasks": [
                    {
                        "id": task.id,
                        "source": task.metadata["source"],
                        "scorer": task.metadata["scorer"],
                        "metric": task.metadata["metric"],
                        "qaPairIds": task.metadata["qaPairIds"],
                        "contextSha256": task.metadata["contextSha256"],
                    }
                    for task in self.tasks(self._active_split)
                ],
            }
        )

    def report_metadata(self) -> dict[str, Any]:
        tasks = self.tasks(self._active_split)
        question_count = sum(
            int(task.metadata["questionCount"])
            for task in tasks
        )
        official_judge_models = set(
            (self.manifest.get("officialFull") or {}).get(
                "officialJudgeModels"
            )
            or []
        )
        judge_compatibility = (
            "official-model"
            if self.judge.model in official_judge_models
            else "compatible-nonofficial-model"
        )
        score_label = (
            "compatibility-full"
            if (
                self._active_split == "official-full"
                and judge_compatibility != "official-model"
            )
            else self.score_labels.get(
                self._active_split,
                self._active_split,
            )
        )
        metrics = sorted({
            str(task.metadata["metric"])
            for task in tasks
        })
        scorers = sorted({
            str(task.metadata["scorer"])
            for task in tasks
        })
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "license": self.manifest["license"],
            "split": self._active_split,
            "scoreLabel": score_label,
            "datasetHash": self.dataset_hash(),
            "caseCount": len(tasks),
            "questionCount": question_count,
            "categories": [task.category for task in tasks],
            "officialEvaluator": True,
            "evaluatorCompatibility": (
                self.manifest["officialEvaluator"]["compatibility"]
            ),
            "officialEvaluation": {
                "status": "available",
                "metrics": metrics,
                "scorers": scorers,
                "judgeModel": self.judge.model,
                "judgeCompatibility": judge_compatibility,
            },
        }
