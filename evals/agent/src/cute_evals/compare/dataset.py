from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any

import jsonschema

from ..models import EvalCase


class CompareDatasetError(ValueError):
    """Raised when a comparison dataset is invalid."""


@dataclass(slots=True)
class CompareCase:
    id: str
    query: str
    workspace_template: Path
    workspace_read_only_paths: list[str]
    hard_gate: bool
    expected: dict[str, Any]
    budgets: dict[str, Any]
    network: dict[str, Any]
    judge: dict[str, Any]
    metadata: dict[str, Any]

    @property
    def workspace_checks(self) -> list[dict[str, Any]]:
        return list(self.expected.get("workspaceChecks") or [])

    def to_eval_case(self, source_path: Path, suite: str) -> EvalCase:
        expected = {
            key: value
            for key, value in self.expected.items()
            if key != "workspaceChecks"
        }
        if self.budgets.get("maxSteps") is not None:
            expected["maxSteps"] = self.budgets["maxSteps"]
        raw = {
            "id": self.id,
            "suite": suite,
            "version": 1,
            "hardGate": self.hard_gate,
            "input": {"message": self.query},
            "expected": expected,
            "budgets": self.budgets,
            "script": [],
            "metadata": {
                **self.metadata,
                **(
                    {"compareJudge": self.judge}
                    if self.judge
                    else {}
                ),
                "compareWorkspaceChecks": self.workspace_checks,
                "compareNetwork": self.network,
                "compareWorkspaceTemplate": str(self.workspace_template),
            },
        }
        return EvalCase.from_dict(
            raw,
            str(source_path),
            require_script=False,
        )


@dataclass(slots=True)
class CompareDataset:
    schema_version: str
    suite: str
    version: int
    cases: list[CompareCase]
    dataset_hash: str
    source_path: Path
    raw: dict[str, Any] = field(repr=False)


def _schema_path() -> Path:
    candidates = [
        Path(__file__).resolve().parents[3] / "schemas" / "compare-case.schema.json",
        Path(__file__).resolve().parents[1] / "schemas" / "compare-case.schema.json",
    ]
    for path in candidates:
        if path.exists():
            return path
    raise CompareDatasetError("compare-case.schema.json was not found")


def dataset_hash(raw: dict[str, Any]) -> str:
    encoded = json.dumps(
        raw,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


def _safe_relative_path(value: str, label: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise CompareDatasetError(f"{label} must be a relative path: {value}")
    return path


def load_compare_dataset(path: Path) -> CompareDataset:
    source_path = path.resolve()
    try:
        raw = json.loads(source_path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise CompareDatasetError(f"compare dataset not found: {source_path}") from exc
    except json.JSONDecodeError as exc:
        raise CompareDatasetError(
            f"invalid compare dataset JSON at {source_path}: {exc}"
        ) from exc

    schema = json.loads(_schema_path().read_text(encoding="utf-8"))
    try:
        jsonschema.validate(raw, schema)
    except jsonschema.ValidationError as exc:
        location = "/".join(str(part) for part in exc.absolute_path)
        raise CompareDatasetError(
            f"compare dataset schema validation failed at {location or '<root>'}: "
            f"{exc.message}"
        ) from exc

    seen: set[str] = set()
    cases: list[CompareCase] = []
    dataset_root = source_path.parent
    for item in raw["cases"]:
        case_id = str(item["id"])
        if case_id in seen:
            raise CompareDatasetError(f"duplicate compare case id: {case_id}")
        seen.add(case_id)

        workspace = item["workspace"]
        template_rel = _safe_relative_path(
            str(workspace["template"]),
            f"{case_id}.workspace.template",
        )
        template = (dataset_root / Path(*template_rel.parts)).resolve()
        if dataset_root.resolve() not in template.parents and template != dataset_root.resolve():
            raise CompareDatasetError(
                f"{case_id}.workspace.template escapes the dataset directory"
            )
        if not template.is_dir():
            raise CompareDatasetError(
                f"{case_id}.workspace.template is not a directory: {template}"
            )

        read_only_paths = []
        for value in workspace.get("readOnlyPaths") or []:
            safe = _safe_relative_path(
                str(value),
                f"{case_id}.workspace.readOnlyPaths",
            )
            read_only_paths.append(safe.as_posix())
        for check in item["expected"].get("workspaceChecks") or []:
            _safe_relative_path(
                str(check["path"]),
                f"{case_id}.expected.workspaceChecks.path",
            )

        cases.append(
            CompareCase(
                id=case_id,
                query=str(item["query"]),
                workspace_template=template,
                workspace_read_only_paths=read_only_paths,
                hard_gate=bool(item.get("hardGate", True)),
                expected=dict(item["expected"]),
                budgets=dict(item.get("budgets") or {}),
                network=dict(item.get("network") or {"mode": "deny", "hosts": []}),
                judge=dict(item.get("judge") or {}),
                metadata=dict(item.get("metadata") or {}),
            )
        )

    return CompareDataset(
        schema_version=str(raw["schemaVersion"]),
        suite=str(raw["suite"]),
        version=int(raw["version"]),
        cases=cases,
        dataset_hash=dataset_hash(raw),
        source_path=source_path,
        raw=raw,
    )
