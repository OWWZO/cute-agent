from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..models import RunTrace


@dataclass(slots=True)
class AgentRun:
    agent: str
    case_id: str
    trace: RunTrace
    trace_path: Path
    workspace_path: Path
    workspace_hash: str
    agent_version: str | None
    adapter_metadata: dict[str, Any] = field(default_factory=dict)
