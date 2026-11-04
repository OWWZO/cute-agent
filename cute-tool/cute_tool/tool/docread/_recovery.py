# -*- coding: utf-8 -*-
"""Recover tool-call arguments when large data/content breaks JSON escaping."""
import json
import re
from typing import Any

_STRING_FIELD = re.compile(
    r'"(?P<key>operation|file_path|data|content)"\s*:\s*"(?P<value>(?:\\.|[^"\\])*)"',
    re.DOTALL,
)


def recover_doc_tool_args(raw: str, *, content_key: str) -> dict[str, Any] | None:
    """在大文本破坏完整 JSON 时，尽可能恢复可执行的工具参数。"""
    if not raw or not raw.strip():
        return None
    out: dict[str, Any] = {}
    for m in _STRING_FIELD.finditer(raw):
        key = m.group("key")
        val = m.group("value")
        try:
            # \uXXXX 必须按 JSON 的 Unicode 转义解码，unicode_escape 会把
            # UTF-8 字节重新按 Latin-1 解释成乱码。
            decoded = json.loads(f'"{val}"')
        except (UnicodeDecodeError, ValueError):
            decoded = val.replace("\\n", "\n").replace("\\t", "\t").replace('\\"', '"')
        if key == "operation":
            out["operation"] = decoded
        elif key == "file_path":
            out["file_path"] = decoded
        elif key in ("data", "content"):
            if key == content_key:
                out[key] = decoded
    if content_key not in out and "operation" not in out and "file_path" not in out:
        return None
    if "operation" not in out:
        # 只有路径/内容存在时才默认写入操作，避免把不完整片段误判成可执行请求。
        out.setdefault("operation", "write")
    return out
