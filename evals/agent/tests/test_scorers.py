from __future__ import annotations

import json
import sys
import types

import pytest

from cute_evals.framework_scorers import _judge_cache_path
from cute_evals.models import (
    BudgetSpec,
    EvalCase,
    ExpectedSpec,
    JudgeConfig,
    RunTrace,
)
from cute_evals.scorers import score_case


def make_case() -> EvalCase:
    return EvalCase(
        id="react.test",
        suite="react-core",
        version=1,
        hard_gate=True,
        query="read config",
        expected=ExpectedSpec(
            final_contains=["3306"],
            max_steps=2,
            required_tools=["read_file"],
        ),
        budgets=BudgetSpec(max_total_tokens=400, max_wall_ms=1000),
        script=[
            {
                "type": "tool",
                "tool": "read_file",
                "arguments": {"path": "config.yaml"},
                "result": "db.port=3306",
            },
            {"type": "final", "content": "ok"},
        ],
        source_path="test.yaml",
    )


def make_trace() -> RunTrace:
    return RunTrace.from_dict(
        {
            "run": {
                "caseId": "react.test",
                "requestId": "req-1",
                "status": "SUCCESS",
                "finalSummaryText": "db.port=3306",
                "totalTokens": 220,
                "durationMs": 20,
            },
            "llmInvocations": [
                {
                    "stepNo": 1,
                    "startedAt": "2026-11-06T00:00:00.000",
                    "firstTokenAt": "2026-11-06T00:00:00.010",
                },
                {
                    "stepNo": 2,
                    "startedAt": "2026-11-06T00:00:00.020",
                    "firstTokenAt": "2026-11-06T00:00:00.030",
                },
            ],
            "toolInvocations": [
                {
                    "toolName": "read_file",
                    "inputJson": "{\"path\":\"config.yaml\"}",
                    "status": "SUCCESS",
                }
            ],
            "artifacts": [],
        }
    )


def test_budget_gate_failure_is_slo_not_answer_quality() -> None:
    case = make_case()
    trace = make_trace()
    trace.total_tokens = 500

    result = score_case(
        case,
        trace,
        "trace.json",
        profile="nightly",
    )

    token_budget = next(
        score for score in result.scores if score.name == "token_budget"
    )
    assert token_budget.passed is False
    assert result.quality_passed is True
    assert result.slo_passed is False
    assert result.passed is False


def make_skill_case(
    *,
    required_skill: str | None = "xlsx",
    forbidden_skills: list[str] | None = None,
    max_skill_loads: int | None = 1,
) -> EvalCase:
    return EvalCase(
        id="skill.test",
        suite="skill-sim",
        version=1,
        hard_gate=True,
        query="处理表格",
        expected=ExpectedSpec(
            final_contains=["完成"],
            required_skill=required_skill,
            forbidden_skills=forbidden_skills or [],
            max_skill_loads=max_skill_loads,
        ),
        budgets=BudgetSpec(max_total_tokens=400, max_wall_ms=1000),
        script=[{"type": "final", "content": "完成"}],
        source_path="skill.yaml",
    )


def make_skill_trace(*skill_names: str) -> RunTrace:
    return RunTrace.from_dict(
        {
            "run": {
                "caseId": "skill.test",
                "requestId": "req-skill",
                "status": "SUCCESS",
                "finalSummaryText": "完成",
                "totalTokens": 220,
                "durationMs": 20,
            },
            "llmInvocations": [{"stepNo": 1}],
            "toolInvocations": [
                {
                    "toolName": "skill_view",
                    "inputJson": f'{{"name":"{name}"}}',
                    "status": "SUCCESS",
                }
                for name in skill_names
            ],
            "artifacts": [],
        }
    )


def make_rag_case(*, no_evidence: bool = False) -> EvalCase:
    return EvalCase(
        id="rag.test",
        suite="rag-core",
        version=1,
        hard_gate=True,
        query="差旅住宿上限是多少？",
        expected=ExpectedSpec(
            final_contains=["1200", "[policy.pdf#p3]"],
            required_tools=["mrag_query"],
            required_citations=[] if no_evidence else ["[policy.pdf#p3]"],
            no_evidence=no_evidence,
        ),
        budgets=BudgetSpec(max_total_tokens=600, max_wall_ms=60000),
        script=[],
        source_path="rag.yaml",
    )


