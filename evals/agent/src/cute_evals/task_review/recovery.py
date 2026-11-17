from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from ..trajectory_quality import _tool_failure_signal

RECOVERY_TYPES = (
    "RETRY_SAME_INPUT",
    "CHANGED_ARGUMENT",
    "CHANGED_TOOL",
    "USER_INTERVENTION",
    "NO_RECOVERY",
    "UNKNOWN",
)


def canonical_args(value: Any) -> str:
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except (TypeError, ValueError):
            return value.strip()
        value = parsed
    if isinstance(value, (dict, list)):
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    return str(value or "").strip()


@dataclass(slots=True)
class Recovery:
    recovered: bool
    recovery_type: str
    summary: str
    evidence_refs: list[str]


def detect_recovery(
    index: int,
    tools: list[dict[str, Any]],
    *,
    request_id: str,
) -> Recovery:
    failed = tools[index]
    failed_name = str(failed.get("toolName") or "unknown")
    failed_args = canonical_args(failed.get("inputJson"))
    failed_ref = f"tool:{request_id}:{failed.get('toolCallId') or failed.get('id') or index + 1}"

    for later in tools[index + 1 :]:
        status = str(later.get("status") or "UNKNOWN").upper()
        if _tool_failure_signal(later) is not None:
            continue
        if status not in {"SUCCESS", "COMPLETED"}:
            continue
        later_id = later.get("toolCallId") or later.get("id") or "unknown"
        later_ref = f"tool:{request_id}:{later_id}"
        later_name = str(later.get("toolName") or "unknown")
        later_args = canonical_args(later.get("inputJson"))
        if later_name == failed_name and later_args == failed_args:
            return Recovery(
                True,
                "RETRY_SAME_INPUT",
                "后续使用相同工具和参数重试后成功。",
                [failed_ref, later_ref],
            )
        if later_name == failed_name:
            return Recovery(
                True,
                "CHANGED_ARGUMENT",
                "后续保留工具类型但修改参数后成功。",
                [failed_ref, later_ref],
            )
        return Recovery(
            True,
            "CHANGED_TOOL",
            f"后续切换到 {later_name} 工具后成功。",
            [failed_ref, later_ref],
        )
    return Recovery(
        False,
        "NO_RECOVERY",
        "没有观测到后续成功调用。",
        [failed_ref],
    )
