import json

import pytest

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