def make_rag_trace(*, context: str, final: str) -> RunTrace:
    return RunTrace.from_dict(
        {
            "run": {
                "caseId": "rag.test",
                "requestId": "req-rag",
                "status": "SUCCESS",
                "finalSummaryText": final,
                "totalTokens": 260,
                "durationMs": 20,
            },
            "llmInvocations": [{"stepNo": 1}],
            "toolInvocations": [
                {
                    "toolName": "mrag_query",
                    "inputJson": '{"question":"差旅住宿上限"}',
                    "toolResult": context,
                    "status": "SUCCESS",
                }
            ],
            "artifacts": [],
        }
    )


def make_data_case(tmp_path, *, expected_rows: list[list[object]] | None = None) -> EvalCase:
    fixtures = tmp_path / "fixtures"
    fixtures.mkdir()
    (fixtures / "analytics.sql").write_text(
        """
        CREATE TABLE inventory (product_id TEXT, stock INTEGER, ordered INTEGER);
        INSERT INTO inventory VALUES ('P-100', 15, 7);
        """,
        encoding="utf-8",
    )
    return EvalCase(
        id="data.test",
        suite="data-core",
        version=1,
        hard_gate=True,
        query="计算可用库存",
        expected=ExpectedSpec(
            final_contains=["8"],
            required_tools=["sql_query"],
            sql_oracle={
                "fixture": "fixtures/analytics.sql",
                "expectedColumns": ["available"],
                "expectedRows": expected_rows if expected_rows is not None else [[8]],
                "numericTolerance": 0.0,
                "ordered": False,
            },
        ),
        budgets=BudgetSpec(max_total_tokens=600, max_wall_ms=60000),
        script=[],
        source_path=str(tmp_path / "data.yaml"),
    )


def make_data_trace(sql: str) -> RunTrace:
    return RunTrace.from_dict(
        {
            "run": {
                "caseId": "data.test",
                "requestId": "req-data",
                "status": "SUCCESS",
                "finalSummaryText": "可用库存为 8",
                "totalTokens": 260,
            },
            "llmInvocations": [{"stepNo": 1}],
            "toolInvocations": [
                {
                    "toolName": "sql_query",
                    "inputJson": json.dumps({"sql": sql}),
                    "toolResult": '{"rows":[{"available":8}]}',
                    "status": "SUCCESS",
                }
            ],
            "artifacts": [],
        }
    )


def test_deterministic_scores_pass() -> None:
    result = score_case(make_case(), make_trace(), "traces/react.test/trace.json")

    assert result.passed
    assert not result.unverified
    assert result.trace.first_token_ms() == 10.0
    assert any(
        score.name == "task_success" and score.passed
        for score in result.scores
    )


def test_missing_required_tool_fails() -> None:
    case = make_case()
    case.expected.required_tools = ["sql_query"]
    result = score_case(case, make_trace(), "traces/react.test/trace.json")

    assert not result.passed
    assert any(
        score.name == "tool_sequence" and not score.passed
        for score in result.scores
    )


def test_tool_sequence_must_follow_expected_order() -> None:
    case = make_case()
    case.expected.required_tool_sequence = ["list_dir", "read_file"]
    case.expected.strict_tool_sequence = True
    trace = make_trace()
    trace.tool_invocations.append(
        {
            "toolName": "list_dir",
            "inputJson": "{\"path\":\".\"}",
            "status": "SUCCESS",
        }
    )

    result = score_case(case, trace, "traces/react.test/trace.json")

    assert not result.passed
    sequence = next(score for score in result.scores if score.name == "tool_sequence")
    assert not sequence.passed
    assert sequence.evidence["sequenceMatched"] is False


def test_first_token_unverified_when_missing() -> None:
    trace = make_trace()
    for invocation in trace.llm_invocations:
        invocation["firstTokenAt"] = None
    result = score_case(make_case(), trace, "traces/react.test/trace.json")

    assert any(
        score.name == "first_token_latency" and score.status == "unverified"
        for score in result.scores
    )


