from __future__ import annotations

import os
import time as time_module
from datetime import datetime, time
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from cute_evals.ops_schedule import (
    next_daily_run,
    parse_daily_time,
    require_eval_db_env,
    run_daily_scheduler,
)


def test_parse_daily_time_and_rollover() -> None:
    at = parse_daily_time("02:30")
    timezone = "Asia/Shanghai"

    same_day = next_daily_run(
        datetime(2026, 11, 6, 1, 0, tzinfo=ZoneInfo(timezone)),
        at,
        timezone,
    )
    next_day = next_daily_run(
        datetime(2026, 11, 6, 3, 0, tzinfo=ZoneInfo(timezone)),
        at,
        timezone,
    )

    assert same_day == datetime(2026, 11, 6, 2, 30, tzinfo=ZoneInfo(timezone))
    assert next_day == datetime(2026, 11, 7, 2, 30, tzinfo=ZoneInfo(timezone))


def test_require_eval_db_env_requires_explicit_password_variable() -> None:
    with pytest.raises(ValueError, match="CUTE_EVAL_DB_PASSWORD"):
        require_eval_db_env(
            {
                "CUTE_EVAL_DB_URL": "jdbc:mysql://127.0.0.1/eval",
                "CUTE_EVAL_DB_USER": "readonly",
            }
        )

    require_eval_db_env(
        {
            "CUTE_EVAL_DB_URL": "jdbc:mysql://127.0.0.1/eval",
            "CUTE_EVAL_DB_USER": "readonly",
            "CUTE_EVAL_DB_PASSWORD": "",
        }
    )


def test_scheduler_runs_immediately_and_writes_state(tmp_path: Path) -> None:
    now = datetime(2026, 11, 6, 18, 0, tzinfo=ZoneInfo("UTC"))
    outputs = []

    def run_once(output_dir: Path) -> dict:
        outputs.append(output_dir)
        return {"scanned": 1}

    with pytest.raises(RuntimeError, match="stop"):
        run_daily_scheduler(
            at=time(2, 30),
            timezone="Asia/Shanghai",
            out_root=tmp_path,
            run_once=run_once,
            run_now=True,
            clock=lambda: now,
            wait=lambda _: (_ for _ in ()).throw(RuntimeError("stop")),
        )

    assert len(outputs) == 1
    state = (tmp_path / "scheduler-state.json").read_text(encoding="utf-8")
    assert '"lastSuccessAt": "2026-11-06T18:00:00+00:00"' in state
    assert '"status": "waiting"' in state


@pytest.mark.skipif(
    not hasattr(time_module, "tzset"),
    reason="time.tzset is unavailable on Windows",
)
def test_naive_clock_is_converted_from_the_system_timezone(monkeypatch) -> None:
    original = os.environ.get("TZ")
    monkeypatch.setenv("TZ", "UTC")
    time_module.tzset()
    try:
        result = next_daily_run(
            datetime(2026, 11, 6, 23, 30),
            time(6, 0),
            "Asia/Shanghai",
        )
    finally:
        if original is None:
            monkeypatch.delenv("TZ", raising=False)
        else:
            monkeypatch.setenv("TZ", original)
        time_module.tzset()

    assert result == datetime(
        2026,
        11,
        8,
        6,
        0,
        tzinfo=ZoneInfo("Asia/Shanghai"),
    )
