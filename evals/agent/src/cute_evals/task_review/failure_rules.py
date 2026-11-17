from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

FAILURE_LAYERS = (
    "MODEL_DECISION",
    "MODEL_ARGUMENT",
    "HARNESS_CONTRACT",
    "HARNESS_RUNTIME",
    "ENVIRONMENT",
    "USER_ACTION",
    "TRACE_GAP",
    "UNKNOWN",
)

FAILED_STATUSES = frozenset(
    {"FAILED", "ERROR", "TIMEOUT", "STOPPED", "REJECTED", "CANCELLED"}
)

_AUTH = re.compile(
    r"(?i)(unauthori[sz]ed|forbidden|authentication|login required|"
    r"token expired|invalid token|401|403|登录失效|未登录|权限不足)"
)
_NETWORK = re.compile(
    r"(?i)(dns|name resolution|connection refused|connection reset|"
    r"network is unreachable|socket|econn|timed out connecting|"
    r"网络|连接失败|无法连接)"
)
_TIMEOUT = re.compile(r"(?i)(timeout|timed out|deadline exceeded|超时)")
_EXECUTOR = re.compile(
    r"(?i)(nullpointer|illegalstate|indexoutofbounds|serialization|"
    r"deserialize|executor crashed|internal server error|stack trace|"
    r"序列化|执行器崩溃|内部异常)"
)
_CONTRACT = re.compile(
    r"(?i)(unknown error|tool failed|operation failed|something went wrong|"
    r"unsupported schema|schema mismatch|contract mismatch|"
    r"未知错误|工具失败|操作失败|格式不确定)"
)
_SELECTOR = re.compile(
    r"(?i)(selector|locator|xpath|css selector|no element|element not found|"
    r"选择器|元素不存在|找不到元素)"
)
_PATH = re.compile(
    r"(?i)(no such file|file not found|path not found|missing path|"
    r"不存在该文件|找不到文件|路径不存在)"
)
_URL = re.compile(
    r"(?i)(invalid url|expected url|url scheme|malformed url|"
    r"网址无效|URL.*(?:格式|协议))"
)
_USER_STOP = re.compile(
    r"(?i)(user cancelled|user canceled|cancelled by user|"
    r"stopped by user|用户取消|用户停止)"
)


@dataclass(frozen=True, slots=True)
class FailureClassification:
    failure_kind: str
    primary_layer: str
    contributing_layers: tuple[str, ...]
    reason: str
    confidence: float
    rule_ids: tuple[str, ...]


def _text(tool: dict[str, Any]) -> str:
    text = "\n".join(
        str(value)
        for value in (
            tool.get("errorMsg"),
            tool.get("errorCode"),
            tool.get("toolResult"),
            tool.get("llmObservation"),
            tool.get("statusReason"),
        )
        if value is not None and str(value).strip()
    )
    return re.sub(r"_+", " ", text)


def _classify_argument(
    tool: dict[str, Any],
    *,
    json_invalid: bool,
    missing_required: list[str],
    type_errors: list[str],
    error_text: str,
) -> FailureClassification | None:
    if json_invalid:
        return FailureClassification(
            "ARG_JSON_INVALID",
            "MODEL_ARGUMENT",
            (),
            "工具入参不是合法 JSON。",
            0.98,
            ("ARG_JSON_INVALID",),
        )
    if missing_required:
        return FailureClassification(
            "ARG_REQUIRED_MISSING",
            "MODEL_ARGUMENT",
            (),
            f"工具入参缺少必填字段：{', '.join(missing_required)}。",
            0.96,
            ("ARG_REQUIRED_MISSING",),
        )
    if type_errors:
        return FailureClassification(
            "ARG_TYPE_MISMATCH",
            "MODEL_ARGUMENT",
            (),
            f"工具入参类型不符合 Schema：{', '.join(type_errors)}。",
            0.94,
            ("ARG_TYPE_MISMATCH",),
        )
    if _URL.search(error_text):
        return FailureClassification(
            "ARG_URL_INVALID",
            "MODEL_ARGUMENT",
            (),
            "工具拒绝了不合法的 URL 或协议。",
            0.9,
            ("ARG_URL_INVALID",),
        )
    if _SELECTOR.search(error_text):
        return FailureClassification(
            "ARG_SELECTOR_NOT_FOUND",
            "MODEL_ARGUMENT",
            ("HARNESS_CONTRACT",)
            if _CONTRACT.search(error_text)
            else (),
            "工具选择器没有命中页面元素。",
            0.86,
            ("ARG_SELECTOR_NOT_FOUND",),
        )
    if _PATH.search(error_text):
        return FailureClassification(
            "ARG_PATH_NOT_FOUND",
            "MODEL_ARGUMENT",
            (),
            "工具参数中的路径不存在。",
            0.88,
            ("ARG_PATH_NOT_FOUND",),
        )
    return None


