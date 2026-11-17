from __future__ import annotations

import json
from pathlib import Path

import jsonschema
import pytest
from fastapi.testclient import TestClient

from cute_evals.service.app import create_app
from cute_evals.task_review.action_compiler import build_developer_actions
from cute_evals.task_review.action_playbooks import get_playbook
from cute_evals.task_review.diagnostics import build_diagnostics
from cute_evals.task_review.evidence import resolve_evidence
from cute_evals.task_review.evidence_index import (
    EvidenceRefError,
    build_evidence_index,
)
from cute_evals.task_review.facts import build_fast_report


def _bundle() -> dict:
    return json.loads(
        (
            Path(__file__).parent
            / "fixtures"
            / "task_review"
            / "evidence_bundle.json"
        ).read_text(encoding="utf-8")
    )


def _schema(name: str) -> dict:
    return json.loads(
        (Path(__file__).parents[1] / "schemas" / name).read_text(
            encoding="utf-8"
        )
    )


def test_evidence_index_and_all_fixture_refs_resolve() -> None:
    bundle = _bundle()
    index = build_evidence_index(bundle)
    refs = [
        "tool:1794819108266-9502:call_00_evbFUryT1gORJydhQYT84474",
        "tool:1794819108266-9502:call_00_1vWsLg98HlgrUu1kxDJU3779",
        "tool:1794819108266-9502:call_00_VFQSy5NvUino2xkUGz655695",
        "tool:1794819108266-9502:call_00_KlxXpkpA2GZEs9C84UbX5314",
    ]

    assert set(refs) == set(index)
    for ref in refs:
        evidence = resolve_evidence(bundle, ref)
        assert evidence["resolved"] is True
        assert evidence["ref"] == ref
        assert evidence["input"]["present"] is True
        jsonschema.validate(
            evidence,
            _schema("task-review-evidence.schema.json"),
        )


def test_evidence_resolver_uses_tool_result_fallback_and_links_recovery() -> None:
    bundle = _bundle()
    first = resolve_evidence(
        bundle,
        "tool:1794819108266-9502:call_00_evbFUryT1gORJydhQYT84474",
    )
    fourth = resolve_evidence(
        bundle,
        "tool:1794819108266-9502:call_00_KlxXpkpA2GZEs9C84UbX5314",
    )

    assert first["output"]["source"] == "toolResult"
    assert first["output"]["raw"] == "selector not found: #publish-button"
    assert first["recovery"]["recovered"] is True
    assert (
        "tool:1794819108266-9502:call_00_1vWsLg98HlgrUu1kxDJU3779"
        in first["recovery"]["refs"]
    )
    assert fourth["artifacts"][0]["artifactId"] == "artifact-1"


def test_evidence_legacy_ref_is_supported_and_ambiguity_is_explicit() -> None:
    bundle = _bundle()
    legacy = resolve_evidence(
        bundle,
        "tool:call_00_KlxXpkpA2GZEs9C84UbX5314",
    )
    assert legacy["requestId"] == "1794819108266-9502"

    second_run = json.loads(json.dumps(bundle["runs"][0]))
    second_run["requestId"] = "req-2"
    second_run["trace"]["run"]["requestId"] = "req-2"
    bundle["runs"].append(second_run)
    with pytest.raises(EvidenceRefError) as exc:
        resolve_evidence(
            bundle,
            "tool:call_00_KlxXpkpA2GZEs9C84UbX5314",
        )
    assert exc.value.code == "AMBIGUOUS_EVIDENCE_REF"
    assert len(exc.value.candidates) == 2


def test_evidence_invalid_and_missing_refs_have_stable_errors() -> None:
    bundle = _bundle()
    with pytest.raises(EvidenceRefError) as invalid:
        resolve_evidence(bundle, "artifact:1")
    assert invalid.value.code == "INVALID_EVIDENCE_REF"

    with pytest.raises(EvidenceRefError) as missing:
        resolve_evidence(bundle, "tool:req-1:missing")
    assert missing.value.code == "EVIDENCE_NOT_FOUND"


