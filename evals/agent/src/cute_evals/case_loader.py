from __future__ import annotations

import json
import re
from pathlib import Path

import jsonschema
import yaml

from .models import EvalCase

CASE_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
CASE_SCHEMA_PATH = (
    Path(__file__).resolve().parents[2] / "schemas" / "case.schema.json"
)


def load_cases(path: Path, suite: str | None = None) -> list[EvalCase]:
    case_paths = sorted(path.glob("*.yaml"))
    if not case_paths:
        raise FileNotFoundError(f"no YAML cases found in {path}")
    cases = []
    schema = json.loads(CASE_SCHEMA_PATH.read_text(encoding="utf-8"))
    for case_path in case_paths:
        raw = yaml.safe_load(case_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise TypeError(f"eval case must be a mapping: {case_path}")
        jsonschema.validate(raw, schema)
        case = EvalCase.from_dict(
            raw,
            str(case_path),
            require_script=raw.get("suite") != "private-badcase",
        )
        if not CASE_ID_PATTERN.fullmatch(case.id):
            raise ValueError(f"invalid case id {case.id!r}: {case_path}")
        if suite is None or case.suite == suite:
            cases.append(case)
    if not cases:
        raise ValueError(f"no cases matched suite={suite}")
    return cases


def load_case_file(path: Path, *, require_script: bool = True) -> EvalCase:
    """Load exactly one finalized case from a YAML file."""
    if not path.exists():
        raise FileNotFoundError(f"case file not found: {path}")
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise TypeError(f"eval case must be a mapping: {path}")
    schema = json.loads(CASE_SCHEMA_PATH.read_text(encoding="utf-8"))
    jsonschema.validate(raw, schema)
    case = EvalCase.from_dict(raw, str(path), require_script=require_script)
    if not CASE_ID_PATTERN.fullmatch(case.id):
        raise ValueError(f"invalid case id {case.id!r}: {path}")
    return case


def load_case_dirs(paths: list[Path], suite: str | None = None) -> list[EvalCase]:
    cases: list[EvalCase] = []
    for path in paths:
        cases.extend(load_cases(path, suite=suite))
    seen: set[str] = set()
    duplicates: set[str] = set()
    for case in cases:
        if case.id in seen:
            duplicates.add(case.id)
        seen.add(case.id)
    if duplicates:
        raise ValueError(f"duplicate case ids: {', '.join(sorted(duplicates))}")
    return sorted(cases, key=lambda case: case.id)
