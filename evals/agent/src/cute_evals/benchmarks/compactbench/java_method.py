from __future__ import annotations

import asyncio
import os
from typing import Any, ClassVar
from uuid import uuid4

try:
    from compactbench.compactors import Compactor, fit_to_budget
    from compactbench.contracts import CompactionArtifact, CompactionRequest
except ImportError as exc:  # pragma: no cover - exercised by the official runner
    raise RuntimeError(
        "CompactBench 0.3 source is required; PyPI 0.1.0 uses the obsolete protocol"
    ) from exc

from cute_evals.java_runner import run_java_compaction

_PREFIX = "[CONTEXT COMPACTION \u2014 REFERENCE ONLY]"
_END = (
    "--- END OF CONTEXT SUMMARY \u2014 respond to the message below, "
    "not the summary above ---"
)
_ARTIFACT_CHAR_LIMIT = 200000


def _message(role: str, content: str) -> dict[str, Any]:
    return {
        "role": role,
        "content": content,
    }


def _turn_message(turn: Any) -> dict[str, Any]:
    role = str(getattr(turn.role, "value", turn.role))
    content = str(turn.content)
    if role == "tool":
        name = str(getattr(turn, "name", None) or "tool")
        # CompactBench tool turns are not paired with an assistant tool_call.
        # Demote them to user-visible facts so Java's tool-pair sanitizer
        # cannot discard otherwise valid context.
        return _message("user", f"[tool:{name}]\n{content}")
    if role not in {"system", "assistant", "user"}:
        return _message("user", content)
    return _message(role, content)


def _previous_summary(previous: CompactionArtifact | None) -> str | None:
    if previous is None:
        return None
    parts = [previous.summary_text.strip()] if previous.summary_text.strip() else []
    state = previous.structured_state
    sections = (
        ("Immutable Facts", state.immutable_facts),
        ("Locked Decisions", state.locked_decisions),
        ("Deferred Items", state.deferred_items),
        ("Forbidden Behaviors", state.forbidden_behaviors),
        ("Unresolved Items", state.unresolved_items),
    )
    for heading, values in sections:
        if values:
            parts.append(
                f"## {heading}\n" + "\n".join(f"- {value}" for value in values)
            )
    if state.entity_map:
        parts.append(
            "## Entity Map\n"
            + "\n".join(f"- {key}: {value}" for key, value in state.entity_map.items())
        )
    return "\n\n".join(parts) if parts else None


def build_payload(request: CompactionRequest) -> dict[str, Any]:
    session_id = f"compactbench-{uuid4().hex}"
    messages: list[dict[str, Any]] = []
    previous = _previous_summary(request.previous_artifact)
    if previous is not None:
        messages.append(
            _message("user", f"{_PREFIX}\n{previous}\n\n{_END}")
        )
    messages.extend(_turn_message(turn) for turn in request.turns)
    return {
        "sessionId": session_id,
        "requestId": f"{session_id}-cycle-{request.cycle}",
        "messages": messages,
        "force": True,
        "budget": {
            "enabled": True,
            "llmEnabled": True,
            "microEnabled": False,
            "sessionMemoryEnabled": False,
            "thresholdPercent": 0.5,
            "keepMinTokens": 1,
            "keepMinTextMessages": 0,
            "keepMaxTokens": max(64, request.token_budget or 4096),
            "protectFirstN": 0,
            "protectLastN": 1,
            "summaryTargetRatio": 0.2,
            "summaryInputMaxChars": 200000,
            "summarizerTimeoutSeconds": 300,
        },
    }


class CuteAgentCompactor(Compactor):
    name: ClassVar[str] = "cute-agent-production-compactor"
    version: ClassVar[str] = "1.0.0"

    async def compact(self, request: CompactionRequest) -> CompactionArtifact:
        response = await asyncio.to_thread(
            run_java_compaction,
            build_payload(request),
            llm_model=os.environ.get("CUTE_EVAL_COMPACT_MODEL"),
            llm_base_url=os.environ.get("CUTE_EVAL_COMPACT_BASE_URL"),
            llm_api_key=os.environ.get("CUTE_EVAL_COMPACT_API_KEY"),
            llm_interface_url=os.environ.get("CUTE_EVAL_COMPACT_INTERFACE_URL"),
        )
        if not response.get("compactionTriggered"):
            raise RuntimeError(
                "Java production compactor did not trigger; "
                f"status={response.get('status')} strategy={response.get('strategy')}"
            )
        summary = str(response.get("summaryText") or "").strip()
        if not summary:
            raise RuntimeError(
                "Java production compactor returned no summary; "
                f"status={response.get('status')} strategy={response.get('strategy')}"
            )
        warnings: list[str] = []
        if len(summary) > _ARTIFACT_CHAR_LIMIT:
            summary = (
                summary[: _ARTIFACT_CHAR_LIMIT - 80].rstrip()
                + "\n[truncated to CompactBench artifact character limit]"
            )
            warnings.append("summary truncated to CompactBench artifact limit")
        strategy = str(response.get("strategy") or "unknown")
        if "fallback" in strategy or "drop-oldest" in strategy:
            reason = str(response.get("failureReason") or "").strip()
            warnings.append(
                f"Java compaction strategy: {strategy}"
                + (f"; reason: {reason[:300]}" if reason else "")
            )
        artifact = CompactionArtifact(
            summaryText=summary,
            warnings=warnings,
            methodMetadata={
                "javaRunner": "EvalCompactionCli",
                "eventId": response.get("eventId"),
                "strategy": strategy,
                "status": response.get("status"),
                "failureReason": response.get("failureReason"),
                "beforeTokens": response.get("beforeTokens"),
                "afterTokens": response.get("afterTokens"),
                "summaryHash": response.get("summaryHash"),
                "cycle": request.cycle,
            },
        )
        if request.token_budget is not None:
            artifact, trimmed = fit_to_budget(artifact, request.token_budget)
            if trimmed:
                artifact = artifact.model_copy(
                    update={"warnings": [*artifact.warnings, *trimmed][-50:]}
                )
        return artifact
