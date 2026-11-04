"""Minimal structlog-compatible facade backed by loguru."""
from __future__ import annotations

from typing import Any

from loguru import logger as _loguru


class _BoundLogger:
    """轻量日志门面，保留 bind 调用形状，但不保存或输出绑定上下文。"""

    def __init__(self, name: str = ""):
        self._name = name

    def bind(self, **kwargs: Any) -> "_BoundLogger":
        """兼容 structlog API；绑定字段当前不会写入日志。"""
        # 当前兼容层不保存绑定上下文，只保证调用方无需依赖完整 structlog。
        return self

    def _fmt(self, event: str, kwargs: dict[str, Any]) -> str:
        # 所有级别共用同一格式，便于从工具日志中检索事件名和键值。
        base = f"[{self._name}] {event}" if self._name else event
        if not kwargs:
            return base
        parts = " ".join(f"{k}={v!r}" for k, v in kwargs.items())
        return f"{base} {parts}"

    def debug(self, event: str, **kwargs: Any) -> None:
        _loguru.opt(lazy=True).debug("{}", lambda: self._fmt(event, kwargs))

    def info(self, event: str, **kwargs: Any) -> None:
        _loguru.opt(lazy=True).info("{}", lambda: self._fmt(event, kwargs))

    def warning(self, event: str, **kwargs: Any) -> None:
        _loguru.opt(lazy=True).warning("{}", lambda: self._fmt(event, kwargs))

    def error(self, event: str, **kwargs: Any) -> None:
        _loguru.opt(lazy=True).error("{}", lambda: self._fmt(event, kwargs))

    def exception(self, event: str, **kwargs: Any) -> None:
        _loguru.opt(lazy=True).exception("{}", lambda: self._fmt(event, kwargs))


def get_logger(name: str | None = None) -> _BoundLogger:
    """返回带可选名称前缀的轻量 logger。"""
    return _BoundLogger(name or "")
