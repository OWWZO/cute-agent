from __future__ import annotations

from cute_evals.benchmarks.rag import MragRun, RagAdapter
from cute_evals.models import RunTrace


def _run_for(adapter: RagAdapter, task, *, trace, answer: str):
    mrag = MragRun(
        task_id=task.id,
        request_id="request-1",
        status="SUCCESS",
        answer=answer,
        events=[{"stage": "final", "isFinal": True}],
        retrieval_trace=trace,
        duration_ms=1200,
        started_at="2026-11-09T00:00:00.000Z",
        finished_at="2026-11-09T00:00:01.200Z",
        question=task.question,
        base_url="http://127.0.0.1:1601/v1/tool/mragQuery",
        kb_id="policy",
        context_text="context",
        retrieved_contexts=["context"],
        total_tokens=100,
    )
    trace_payload = mrag.trace_payload(trace_path="attempts/01/tasks/trace.json")
    run_trace = RunTrace.from_dict(trace_payload)
    return adapter.collect(
        task,
        run_trace,
        "attempts/01/tasks/trace.json",
        retrieval_trace=mrag.retrieval_trace,
    )


def test_rag_python_suite_is_loaded() -> None:
    adapter = RagAdapter()

    tasks = adapter.tasks("internal")

    assert len(tasks) == 7
    assert tasks[0].id == "rag-python.cited-answer"
    assert all(task.metadata["source"] == "project-owned" for task in tasks)


def test_citation_recall_is_unverified_without_retrieval_trace() -> None:
    adapter = RagAdapter()
    task = adapter.tasks("internal")[0]
    run = _run_for(
        adapter,
        task,
        trace=None,
        answer="国内差旅住宿上限为 1200 元。[policy.pdf#p3]",
    )

    result = adapter.score(run)
    recall = next(score for score in result.scores if score.name == "citation_recall")

    assert recall.status == "unverified"
    assert recall.hard_gate is True
    assert result.unverified is True


def test_citation_recall_uses_full_retrieval_trace() -> None:
    adapter = RagAdapter()
    task = adapter.tasks("internal")[0]
    trace = {
        "merged_text": {
            "hits": [
                {
                    "canonical_key": "policy.pdf#p3",
                    "payload": {
                        "text": "国内差旅住宿报销上限为 1200 元。",
                        "filename": "policy.pdf#p3",
                    },
                }
            ]
        },
        "merged_image": {"hits": []},
        "merged_page": {"hits": []},
        "rerank_text": {"hits": []},
    }
    run = _run_for(
        adapter,
        task,
        trace=trace,
        answer="国内差旅住宿上限为 1200 元。[policy.pdf#p3]",
    )

    result = adapter.score(run)
    recall = next(score for score in result.scores if score.name == "citation_recall")

    assert recall.status == "verified"
    assert recall.passed is True
    assert result.unverified is False


def test_forbidden_answer_text_fails_expected_facts() -> None:
    adapter = RagAdapter()
    task = adapter.tasks("internal")[1]
    run = _run_for(
        adapter,
        task,
        trace=None,
        answer="当前员工年假为 5 天。[handbook-v1.pdf#p2]",
    )

    result = adapter.score(run)
    facts = next(score for score in result.scores if score.name == "expected_facts")

    assert facts.passed is False
    assert result.passed is False