def test_route_metrics_and_subgoal_progress_are_evidence_based() -> None:
    case = make_case()
    case.expected.reference_tool_sequence = ["read_file", "search_web"]
    case.expected.subgoals = [
        {"id": "read", "requiredTools": ["read_file"]},
        {"id": "answer", "finalContains": ["3306"]},
    ]

    result = score_case(case, make_trace(), "trace.json")
    scores = {score.name: score for score in result.scores}

    assert scores["route_precision"].value == 1.0
    assert scores["route_recall"].value == 0.5
    assert scores["route_f1"].value == pytest.approx(2 / 3)
    assert scores["subgoal_progress"].value == 1.0
    assert scores["subgoal_progress"].passed is True


def test_subgoal_progress_reports_partial_completion() -> None:
    case = make_case()
    case.expected.subgoals = [
        {"id": "read", "requiredTools": ["read_file"]},
        {"id": "search", "requiredTools": ["search_web"]},
    ]

    result = score_case(case, make_trace(), "trace.json")
    score = next(item for item in result.scores if item.name == "subgoal_progress")

    assert score.value == 0.5
    assert score.passed is False
    assert score.evidence["completed"] == 1


def test_subgoal_zero_minimum_tools_preserves_explicit_zero() -> None:
    case = make_case()
    case.expected.subgoals = [
        {
            "id": "answer-only",
            "requiredTools": ["search_web"],
            "finalContains": ["3306"],
            "minSuccessfulTools": 0,
        }
    ]

    result = score_case(case, make_trace(), "trace.json")
    score = next(item for item in result.scores if item.name == "subgoal_progress")

    assert score.value == 1.0
    assert score.passed is True
    assert score.evidence["subgoals"][0]["minSuccessfulTools"] == 0


def test_agentevals_trajectory_match_is_actually_evaluated() -> None:
    result = score_case(make_case(), make_trace(), "traces/react.test/trace.json")

    score = next(
        item
        for item in result.scores
        if item.scorer == "agentevals" and item.name == "trajectory_match"
    )
    assert score.status == "verified"
    assert score.passed is True


def test_require_judge_marks_deepeval_unverified_as_hard_gate() -> None:
    result = score_case(
        make_case(),
        make_trace(),
        "traces/react.test/trace.json",
        require_judge=True,
    )

    assert result.unverified


def test_corrupt_deepeval_cache_is_recomputed_instead_of_aborting(
    tmp_path,
    monkeypatch,
) -> None:
    case = make_case()
    trace = make_trace()
    judge = JudgeConfig(model="fake-judge")
    cache_path = _judge_cache_path(
        tmp_path,
        case=case,
        trace=trace,
        judge=judge,
    )
    assert cache_path is not None
    cache_path.parent.mkdir(parents=True)
    cache_path.write_text("{", encoding="utf-8")

    deepeval = types.ModuleType("deepeval")
    deepeval.__path__ = []
    metrics = types.ModuleType("deepeval.metrics")

    class BrokenGEval:
        def __init__(self, **kwargs):
            raise RuntimeError("recomputed")

    metrics.GEval = BrokenGEval
    test_case = types.ModuleType("deepeval.test_case")
    test_case.LLMTestCase = object
    test_case.SingleTurnParams = types.SimpleNamespace(
        INPUT="input",
        ACTUAL_OUTPUT="actual_output",
        EXPECTED_OUTPUT="expected_output",
    )
    monkeypatch.setitem(sys.modules, "deepeval", deepeval)
    monkeypatch.setitem(sys.modules, "deepeval.metrics", metrics)
    monkeypatch.setitem(sys.modules, "deepeval.test_case", test_case)

    result = score_case(
        case,
        trace,
        "trace.json",
        require_judge=True,
        judge=judge,
        judge_cache_dir=tmp_path,
    )
    score = next(
        item
        for item in result.scores
        if item.scorer == "deepeval"
    )

    assert score.status == "unverified"
    assert "recomputed" in score.evidence["reason"]


def test_skill_selection_accepts_expected_skill() -> None:
    result = score_case(
        make_skill_case(forbidden_skills=["pdf", "docx"]),
        make_skill_trace("xlsx"),
        "traces/skill.test/trace.json",
    )

    selection = next(score for score in result.scores if score.name == "skill_selection")
    assert selection.passed is True
    assert selection.value == ["xlsx"]
    assert result.passed


def test_skill_selection_rejects_wrong_skill() -> None:
    result = score_case(
        make_skill_case(required_skill="xlsx", forbidden_skills=["pdf"]),
        make_skill_trace("pdf"),
        "traces/skill.test/trace.json",
    )

    selection = next(score for score in result.scores if score.name == "skill_selection")
    assert selection.passed is False
    assert selection.evidence["forbiddenHits"] == ["pdf"]
    assert not result.passed


