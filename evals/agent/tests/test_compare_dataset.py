from __future__ import annotations

import json
from pathlib import Path

import pytest

from cute_evals.compare.dataset import CompareDatasetError, load_compare_dataset


DATASET = (
    Path(__file__).resolve().parents[1]
    / "cases"
    / "compare"
    / "pilot.json"
)
EXTENDED_DATASET = DATASET.with_name("extended.json")


def test_compare_pilot_validates_and_has_stable_hash() -> None:
    first = load_compare_dataset(DATASET)
    second = load_compare_dataset(DATASET)

    assert first.dataset_hash == second.dataset_hash
    assert first.dataset_hash.startswith("sha256:")
    assert first.suite == "compare-pilot"
    assert len(first.cases) == 12
    assert len({case.id for case in first.cases}) == len(first.cases)
    assert all(case.workspace_template.is_dir() for case in first.cases)


def test_compare_case_maps_to_existing_eval_case() -> None:
    dataset = load_compare_dataset(DATASET)
    source = dataset.cases[0]
    case = source.to_eval_case(dataset.source_path, dataset.suite)

    assert case.id == source.id
    assert case.query == source.query
    assert case.hard_gate is True
    assert case.script == []
    assert case.metadata["compareWorkspaceChecks"] == source.workspace_checks


def test_compare_case_carries_explicit_judge_configuration() -> None:
    dataset = load_compare_dataset(DATASET)
    source = next(
        case for case in dataset.cases if case.id == "compare.safety.clarify"
    )
    case = source.to_eval_case(dataset.source_path, dataset.suite)

    assert case.metadata["compareJudge"]["rubricId"] == "clarification.v1"
    assert case.metadata["compareJudge"]["mode"] == "advisory"


def test_compare_extended_dataset_validates() -> None:
    dataset = load_compare_dataset(EXTENDED_DATASET)

    assert dataset.suite == "compare-extended"
    assert len(dataset.cases) == 26
    assert all(case.workspace_template.is_dir() for case in dataset.cases)


def test_compare_dataset_rejects_duplicate_ids(tmp_path: Path) -> None:
    payload = json.loads(DATASET.read_text(encoding="utf-8"))
    payload["cases"][1]["id"] = payload["cases"][0]["id"]
    for case in payload["cases"]:
        template = Path(case["workspace"]["template"])
        destination = tmp_path / template
        destination.mkdir(parents=True, exist_ok=True)
    dataset_path = tmp_path / "duplicate.json"
    dataset_path.write_text(
        json.dumps(payload, ensure_ascii=False),
        encoding="utf-8",
    )

    with pytest.raises(CompareDatasetError, match="duplicate compare case id"):
        load_compare_dataset(dataset_path)
