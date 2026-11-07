from __future__ import annotations

import json
import os
import time
from collections.abc import Callable, Mapping
from datetime import datetime, timedelta
from datetime import time as clock_time
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

SCHEDULER_SCHEMA_VERSION = "1.0"
DB_ENV_VARS = (
    "CUTE_EVAL_DB_URL",
    "CUTE_EVAL_DB_USER",
    "CUTE_EVAL_DB_PASSWORD",
)


def parse_daily_time(value: str) -> clock_time:
    parts = value.strip().split(":")
    if len(parts) not in {2, 3}:
        raise ValueError("schedule time must use HH:MM or HH:MM:SS")
    try:
        numbers = [int(part) for part in parts]
    except ValueError as exc:
        raise ValueError("schedule time must contain integers") from exc
    if len(numbers) == 2:
        numbers.append(0)
    hour, minute, second = numbers
    if not (0 <= hour <= 23 and 0 <= minute <= 59 and 0 <= second <= 59):
        raise ValueError("schedule time is outside the valid clock range")
    return clock_time(hour, minute, second)


def next_daily_run(now: datetime, at: clock_time, timezone: str) -> datetime:
    tz = ZoneInfo(timezone)
    local_now = now.astimezone(tz)
    candidate = datetime.combine(local_now.date(), at, tzinfo=tz)
    if candidate <= local_now:
        candidate += timedelta(days=1)
    return candidate


def require_eval_db_env(env: Mapping[str, str] | None = None) -> None:
    source = env if env is not None else os.environ
    missing = []
    for name in DB_ENV_VARS:
        if name not in source or (
            name != "CUTE_EVAL_DB_PASSWORD" and not str(source[name]).strip()
        ):
            missing.append(name)
    if missing:
        raise ValueError(
            "scheduled ledger sampling requires explicit eval database environment: "
            + ", ".join(missing)
        )


def wait_until(timestamp: float, *, check_interval_seconds: float = 30.0) -> None:
    while True:
        remaining = timestamp - time.time()
        if remaining <= 0:
            return
        time.sleep(min(check_interval_seconds, max(0.1, remaining)))


def write_scheduler_state(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    temporary.replace(path)


def run_daily_scheduler(
    *,
    at: clock_time,
    timezone: str,
    out_root: Path,
    run_once: Callable[[Path], dict[str, Any]],
    run_now: bool = False,
    clock: Callable[[], datetime] | None = None,
    wait: Callable[[float], None] = wait_until,
) -> None:
    out_root = out_root.resolve()
    out_root.mkdir(parents=True, exist_ok=True)
    state_path = out_root / "scheduler-state.json"
    state: dict[str, Any] = {
        "schemaVersion": SCHEDULER_SCHEMA_VERSION,
        "pid": os.getpid(),
        "timezone": timezone,
        "at": at.isoformat(timespec="minutes"),
        "outRoot": str(out_root),
        "status": "starting",
        "lastStartedAt": None,
        "lastFinishedAt": None,
        "lastSuccessAt": None,
        "lastError": None,
        "lastOutput": None,
        "nextRunAt": None,
    }
    get_now = clock or (lambda: datetime.now())

    def execute(started_at: datetime) -> None:
        state["status"] = "running"
        state["lastStartedAt"] = started_at.isoformat(timespec="seconds")
        state["nextRunAt"] = None
        state["lastError"] = None
        write_scheduler_state(state_path, state)
        output_name = started_at.astimezone(ZoneInfo("UTC")).strftime(
            "%Y-%m-%d-%H%M%SZ"
        )
        output_dir = out_root / output_name
        try:
            result = run_once(output_dir)
            finished_at = get_now()
            state["status"] = "ok"
            state["lastFinishedAt"] = finished_at.isoformat(timespec="seconds")
            state["lastSuccessAt"] = state["lastFinishedAt"]
            state["lastOutput"] = str(output_dir)
            state["lastError"] = None
            state["lastResult"] = result
            write_scheduler_state(state_path, state)
        except Exception as exc:  # noqa: BLE001 - scheduler must stay alive for the next run
            finished_at = get_now()
            state["status"] = "error"
            state["lastFinishedAt"] = finished_at.isoformat(timespec="seconds")
            state["lastOutput"] = str(output_dir)
            state["lastError"] = f"{type(exc).__name__}: {exc}"
            write_scheduler_state(state_path, state)

    if run_now:
        execute(get_now())

    while True:
        current = get_now()
        next_run = next_daily_run(current, at, timezone)
        state["status"] = "waiting"
        state["nextRunAt"] = next_run.isoformat(timespec="seconds")
        write_scheduler_state(state_path, state)
        wait(next_run.timestamp())
        execute(get_now())