def test_skill_load_budget_rejects_repeated_load() -> None:
    result = score_case(
        make_skill_case(required_skill="xlsx", max_skill_loads=1),
        make_skill_trace("xlsx", "xlsx"),
        "traces/skill.test/trace.json",
    )

    load_count = next(score for score in result.scores if score.name == "skill_load_count")
    assert load_count.value == 2
    assert load_count.passed is False
    assert not result.passed


def test_no_skill_control_passes_without_skill_calls() -> None:
    result = score_case(
        make_skill_case(
            required_skill=None,
            forbidden_skills=["pdf", "xlsx", "docx"],
            max_skill_loads=0,
        ),
        make_skill_trace(),
        "traces/skill.test/trace.json",
    )

    selection = next(score for score in result.scores if score.name == "skill_selection")
    load_count = next(score for score in result.scores if score.name == "skill_load_count")
    assert selection.passed is True
    assert selection.value == []
    assert load_count.passed is True
    assert result.passed


def test_rag_scores_retrieval_and_citation_as_separate_layers() -> None:
    result = score_case(
        make_rag_case(),
        make_rag_trace(
            context="[policy.pdf#p3] 国内差旅住宿报销上限为 1200 元。",
            final="国内差旅住宿报销上限为 1200 元。[policy.pdf#p3]",
        ),
        "traces/rag.test/trace.json",
    )

    recall = next(score for score in result.scores if score.name == "citation_recall")
    coverage = next(score for score in result.scores if score.name == "citation_coverage")
    faithfulness = next(score for score in result.scores if score.name == "faithfulness")
    assert recall.passed is True
    assert coverage.passed is True
    assert faithfulness.status == "unverified"
    assert result.passed


def test_rag_retrieval_failure_does_not_hide_citation_failure() -> None:
    result = score_case(
        make_rag_case(),
        make_rag_trace(
            context="没有检索到制度条款。",
            final="国内差旅住宿报销上限为 1200 元。[policy.pdf#p3]",
        ),
        "traces/rag.test/trace.json",
    )

    recall = next(score for score in result.scores if score.name == "citation_recall")
    coverage = next(score for score in result.scores if score.name == "citation_coverage")
    assert recall.passed is False
    assert coverage.passed is True
    assert not result.passed


def test_rag_no_evidence_is_not_a_false_recall_failure() -> None:
    result = score_case(
        make_rag_case(no_evidence=True),
        make_rag_trace(
            context="未检索到关于宠物托运报销的制度条款。",
            final="知识库未提供宠物托运报销依据。",
        ),
        "traces/rag.test/trace.json",
    )

    recall = next(score for score in result.scores if score.name == "citation_recall")
    assert recall.value is None
    assert recall.passed is None
    assert recall.evidence["reason"] == "case declares no evidence"


def test_data_sql_oracle_executes_query_and_matches_rows(tmp_path) -> None:
    result = score_case(
        make_data_case(tmp_path),
        make_data_trace(
            "select stock - ordered as available from inventory where product_id = 'P-100'"
        ),
        "traces/data.test/trace.json",
    )

    execution = next(score for score in result.scores if score.name == "sql_execution")
    result_match = next(score for score in result.scores if score.name == "sql_result")
    assert execution.passed is True
    assert result_match.passed is True
    assert result_match.evidence["actualRows"] == [[8]]


def test_data_sql_oracle_rejects_wrong_rows(tmp_path) -> None:
    result = score_case(
        make_data_case(tmp_path),
        make_data_trace("select stock as available from inventory"),
        "traces/data.test/trace.json",
    )

    result_match = next(score for score in result.scores if score.name == "sql_result")
    assert result_match.passed is False
    assert result_match.evidence["actualRows"] == [[15]]
    assert not result.passed


def test_data_sql_oracle_rejects_write_statement(tmp_path) -> None:
    result = score_case(
        make_data_case(tmp_path),
        make_data_trace("delete from inventory"),
        "traces/data.test/trace.json",
    )

    execution = next(score for score in result.scores if score.name == "sql_execution")
    result_match = next(score for score in result.scores if score.name == "sql_result")
    assert execution.passed is False
    assert result_match.passed is False
    assert "only a single read-only" in execution.evidence["error"]
