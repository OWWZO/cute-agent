from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import yaml

from ...case_loader import load_case_file
from ...framework_scorers import score_ragas
from ...models import (
    CaseResult,
    EvalCase,
    JudgeConfig,
    RunTrace,
    ScoreRecord,
)
from ..base import BenchmarkRun, BenchmarkTask, PreparedTask

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"
REPO_ROOT = Path(__file__).resolve().parents[6]


def _json_hash(payload: Any) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    ).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


def _contains(value: str, text: str) -> bool:
    if value in text or value.strip("[]") in text:
        return True
    compact_value = "".join(value.split())
    compact_text = "".join(text.split())
    return compact_value in compact_text or compact_value.strip("[]") in compact_text


class RagAdapter:
    name = "rag-python"

    def __init__(
        self,
        manifest_path: Path = DEFAULT_MANIFEST,
        *,
        cases_dir: Path | None = None,
        limit: int | None = None,
    ) -> None:
        self.manifest_path = manifest_path.resolve()
        raw = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise TypeError(f"invalid RAG manifest: {self.manifest_path}")
        self.manifest: dict[str, Any] = raw
        self.version = str(raw["version"])
        self.upstream = str(raw["upstream"])
        self.score_label = str(raw["scoreLabel"])
        configured_cases_dir = Path(str(raw["dataset"]["casesDir"]))
        if cases_dir is not None:
            self.cases_dir = cases_dir.resolve()
        elif configured_cases_dir.is_absolute():
            self.cases_dir = configured_cases_dir
        else:
            candidate = REPO_ROOT / configured_cases_dir
            if not candidate.exists():
                candidate = REPO_ROOT / "evals" / "agent" / configured_cases_dir
            self.cases_dir = candidate
        if limit is not None and limit < 1:
            raise ValueError("--limit must be >= 1")
        self.limit = limit
        self._raw_cases: dict[str, dict[str, Any]] = {}
        self._cases: dict[str, EvalCase] = {}
        self._load_cases()

    def _load_cases(self) -> None:
        paths = sorted(self.cases_dir.glob("*.yaml"))
        if not paths:
            raise FileNotFoundError(f"no RAG Python cases found in {self.cases_dir}")
        for path in paths:
            raw = yaml.safe_load(path.read_text(encoding="utf-8"))
            if not isinstance(raw, dict):
                raise TypeError(f"RAG Python case must be a mapping: {path}")
            if raw.get("suite") != "rag-python":
                continue
            case = load_case_file(path, require_script=False)
            self._raw_cases[case.id] = raw
            self._cases[case.id] = case
        if not self._cases:
            raise ValueError(f"no cases with suite=rag-python in {self.cases_dir}")
        ordered = sorted(self._cases)
        if self.limit is not None:
            ordered = ordered[: self.limit]
        self._ordered_ids = ordered
        self._cases = {case_id: self._cases[case_id] for case_id in ordered}

    def tasks(self, split: str) -> list[BenchmarkTask]:
        if split != "internal":
            raise ValueError(
                "RAG Python uses --split internal; it is a project-owned suite"
            )
        tasks: list[BenchmarkTask] = []
        for case_id in self._ordered_ids:
            case = self._cases[case_id]
            tasks.append(
                BenchmarkTask(
                    id=case.id,
                    category=str(case.metadata.get("taskType") or "rag"),
                    question=case.query,
                    functions=[],
                    ground_truth=None,
                    raw=self._raw_cases[case_id],
                    turns=[{"query": case.query}],
                    metadata={
                        "source": "project-owned",
                        "kbId": str(self.manifest.get("knowledgeBase", {}).get("id") or ""),
                        "caseMetadata": dict(case.metadata),
                    },
                )
            )
        return tasks

    def prepare(self, task: BenchmarkTask, workspace: Path) -> PreparedTask:
        workspace.mkdir(parents=True, exist_ok=True)
        return PreparedTask(task=task, workspace=workspace, metadata={})

    def fixture_script(self, task: BenchmarkTask) -> dict[str, Any]:
        return {"script": []}

    def case_for_task(self, task: BenchmarkTask) -> EvalCase:
        return self._cases[task.id]

    def collect(
        self,
        task: BenchmarkTask,
        trace: RunTrace,
        trace_path: str,
        *,
        prepared: PreparedTask | None = None,
        retrieval_trace: dict[str, Any] | None = None,
    ) -> BenchmarkRun:
        return BenchmarkRun(
            task=task,
            trace=trace,
            trace_path=trace_path,
            calls=[],
            conversation=trace.conversation,
            environment_events=trace.environment_events,
            environment_snapshots=trace.environment_snapshots,
            metadata={
                "workspace": str(prepared.workspace) if prepared else None,
                "retrievalTrace": retrieval_trace,
            },
        )

    def _score(
        self,
        case: EvalCase,
        *,
        name: str,
        value: float | bool | str | None,
        unit: str,
        passed: bool | None,
        hard_gate: bool,
        status: str,
        evidence: dict[str, Any] | None = None,
    ) -> ScoreRecord:
        return ScoreRecord(
            case_id=case.id,
            scorer="mrag-python",
            name=name,
            value=value,
            unit=unit,
            passed=passed,
            hard_gate=hard_gate,
            status=status,
            evidence=evidence or {},
        )

    def score(
        self,
        run: BenchmarkRun,
        *,
        judge: JudgeConfig | None = None,
        require_judge: bool = False,
    ) -> CaseResult:
        case = self.case_for_task(run.task)
        trace = run.trace
        answer = trace.final_text.strip()
        required_facts = list(case.expected.final_contains)
        missing_facts = [
            fact for fact in required_facts if not _contains(fact, answer)
        ]
        forbidden_hits = [
            value
            for value in case.expected.final_not_contains
            if _contains(value, answer)
        ]
        required_citations = list(case.expected.required_citations)
        missing_citations = [
            citation
            for citation in required_citations
            if not _contains(citation, answer)
        ]
        retrieval_trace = run.metadata.get("retrievalTrace")
        if not isinstance(retrieval_trace, dict):
            environment = trace.raw.get("environment") or {}
            candidate = environment.get("retrievalTrace")
            retrieval_trace = candidate if isinstance(candidate, dict) else None
        retrieval_haystack = (
            json.dumps(retrieval_trace, ensure_ascii=False)
            if retrieval_trace is not None
            else ""
        )
        missing_retrieval = [
            citation
            for citation in required_citations
            if not _contains(citation, retrieval_haystack)
        ]

        scores = [
            self._score(
                case,
                name="answer_present",
                value=bool(answer),
                unit="boolean",
                passed=bool(answer),
                hard_gate=case.hard_gate,
                status="verified",
                evidence={"answerLength": len(answer)},
            ),
            self._score(
                case,
                name="expected_facts",
                value=not missing_facts and not forbidden_hits,
                unit="boolean",
                passed=not missing_facts and not forbidden_hits,
                hard_gate=case.hard_gate,
                status="verified",
                evidence={
                    "requiredFacts": required_facts,
                    "missingFacts": missing_facts,
                    "forbiddenHits": forbidden_hits,
                },
            ),
            self._score(
                case,
                name="citation_coverage",
                value=(
                    None
                    if not required_citations
                    else (len(required_citations) - len(missing_citations))
                    / len(required_citations)
                ),
                unit="ratio",
                passed=(
                    None
                    if not required_citations
                    else not missing_citations
                ),
                hard_gate=bool(required_citations) and case.hard_gate,
                status="verified",
                evidence={
                    "requiredCitations": required_citations,
                    "missingCitations": missing_citations,
                },
            ),
        ]

        if not required_citations:
            recall_status = "verified"
            recall_passed = None
            recall_value = None
            recall_evidence: dict[str, Any] = {
                "requiredCitations": [],
                "reason": (
                    "case declares no evidence"
                    if case.expected.no_evidence
                    else "no required citations"
                ),
            }
        elif retrieval_trace is None:
            recall_status = "unverified"
            recall_passed = None
            recall_value = None
            recall_evidence = {
                "requiredCitations": required_citations,
                "reason": "MRAG retrieval trace is unavailable; recall cannot be verified",
            }
        else:
            recall_status = "verified"
            recall_passed = not missing_retrieval
            recall_value = (
                len(required_citations) - len(missing_retrieval)
            ) / len(required_citations)
            recall_evidence = {
                "requiredCitations": required_citations,
                "missingCitations": missing_retrieval,
            }
        scores.append(
            self._score(
                case,
                name="citation_recall",
                value=recall_value,
                unit="ratio",
                passed=recall_passed,
                hard_gate=bool(required_citations) and case.hard_gate,
                status=recall_status,
                evidence=recall_evidence,
            )
        )

        if case.budgets.max_wall_ms is not None:
            wall_ok = (
                trace.duration_ms is not None
                and trace.duration_ms <= case.budgets.max_wall_ms
            )
            scores.append(
                self._score(
                    case,
                    name="wall_time",
                    value=trace.duration_ms,
                    unit="ms",
                    passed=wall_ok,
                    hard_gate=True,
                    status="verified",
                    evidence={"maxWallMs": case.budgets.max_wall_ms},
                )
            )

        if case.budgets.max_total_tokens is not None:
            if trace.total_tokens is None:
                scores.append(
                    self._score(
                        case,
                        name="token_budget",
                        value=None,
                        unit="tokens",
                        passed=None,
                        hard_gate=False,
                        status="unverified",
                        evidence={
                            "maxTotalTokens": case.budgets.max_total_tokens,
                            "reason": "MRAG stream did not expose token usage",
                        },
                    )
                )
            else:
                scores.append(
                    self._score(
                        case,
                        name="token_budget",
                        value=trace.total_tokens,
                        unit="tokens",
                        passed=trace.total_tokens <= case.budgets.max_total_tokens,
                        hard_gate=False,
                        status="verified",
                        evidence={
                            "maxTotalTokens": case.budgets.max_total_tokens,
                        },
                    )
                )

        scores.append(
            self._score(
                case,
                name="stage_contract",
                value=trace.status,
                unit="status",
                passed=str(trace.status).upper() == "SUCCESS",
                hard_gate=case.hard_gate,
                status="verified",
                evidence={"runStatus": trace.status},
            )
        )

        for score in score_ragas(
            case,
            trace,
            judge=judge,
            require_judge=require_judge,
        ):
            if score.name != "citation_recall":
                scores.append(score)

        return CaseResult(
            case=case,
            trace=trace,
            scores=scores,
            trace_path=run.trace_path,
        )

    def dataset_hash(self) -> str:
        return _json_hash(
            {
                "name": self.name,
                "version": self.version,
                "manifest": self.manifest,
                "cases": [
                    self._raw_cases[case_id]
                    for case_id in self._ordered_ids
                ],
            }
        )

    def report_metadata(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "license": self.manifest["license"],
            "split": "internal",
            "scoreLabel": self.score_label,
            "officialEvaluator": False,
            "runnerMode": self.manifest["runnerMode"],
            "selectedTaskIds": list(self._ordered_ids),
            "taskCount": len(self._ordered_ids),
            "limit": self.limit,
            "datasetHash": self.dataset_hash(),
        }