def test_evidence_redacts_secrets_and_truncates_large_payloads() -> None:
    bundle = _bundle()
    tool = bundle["runs"][0]["trace"]["toolInvocations"][3]
    tool["inputJson"] = json.dumps(
        {
            "command": "x" * 30_000,
            "apiKey": "sk-should-not-leak",
            "path": "D:\\\\Users\\\\alice\\\\workspace\\\\secret.txt",
        }
    )
    tool["toolResult"] = "Bearer secret-token " + ("y" * 30_000)

    evidence = resolve_evidence(
        bundle,
        "tool:1794819108266-9502:call_00_KlxXpkpA2GZEs9C84UbX5314",
    )
    serialized = json.dumps(evidence, ensure_ascii=False)

    assert "sk-should-not-leak" not in serialized
    assert "secret-token" not in serialized
    assert "D:\\\\Users\\\\alice" not in serialized
    assert evidence["input"]["truncated"] is True
    assert evidence["output"]["truncated"] is True
    assert "<truncated>" in evidence["input"]["raw"]
    assert "<truncated>" in evidence["output"]["raw"]


def test_action_compiler_groups_same_bug_and_keeps_distinct_tools_apart() -> None:
    bundle = _bundle()
    fast = build_fast_report(bundle)
    attributions, _, _ = build_diagnostics(bundle)
    actions = build_developer_actions(attributions, fast=fast)

    assert actions
    assert all(action["agentPrompt"] for action in actions)
    assert all(len(action["fixPlan"]) >= 2 for action in actions)
    assert all(action["testPlan"]["unit"] for action in actions)
    assert all(action["testPlan"]["integration"] for action in actions)
    assert all(action["testPlan"]["regression"] for action in actions)
    assert len({action["toolName"] for action in actions}) == len(actions)
    for action in actions:
        assert "## 问题定义" in action["agentPrompt"]
        assert "## 最小复现" in action["agentPrompt"]
        assert "## 测试要求" in action["agentPrompt"]
        assert "## 验收标准" in action["agentPrompt"]


def test_all_failure_playbooks_are_specific_and_actionable() -> None:
    failure_kinds = [
        "ARG_JSON_INVALID",
        "ARG_REQUIRED_MISSING",
        "ARG_TYPE_MISMATCH",
        "ARG_SELECTOR_NOT_FOUND",
        "ARG_PATH_NOT_FOUND",
        "ARG_URL_INVALID",
        "ARG_REPEATED_AFTER_FAILURE",
        "FAIL_TIMEOUT",
        "FAIL_NETWORK",
        "FAIL_AUTH",
        "FAIL_EXECUTOR_EXCEPTION",
        "FAIL_RESULT_MISSING",
        "FAIL_SCHEMA_CONTRACT",
        "FAIL_USER_STOP",
        "UNKNOWN_FAILURE",
    ]

    for failure_kind in failure_kinds:
        playbook = get_playbook(
            failure_kind,
            tool_name="fixture_tool",
            primary_layer="MODEL_ARGUMENT",
        )
        assert "fixture_tool" in playbook.title
        assert playbook.observed
        assert playbook.expected
        assert len(playbook.fix_plan) >= 2
        assert playbook.unit_tests
        assert playbook.integration_tests
        assert playbook.regression_tests
        assert playbook.acceptance_criteria


def test_evidence_api_maps_success_and_error_codes(tmp_path: Path) -> None:
    bundle = _bundle()
    ref = "tool:1794819108266-9502:call_00_1vWsLg98HlgrUu1kxDJU3779"

    class _Manager:
        def create(self, headers, **kwargs):
            return {}

        def get(self, job_id):
            return {"jobId": job_id}

        def evidence(self, job_id, evidence_ref):
            return resolve_evidence(bundle, evidence_ref)

        def list(self, *, limit=20):
            return []

        def cancel(self, job_id):
            return {}

        def report_markdown(self, job_id):
            return ""

        def close(self):
            return None

    app = create_app(manager=_Manager())
    with TestClient(app) as client:
        response = client.get(
            "/task-review/jobs/review-test/evidence",
            params={"ref": ref},
        )
        assert response.status_code == 200
        assert response.json()["toolName"] == "browser"

        invalid = client.get(
            "/task-review/jobs/review-test/evidence",
            params={"ref": "bad"},
        )
        assert invalid.status_code == 400
        assert invalid.json()["error"]["code"] == "INVALID_EVIDENCE_REF"

        missing_ref = client.get("/task-review/jobs/review-test/evidence")
        assert missing_ref.status_code == 400
        assert missing_ref.json()["error"]["code"] == "INVALID_EVIDENCE_REF"
