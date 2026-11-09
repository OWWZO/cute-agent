from __future__ import annotations

import hashlib
import json
import os
import re
import urllib.error
import urllib.request
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
from ..base import BenchmarkRun, BenchmarkTask, PreparedTask
from .judge import judge_longmemeval_answer

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"
OFFICIAL_JUDGE_MODELS = {
    "gpt-4o",
    "gpt-4o-2024-08-06",
    "gpt-4o-mini",
    "gpt-4o-mini-2024-07-18",
    "llama-3.1-70b-instruct",
}


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


def _extract_final_answer(text: str, prefix: str = "FINAL_ANSWER:") -> str | None:
    # Some models concatenate the artifact marker and the answer
    # (e.g. "$$$ noneFINAL_ANSWER: ..."), so match the contract anywhere,
    # not only at the beginning of a line.
    pattern = re.compile(rf"(?im){re.escape(prefix)}\s*(.+?)\s*$")
    matches = pattern.findall(text or "")
    if not matches:
        return None
    return matches[-1].strip()


class LongMemEvalAdapter:
    name = "longmemeval"

    def __init__(
        self,
        manifest_path: Path = DEFAULT_MANIFEST,
        *,
        data_home: Path | None = None,
        data_path: Path | None = None,
        dataset_file: str | None = None,
        judge: JudgeConfig | None = None,
    ) -> None:
        self.manifest_path = manifest_path.resolve()
        raw = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise TypeError(f"invalid LongMemEval manifest: {self.manifest_path}")
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
        self.revision = str(raw["dataset"]["revision"])
        self.dataset_file = dataset_file or str(raw["dataset"]["defaultFile"])
        self.data_path = data_path.resolve() if data_path else None
        self.judge = judge or JudgeConfig()
        self._rows: dict[str, list[dict[str, Any]]] = {}
        self._tasks: dict[str, list[BenchmarkTask]] = {}
        self._active_split = "official-subset"

    def _dataset_dir(self) -> Path:
        return self.data_home / "longmemeval" / self.revision

    def _file_spec(self) -> dict[str, Any]:
        files = self.manifest["dataset"].get("files") or {}
        spec = files.get(self.dataset_file)
        if not isinstance(spec, dict):
            raise ValueError(
                f"LongMemEval file is not pinned in manifest: {self.dataset_file}"
            )
        return spec

    def dataset_path(self) -> Path:
        if self.data_path is not None:
            return self.data_path
        target = self._dataset_dir() / self.dataset_file
        if target.exists():
            return target
        target.parent.mkdir(parents=True, exist_ok=True)
        url = (
            "https://huggingface.co/datasets/"
            f"{self.manifest['dataset']['repo']}/resolve/{self.revision}/"
            f"{self.dataset_file}"
        )
        endpoint = os.environ.get("HF_ENDPOINT")
        if endpoint:
            url = (
                endpoint.rstrip("/")
                + "/datasets/"
                + str(self.manifest["dataset"]["repo"])
                + f"/resolve/{self.revision}/{self.dataset_file}"
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
                f"failed to download LongMemEval file {self.dataset_file}: {exc}"
            ) from exc
        return target

    def _load_rows(self, split: str) -> list[dict[str, Any]]:
        if split in self._rows:
            return list(self._rows[split])
        path = self.dataset_path()
        expected = str(self._file_spec()["sha256"]).lower()
        actual = _sha256(path)
        if actual != expected:
            raise ValueError(
                f"LongMemEval dataset hash mismatch: expected {expected}, got {actual}"
            )
        rows = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(rows, list) or not rows:
            raise ValueError(f"LongMemEval dataset is empty: {path}")
        selected = self._select_rows(rows, split)
        self._rows[split] = selected
        return list(selected)

    def _select_rows(
        self,
        rows: list[dict[str, Any]],
        split: str,
    ) -> list[dict[str, Any]]:
        if split not in {"official-subset", "official-full"}:
            raise ValueError(f"unsupported LongMemEval split: {split}")
        ordered = sorted(rows, key=lambda row: str(row.get("question_id") or ""))
        if split == "official-full":
            return ordered
        per_type = int(self.manifest["dataset"].get("subsetPerType") or 2)
        abs_limit = int(self.manifest["dataset"].get("subsetAbstention") or 2)
        selected: list[dict[str, Any]] = []
        by_type: dict[str, list[dict[str, Any]]] = {}
        abstention: list[dict[str, Any]] = []
        for row in ordered:
            question_id = str(row.get("question_id") or "")
            if not question_id:
                raise ValueError("LongMemEval row is missing question_id")
            if question_id.endswith("_abs"):
                abstention.append(row)
                continue
            by_type.setdefault(str(row.get("question_type")), []).append(row)
        for question_type in sorted(by_type):
            selected.extend(by_type[question_type][:per_type])
        selected.extend(abstention[:abs_limit])
        if not selected:
            raise ValueError("LongMemEval selection produced no tasks")
        return sorted(selected, key=lambda row: str(row["question_id"]))

    def tasks(self, split: str) -> list[BenchmarkTask]:
        self._active_split = split
        if split in self._tasks:
            return list(self._tasks[split])
        tasks: list[BenchmarkTask] = []
        for row in self._load_rows(split):
            question_id = str(row["question_id"])
            tasks.append(
                BenchmarkTask(
                    id=question_id,
                    category=str(row["question_type"]),
                    question=str(row["question"]),
                    functions=[],
                    ground_truth=[{"answer": str(row["answer"])}],
                    raw=row,
                    turns=[{"query": str(row["question"])}],
                    metadata={
                        "questionType": str(row["question_type"]),
                        "answer": str(row["answer"]),
                        "questionDate": str(row.get("question_date") or ""),
                        "abstention": question_id.endswith("_abs"),
                        "split": split,
                        "answerContract": self.manifest["answerContract"],
                    },
                )
            )
        self._tasks[split] = tasks
        return list(tasks)

    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask:
        workspace.mkdir(parents=True, exist_ok=True)
        return PreparedTask(task=task, workspace=workspace, metadata={})

    def fixture_script(self, task: BenchmarkTask) -> dict[str, Any]:
        return {
            "id": task.id,
            "query": self.query_for_task(task),
            "script": [{"type": "final", "content": "FINAL_ANSWER: fixture"}],
            "tools": [],
        }

    def query_for_task(self, task: BenchmarkTask) -> str:
        abstention = bool(task.metadata.get("abstention"))
        retrieval = (
            "Use a short single-word discovery query for the exact role named in "
            "the question, then read at most one hit. Do not browse all sessions, "
            "search with fewer keywords, or search for alternative titles."
            if abstention
            else (
                "Use one short plain keyword query (2-4 terms), not long sentences "
                "or quoted phrases; if a query returns no hits, retry with fewer "
                "keywords. For questions about how many, how much, or how many "
                "times, call browse once because there may be many stored sessions "
                "and discovery returns only one hit per session."
            )
        )
        return (
            "Answer this LongMemEval question from the user's prior conversations. "
            "Use session_search to retrieve relevant stored sessions before answering. "
            + (
                "Call browse exactly once as the first and only discovery step, "
                "then read each session returned by browse in the same tool batch. "
                "Do not issue keyword discovery queries."
                if str(task.metadata.get("questionType")) == "multi-session"
                else retrieval
            )
            + " Read or "
            "scroll only around relevant hits, do not repeat equivalent searches, and "
            "read or scroll a session at most once unless its result says truncated. "
            "Use role_filter=user for facts the user stated (purchases, quantities, "
            "dates, roles, and preferences); use role_filter=user,assistant only when "
            "the question asks what the assistant said. A broad query must include "
            "all stored sessions that discuss the question's topic; after discovery, "
            "read each candidate session returned by browse. Do not narrow a "
            "discovery query by month, date, or guessed wording, and do not repeat "
            "an equivalent search inside a session. Count completed events from "
            "explicit dated statements in the retrieved user messages; do not count "
            "scheduled future events, planned appointments, tests, therapy, or "
            "mentions without a completed date. Stop and answer once the history "
            "contains enough evidence; do not invent absent facts, and say the answer "
            "is unavailable when it is insufficient. For a derived value such as "
            "per-unit price, rate, or difference, search for both the total and the "
            "count or base; once both are present, calculate immediately and do not "
            "keep searching for synonyms.\n\n"
            "For preference or recommendation questions, use the user's stated "
            "preferences to recommend; retrieve at most one relevant session, then "
            "answer from it even if the exact recommendation is not already present. "
            "For factual questions about a named role, person, product, or entity, use "
            "only evidence for the exact name. A different title or entity is not a "
            "match, even if some words overlap. If the exact title is absent from the "
            "first relevant session, stop immediately: answer unavailable and do not "
            "search alternative titles or provide its number or fact."
            + (
                "\n\nABSTENTION RULE: the question names a role. If the exact role "
                "does not appear in the retrieved user statements, the answer is "
                "unavailable. Never transfer a number or fact from a similar role, "
                "never infer that a similar role is the same role, and never put a "
                "number in FINAL_ANSWER for this case."
                if abstention
                else ""
            )
            + "\n\n"
            f"Question date: {task.metadata.get('questionDate') or 'unknown'}\n"
            f"Question: {task.question}\n\n"
            "Final evidence check: if the question names a role or title, confirm that "
            "exact title appears in the retrieved text; otherwise answer unavailable."
        )

    def case_for_task(
        self,
        task: BenchmarkTask,
        *,
        namespace: str,
    ) -> EvalCase:
        budgets = self.manifest["budgets"]
        split = str(task.metadata.get("split") or self._active_split)
        task_token = _safe_token(task.id, 24)
        namespace_token = _safe_token(namespace, 28)
        user_id = f"eval-lme-{task_token}-{namespace_token}"[:64]
        sessions = []
        for index, turns in enumerate(task.raw.get("haystack_sessions") or []):
            session_id = (
                f"eval-lme-{task_token}-{namespace_token}-s{index + 1:03d}"
            )[:128]
            dates = task.raw.get("haystack_dates") or []
            timestamp = (
                str(dates[index])
                if index < len(dates)
                else "2000-01-01 00:00:00"
            )
            sessions.append(
                {
                    "sessionId": session_id,
                    "timestamp": timestamp,
                    "title": f"LongMemEval history {task.id} #{index + 1}",
                    "turns": [
                        {
                            "role": str(turn.get("role") or "user"),
                            "content": str(turn.get("content") or ""),
                        }
                        for turn in turns
                        if turn.get("content") is not None
                    ],
                }
            )
        contract = self.manifest["answerContract"]
        return EvalCase(
            id=task.id,
            suite=f"longmemeval-{split}",
            version=1,
            hard_gate=True,
            query=self.query_for_task(task),
            expected=ExpectedSpec(max_steps=int(budgets["maxSteps"])),
            budgets=BudgetSpec(
                max_total_tokens=int(budgets["maxTokens"]),
                max_wall_ms=int(budgets["maxWallMs"]),
                max_tool_calls=int(budgets["maxToolCalls"]),
            ),
            script=[{"type": "final", "content": "FINAL_ANSWER: fixture"}],
            source_path=str(self.manifest_path),
            tools=[],
            answer_contract=contract,
            metadata={
                "source": "official",
                "questionType": task.metadata["questionType"],
                "abstention": bool(task.metadata["abstention"]),
                "split": split,
            },
            user_id=user_id,
            memory_seed={
                "userId": user_id,
                "namespace": f"eval-lme-{task_token}-{namespace_token}",
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
    ) -> BenchmarkRun:
        return BenchmarkRun(
            task=task,
            trace=trace,
            trace_path=trace_path,
            calls=[],
            conversation=trace.conversation,
            environment_events=trace.environment_events,
            environment_snapshots=trace.environment_snapshots,
            metadata={"prepared": prepared.metadata if prepared else {}},
        )

    def score(self, run: BenchmarkRun) -> CaseResult:
        case = self.case_for_task(
            run.task,
            namespace=str(run.metadata.get("namespace") or "report"),
        )
        contract = self.manifest["answerContract"]
        final_answer = _extract_final_answer(
            run.trace.final_text,
            str(contract["linePrefix"]),
        )
        judge_result: dict[str, Any] | None = None
        judge_error: str | None = None
        if final_answer is not None:
            try:
                judge_result = judge_longmemeval_answer(
                    judge=self.judge,
                    task=str(run.task.metadata["questionType"]),
                    question=run.task.question,
                    answer=str(run.task.metadata["answer"]),
                    response=final_answer,
                    abstention=bool(run.task.metadata["abstention"]),
                )
            except Exception as exc:  # noqa: BLE001 - judge boundary is unverified
                judge_error = str(exc)
        passed = (
            judge_result.get("label")
            if judge_result is not None
            else None
        )
        score_status = "verified" if judge_result is not None else "unverified"
        scores = [
            ScoreRecord(
                case_id=run.task.id,
                scorer="longmemeval.official-prompt",
                name="official_qa_accuracy",
                value=passed,
                unit="boolean",
                passed=passed,
                hard_gate=True,
                status=score_status,
                evidence={
                    "evaluator": "LongMemEval evaluate_qa.py prompt",
                    "evaluatorRevision": self.manifest["officialEvaluator"]["revision"],
                    "judgeModel": self.judge.model,
                    "judgeCompatibility": (
                        "official-model"
                        if self.judge.model in OFFICIAL_JUDGE_MODELS
                        else "compatible-nonofficial-model"
                    ),
                    "judgeRawLabel": (
                        judge_result.get("rawLabel") if judge_result else None
                    ),
                    "judgeError": judge_error,
                    "questionType": run.task.metadata["questionType"],
                    "abstention": run.task.metadata["abstention"],
                    "expectedAnswer": run.task.metadata["answer"],
                    "finalAnswer": final_answer,
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="longmemeval.contract",
                name="answer_contract",
                value=final_answer is not None,
                unit="boolean",
                passed=final_answer is not None,
                hard_gate=True,
                status="verified",
                evidence={
                    "linePrefix": contract["linePrefix"],
                    "rawAnswer": run.trace.final_text,
                },
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="longmemeval.metrics",
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
                scorer="longmemeval.metrics",
                name="token_budget",
                value=run.trace.total_tokens,
                unit="tokens",
                passed=(
                    case.budgets.max_total_tokens is None
                    or run.trace.total_tokens is None
                    or run.trace.total_tokens <= case.budgets.max_total_tokens
                ),
                hard_gate=True,
                status=(
                    "verified"
                    if run.trace.total_tokens is not None
                    else "unverified"
                ),
                evidence={"maxTotalTokens": case.budgets.max_total_tokens},
            ),
            ScoreRecord(
                case_id=run.task.id,
                scorer="longmemeval.metrics",
                name="wall_time",
                value=run.trace.duration_ms,
                unit="ms",
                passed=(
                    case.budgets.max_wall_ms is None
                    or run.trace.duration_ms is None
                    or run.trace.duration_ms <= case.budgets.max_wall_ms
                ),
                hard_gate=True,
                status=(
                    "verified"
                    if run.trace.duration_ms is not None
                    else "unverified"
                ),
                evidence={"maxWallMs": case.budgets.max_wall_ms},
            ),
        ]
        return CaseResult(
            case=case,
            trace=run.trace,
            scores=scores,
            trace_path=run.trace_path,
        )

    def dataset_hash(self) -> str:
        rows = self._load_rows(self._active_split)
        return _json_hash(
            {
                "name": self.name,
                "version": self.version,
                "revision": self.revision,
                "datasetFile": self.dataset_file,
                "rows": rows,
            }
        )

    def report_metadata(self) -> dict[str, Any]:
        split = self._active_split
        official_judge = self.judge.model in OFFICIAL_JUDGE_MODELS
        if split == "official-full":
            score_label = (
                "official-full" if official_judge else "compatibility-full"
            )
        else:
            score_label = (
                "official-subset" if official_judge else "compatibility-subset"
            )
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "license": self.manifest["license"],
            "split": split,
            "scoreLabel": score_label,
            "officialEvaluator": False,
            "evaluatorCompatibility": "exact-official-prompt-and-label-rule",
            "judgeModel": self.judge.model,
            "judgeCompatibleWithOfficialScore": official_judge,
            "runnerMode": self.manifest["runnerMode"],
            "revision": self.revision,
            "datasetFile": self.dataset_file,
            "selectedTaskIds": [task.id for task in self.tasks(split)],
            "datasetHash": self.dataset_hash(),
        }
