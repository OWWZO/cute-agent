# -*- coding: utf-8 -*-
"""DeepSearch 有界迭代研究使用的状态与协议模型。"""

from dataclasses import dataclass, field
from typing import Any, Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


def _enum_token(value: Any) -> str:
    """将模型常见的自然语言枚举写法归一化为稳定 token。"""
    raw = str(value or "").strip().casefold()
    token = "".join(char if char.isalnum() else "_" for char in raw)
    return "_".join(part for part in token.split("_") if part)


class ResearchGap(BaseModel):
    """Reviewer 识别出的一个研究缺口。"""

    model_config = ConfigDict(extra="ignore", str_strip_whitespace=True)

    gap_id: str
    description: str
    priority: str
    status: str = "open"

    @field_validator("priority", mode="before")
    @classmethod
    def _priority_alias(cls, value):
        token = _enum_token(value)
        if token in {
            "high",
            "urgent",
            "critical",
            "medium",
            "moderate",
            "important",
            "severe",
            "blocking",
            "p0",
            "p1",
        }:
            return "high"
        return "normal"

    @field_validator("status", mode="before")
    @classmethod
    def _status_alias(cls, value):
        token = _enum_token(value)
        if token in {"resolved", "closed", "done", "complete", "completed", "fixed", "covered"}:
            return "resolved"
        if token in {"unresolved", "blocked", "missing", "unaddressed"}:
            return "unresolved"
        return "open"


class ResearchAction(BaseModel):
    """Reviewer 或章节规划器允许执行的研究动作。"""

    model_config = ConfigDict(extra="ignore", str_strip_whitespace=True)

    type: Literal["search", "fetch_url"]
    tool: Literal["web_search", "web_fetch"]
    query: str = ""
    url: str = ""
    reason: str = ""
    gap_ids: list[str] = Field(default_factory=list)

    @field_validator("type", mode="before")
    @classmethod
    def _type_alias(cls, value):
        token = _enum_token(value)
        if token in {"search", "web_search", "websearch", "search_web"}:
            return "search"
        if token in {
            "fetch",
            "fetch_url",
            "web_fetch",
            "webfetch",
            "open_url",
            "openurl",
        }:
            return "fetch_url"
        raise ValueError(f"unsupported research action type: {value!r}")

    @field_validator("tool", mode="before")
    @classmethod
    def _tool_alias(cls, value):
        token = _enum_token(value)
        if token in {"search", "web_search", "websearch", "search_web"}:
            return "web_search"
        if token in {
            "fetch",
            "fetch_url",
            "web_fetch",
            "webfetch",
            "open_url",
            "openurl",
        }:
            return "web_fetch"
        raise ValueError(f"unsupported research action tool: {value!r}")

    @field_validator("gap_ids", mode="before")
    @classmethod
    def _none_gap_ids(cls, value):
        return [] if value is None else value

    @model_validator(mode="after")
    def _validate_action_shape(self):
        if self.type == "search":
            if self.tool != "web_search":
                raise ValueError("search action requires tool=web_search")
            if not self.query:
                raise ValueError("search action requires a non-empty query")
            if self.url:
                raise ValueError("search action must not include url")
            return self

        if self.tool != "web_fetch":
            raise ValueError("fetch_url action requires tool=web_fetch")
        if not self.url:
            raise ValueError("fetch_url action requires a non-empty url")
        if self.query:
            raise ValueError("fetch_url action must not include query")
        parsed = urlsplit(self.url)
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc:
            raise ValueError("fetch_url action requires an absolute http(s) url")
        return self


# 旧调用方继续使用 SearchAction，但实际协议已经统一为 ResearchAction。
SearchAction = ResearchAction


class ReviewResult(BaseModel):
    """Reviewer 的结构化输出。"""

    model_config = ConfigDict(extra="ignore", str_strip_whitespace=True)

    base_version: int
    sufficient: bool
    gaps: list[ResearchGap] = Field(default_factory=list)
    actions: list[ResearchAction] = Field(default_factory=list)
    reason: str = ""


class EditOp(BaseModel):
    """Editor 输出的单条字符串编辑意图。"""

    model_config = ConfigDict(extra="ignore", str_strip_whitespace=False)

    old_text: str
    new_text: str
    reason: str = ""


class EditPlan(BaseModel):
    """Editor 输出的整批编辑意图。"""

    model_config = ConfigDict(extra="ignore", str_strip_whitespace=True)

    base_version: int
    ops: list[EditOp] = Field(default_factory=list)


@dataclass
class ResearchState:
    """单章研究循环的可恢复状态。"""

    summary: str
    summary_version: int = 0
    iteration: int = 0
    gaps: list[ResearchGap] = field(default_factory=list)
    searched_queries: list[str] = field(default_factory=list)
    fetched_urls: list[str] = field(default_factory=list)
    link_candidates: list[dict[str, Any]] = field(default_factory=list)
    source_ids: list[str] = field(default_factory=list)
    used_source_ids: list[str] = field(default_factory=list)
    dropped_source_ids: list[str] = field(default_factory=list)
    previous_edit_summary: str = ""
    stop_reason: str = ""


def _make_schema_strict(node: Any) -> None:
    """按 OpenAI structured outputs 要求补齐对象必填项。"""
    if isinstance(node, dict):
        properties = node.get("properties")
        if isinstance(properties, dict):
            node["required"] = list(properties)
            node["additionalProperties"] = False
        node.pop("default", None)
        for value in node.values():
            _make_schema_strict(value)
    elif isinstance(node, list):
        for value in node:
            _make_schema_strict(value)


def json_schema_response_format(
    model: type[BaseModel], *, name: str
) -> dict[str, Any]:
    """生成 OpenAI 兼容 structured outputs 的 response_format。"""
    schema = model.model_json_schema()
    _make_schema_strict(schema)
    return {
        "type": "json_schema",
        "json_schema": {
            "name": name,
            "strict": True,
            "schema": schema,
        },
    }


@dataclass
class SourceRecord:
    """一条搜索来源及其在使用过程中的状态。"""

    source_id: str
    round: int
    url: str
    title: str
    query: str
    content_hash: str
    content: str
    origin: str = ""
    depth: int = 0
    parent_url: str = ""
    original_url: str = ""
    fetched: bool = True
    used: bool = False
    cited: bool = False
    dropped_reason: str = ""
