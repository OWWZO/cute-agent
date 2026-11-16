# -*- coding: utf-8 -*-
# =====================
#
# Author: liumin.423
# Date:   2025/7/8
# =====================
"""耗时日志工具：同步/异步上下文管理器 + 装饰器。"""

import asyncio
import functools
import inspect
import time
import traceback
from contextlib import contextmanager
from loguru import logger

from cute_tool.model.context import RequestIdCtx


class Timer(object):
    """同步代码块计时，日志带 RequestIdCtx。"""

    def __init__(self, key: str, level: str = "debug"):
        self.key = key
        self.level = level

    def __enter__(self):
        self.start_time = time.time()
        logger.log(self.level.upper(), f"{RequestIdCtx.request_id} {self.key} start...")
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if exc_type is not None:
            logger.error(
                f"{RequestIdCtx.request_id} {self.key} error={exc_type.__name__}: {exc_val}\n"
                f"{traceback.format_exc()}"
            )
        else:
            logger.log(
                self.level.upper(),
                f"{RequestIdCtx.request_id} {self.key} cost=[{int((time.time() - self.start_time) * 1000)} ms]",
            )


class AsyncTimer(object):
    """异步代码块计时。"""

    def __init__(self, key: str, level: str = "debug"):
        self.key = key
        self.level = level

    async def __aenter__(self):
        self.start_time = time.time()
        logger.log(self.level.upper(), f"{RequestIdCtx.request_id} {self.key} start...")
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        if exc_type is None:
            logger.log(
                self.level.upper(),
                f"{RequestIdCtx.request_id} {self.key} cost=[{int((time.time() - self.start_time) * 1000)} ms]",
            )
            return False
        # 客户端断开 / 外层 cancel scope / 生成器关闭：属正常中断，不要打 ERROR 堆栈
        if exc_type in (asyncio.CancelledError, GeneratorExit):
            logger.warning(
                f"{RequestIdCtx.request_id} {self.key} cancelled/closed "
                f"cost=[{int((time.time() - self.start_time) * 1000)} ms] type={exc_type.__name__}"
            )
            return False
        logger.error(
            f"{RequestIdCtx.request_id} {self.key} error={traceback.format_exc()}"
        )
        return False


@contextmanager
def stage_timer(scope: str, stage: str, request_id: str):
    """记录单个执行阶段的开始、结束和异常，便于定位无响应卡点。"""
    started_at = time.monotonic()
    logger.info(
        "[{}] request_id={} stage={} status=start",
        scope,
        request_id or "-",
        stage,
    )
    try:
        yield
    except BaseException as exc:
        elapsed_ms = int((time.monotonic() - started_at) * 1000)
        logger.warning(
            "[{}] request_id={} stage={} status=error elapsed_ms={} error={}: {}",
            scope,
            request_id or "-",
            stage,
            elapsed_ms,
            type(exc).__name__,
            exc,
        )
        raise
    else:
        elapsed_ms = int((time.monotonic() - started_at) * 1000)
        logger.info(
            "[{}] request_id={} stage={} status=ok elapsed_ms={}",
            scope,
            request_id or "-",
            stage,
            elapsed_ms,
        )


def timer(key: str = "", level: str = "debug"):
    """装饰器：自动适配同步函数 / 协程 / 异步生成器，统一打耗时日志。"""

    def decorator(func):
        if asyncio.iscoroutinefunction(func):

            @functools.wraps(func)
            async def wrapper(*args, **kwargs):
                async with AsyncTimer(f"{key} {func.__name__}", level=level):
                    result = await func(*args, **kwargs)
                return result

            return wrapper
        elif inspect.isasyncgenfunction(func):

            @functools.wraps(func)
            async def wrapper(*args, **kwargs):
                async with AsyncTimer(f"{key} {func.__name__}", level=level):
                    async for element in func(*args, **kwargs):
                        yield element

            return wrapper
        else:

            @functools.wraps(func)
            def wrapper(*args, **kwargs):
                with Timer(f"{key} {func.__name__}", level=level):
                    result = func(*args, **kwargs)
                return result

            return wrapper

    return decorator


if __name__ == "__main__":
    pass