def classify_failure(
    tool: dict[str, Any],
    *,
    json_invalid: bool = False,
    missing_required: list[str] | None = None,
    type_errors: list[str] | None = None,
    repeated_after_failure: bool = False,
) -> FailureClassification:
    status = str(tool.get("status") or "UNKNOWN").upper()
    error_text = _text(tool)
    result_present = any(
        tool.get(key) is not None and str(tool.get(key)).strip()
        for key in ("toolResult", "llmObservation")
    )

    if status in {"STOPPED", "CANCELLED"} or _USER_STOP.search(error_text):
        return FailureClassification(
            "FAIL_USER_STOP",
            "USER_ACTION",
            (),
            "用户或上层流程主动停止了执行。",
            0.99,
            ("FAIL_USER_STOP",),
        )

    argument = _classify_argument(
        tool,
        json_invalid=json_invalid,
        missing_required=list(missing_required or []),
        type_errors=list(type_errors or []),
        error_text=error_text,
    )
    if argument is not None:
        if repeated_after_failure and argument.failure_kind != "ARG_JSON_INVALID":
            return FailureClassification(
                "ARG_REPEATED_AFTER_FAILURE",
                "MODEL_DECISION",
                (argument.primary_layer,),
                "失败后再次提交了完全相同的工具参数，没有先根据错误调整策略。",
                0.95,
                ("ARG_REPEATED_AFTER_FAILURE", *argument.rule_ids),
            )
        return argument

    if repeated_after_failure:
        return FailureClassification(
            "ARG_REPEATED_AFTER_FAILURE",
            "MODEL_DECISION",
            (),
            "失败后再次提交了完全相同的工具参数，没有先根据错误调整策略。",
            0.95,
            ("ARG_REPEATED_AFTER_FAILURE",),
        )
    if status == "TIMEOUT" or _TIMEOUT.search(error_text):
        return FailureClassification(
            "FAIL_TIMEOUT",
            "HARNESS_RUNTIME",
            ("ENVIRONMENT",) if _NETWORK.search(error_text) else (),
            "工具执行超时，没有在运行时预算内完成。",
            0.9,
            ("FAIL_TIMEOUT",),
        )
    if _AUTH.search(error_text):
        return FailureClassification(
            "FAIL_AUTH",
            "ENVIRONMENT",
            (),
            "登录凭证或权限状态失效。",
            0.93,
            ("FAIL_AUTH",),
        )
    if _NETWORK.search(error_text):
        return FailureClassification(
            "FAIL_NETWORK",
            "ENVIRONMENT",
            (),
            "运行环境出现网络或连接错误。",
            0.92,
            ("FAIL_NETWORK",),
        )
    if _EXECUTOR.search(error_text):
        return FailureClassification(
            "FAIL_EXECUTOR_EXCEPTION",
            "HARNESS_RUNTIME",
            (),
            "工具执行器内部异常，失败发生在业务逻辑之外。",
            0.9,
            ("FAIL_EXECUTOR_EXCEPTION",),
        )
    if _CONTRACT.search(error_text):
        return FailureClassification(
            "FAIL_SCHEMA_CONTRACT",
            "HARNESS_CONTRACT",
            (),
            "工具返回了不可操作的通用错误，无法据此修正调用。",
            0.78,
            ("FAIL_SCHEMA_CONTRACT", "HARNESS_ERROR_NOT_ACTIONABLE"),
        )
    if not result_present and not error_text:
        return FailureClassification(
            "FAIL_RESULT_MISSING",
            "TRACE_GAP",
            (),
            "Trace 中缺少工具结果和错误消息，无法可靠判断失败原因。",
            0.97,
            ("FAIL_RESULT_MISSING",),
        )
    return FailureClassification(
        "UNKNOWN_FAILURE",
        "UNKNOWN",
        (),
        "当前证据不足以把失败可靠归因到模型、Harness 或环境。",
        0.55,
        ("UNKNOWN_FAILURE",),
    )
