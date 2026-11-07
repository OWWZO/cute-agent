import json

import pytest

from cute_evals.data_dataset import fixture_files
from cute_evals.dataset_lock import dataset_hash as lock_dataset_hash
from cute_evals.rag_dataset import dataset_hash, load_rag_dataset_manifest


def test_rag_dataset_manifest_locks_case_contents(tmp_path) -> None:
    core = tmp_path / "rag" / "core"
    core.mkdir(parents=True)
    case = core / "01-case.yaml"
    case.write_text("id: rag.case\n", encoding="utf-8")
    manifest = tmp_path / "rag" / "dataset.json"
    manifest.write_text(
        json.dumps({"datasetHash": dataset_hash(core)}),
        encoding="utf-8",
    )

    loaded = load_rag_dataset_manifest(core)

    assert loaded["datasetHash"] == dataset_hash(core)


def test_rag_dataset_manifest_rejects_changed_case(tmp_path) -> None:
    core = tmp_path / "rag" / "core"
    core.mkdir(parents=True)
    case = core / "01-case.yaml"
    case.write_text("id: rag.case\n", encoding="utf-8")
    manifest = tmp_path / "rag" / "dataset.json"
    manifest.write_text(
        json.dumps({"datasetHash": dataset_hash(core)}),
        encoding="utf-8",
    )

    case.write_text("id: rag.changed\n", encoding="utf-8")

    with pytest.raises(ValueError, match="hash mismatch"):
        load_rag_dataset_manifest(core)


def test_fixture_files_include_nested_files_but_not_directories(
    tmp_path,
) -> None:
    cases = tmp_path / "data" / "core"
    nested = cases / "fixtures" / "seed"
    nested.mkdir(parents=True)
    fixture = nested / "nested.sql"
    fixture.write_text("select 1;", encoding="utf-8")

    assert fixture_files(cases) == [fixture]


def test_dataset_hash_framing_distinguishes_concatenated_file_layouts(
    tmp_path,
) -> None:
    first = tmp_path / "first"
    second = tmp_path / "second"
    first.mkdir()
    second.mkdir()
    (first / "a.yaml").write_bytes(b"xb.yaml\0y")
    (second / "a.yaml").write_bytes(b"x")
    (second / "b.yaml").write_bytes(b"y")

    assert lock_dataset_hash(first) != lock_dataset_hash(second)
