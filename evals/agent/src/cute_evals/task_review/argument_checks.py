from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any


@dataclass(slots=True)
class ArgumentCheck:
    parsed: Any
    json_invalid: bool = False
    missing_required: list[str] | None = None
    type_errors: list[str] | None = None

    def __post_init__(self) -> None:
        self.missing_required = list(self.missing_required or [])
        self.type_errors = list(self.type_errors or [])


def _schema_for(contract: dict[str, Any] | None) -> dict[str, Any] | None:
    if not isinstance(contract, dict):
        return None
    for key in ("inputSchema", "parameters", "schema"):
        value = contract.get(key)
        if isinstance(value, dict):
            return value
        if isinstance(value, str):
            try:
                parsed = json.loads(value)
            except (TypeError, ValueError):
                continue
            if isinstance(parsed, dict):
                return parsed
    return None


def _matches_type(value: Any, expected: str) -> bool:
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    if expected == "string":
        return isinstance(value, str)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "null":
        return value is None
    return True


def check_arguments(
    input_value: Any,
    contract: dict[str, Any] | None,
) -> ArgumentCheck:
    parsed = input_value
    if isinstance(input_value, str):
        text = input_value.strip()
        if not text:
            parsed = {}
        else:
            try:
                parsed = json.loads(text)
            except (TypeError, ValueError):
                return ArgumentCheck(parsed=input_value, json_invalid=True)

    schema = _schema_for(contract)
    if schema is None or not isinstance(parsed, dict):
        return ArgumentCheck(parsed=parsed)

    required = schema.get("required")
    missing = [
        str(name)
        for name in (required if isinstance(required, list) else [])
        if name not in parsed
    ]
    properties = schema.get("properties")
    type_errors: list[str] = []
    if isinstance(properties, dict):
        for name, value in parsed.items():
            definition = properties.get(name)
            if not isinstance(definition, dict):
                continue
            expected = definition.get("type")
            if isinstance(expected, str) and not _matches_type(value, expected):
                type_errors.append(f"{name} 应为 {expected}")
    return ArgumentCheck(
        parsed=parsed,
        missing_required=missing,
        type_errors=type_errors,
    )
