from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from .models import RunTrace
from .process_view import ProcessStep, project_process_view
from .trajectory_quality import _tool_failure_signal

MULTI_AGENT_VIEW_SCHEMA_VERSION = "1.0"


class MultiAgentViewError(ValueError):
    """Raised when agent ownership cannot be recovered without guessing."""


@dataclass(slots=True)
class AgentToolEvent:
    index: int
    tool_call_id: str | None
    tool_name: str
    status: str
    input_json: str | None
    error_msg: str | None
    duration_ms: int | None
    artifact_count: int | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "index": self.index,
            "toolCallId": self.tool_call_id,
            "toolName": self.tool_name,
            "status": self.status,
            "inputJson": self.input_json,
            "errorMsg": self.error_msg,
            "durationMs": self.duration_ms,
            "artifactCount": self.artifact_count,
        }


@dataclass(slots=True)
class AgentNode:
    agent_id: str
    parent_agent_id: str | None
    agent_type: str
    description: str | None
    steps: list[ProcessStep] = field(default_factory=list)
    tools: list[AgentToolEvent] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "agentId": self.agent_id,
            "parentAgentId": self.parent_agent_id,
            "agentType": self.agent_type,
            "description": self.description,
            "steps": [item.to_dict() for item in self.steps],
            "tools": [item.to_dict() for item in self.tools],
        }


@dataclass(slots=True)
class AgentHandoff:
    from_agent_id: str
    to_agent_id: str
    tool_call_id: str | None
    tool_name: str
    status: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "fromAgentId": self.from_agent_id,
            "toAgentId": self.to_agent_id,
            "toolCallId": self.tool_call_id,
            "toolName": self.tool_name,
            "status": self.status,
        }


@dataclass(slots=True)
class MultiAgentTraceView:
    run_id: str
    request_id: str
    session_id: str | None
    query: str
    status: str
    final_text: str
    agents: list[AgentNode]
    handoffs: list[AgentHandoff]
    metrics: dict[str, Any]
    source: dict[str, Any]
    schema_version: str = MULTI_AGENT_VIEW_SCHEMA_VERSION

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "runId": self.run_id,
            "requestId": self.request_id,
            "sessionId": self.session_id,
            "query": self.query,
            "status": self.status,
            "finalText": self.final_text,
            "agents": [item.to_dict() for item in self.agents],
            "handoffs": [item.to_dict() for item in self.handoffs],
            "metrics": self.metrics,
            "source": self.source,
        }


def _optional_int(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def project_multi_agent_view(
    trace: RunTrace | dict[str, Any],
) -> MultiAgentTraceView:
    """Project a ledger trace into a main/sub-agent ownership view."""
    run_trace = trace if isinstance(trace, RunTrace) else RunTrace.from_dict(trace)
    process_view = project_process_view(run_trace)
    main = AgentNode(
        agent_id="main",
        parent_agent_id=None,
        agent_type="main",
        description=None,
        steps=process_view.steps,
    )
    agents: dict[str, AgentNode] = {"main": main}
    order = ["main"]
    handoffs: list[AgentHandoff] = []
    for index, invocation in enumerate(run_trace.tool_invocations):
        if not isinstance(invocation, dict):
            raise MultiAgentViewError(
                f"tool invocation {index} is not an object"
            )
        sub_agent_id = str(invocation.get("subAgentId") or "").strip()
        agent_id = sub_agent_id or "main"
        failed = _tool_failure_signal(invocation) is not None
        reported_status = str(invocation.get("status") or "UNKNOWN").upper()
        tool_status = "FAILED" if failed else reported_status
        if agent_id not in agents:
            agent_type = str(
                invocation.get("subAgentType") or "subagent"
            ).strip()
            description = (
                str(invocation["subAgentDescription"]).strip()
                if invocation.get("subAgentDescription") is not None
                and str(invocation["subAgentDescription"]).strip()
                else None
            )
            agents[agent_id] = AgentNode(
                agent_id=agent_id,
                parent_agent_id="main",
                agent_type=agent_type,
                description=description,
            )
            order.append(agent_id)
            handoffs.append(
                AgentHandoff(
                    from_agent_id="main",
                    to_agent_id=agent_id,
                    tool_call_id=(
                        str(invocation["toolCallId"])
                        if invocation.get("toolCallId") is not None
                        else None
                    ),
                    tool_name=str(invocation.get("toolName") or ""),
                    status=tool_status,
                )
            )
        else:
            node = agents[agent_id]
            incoming_type = str(invocation.get("subAgentType") or "").strip()
            incoming_description = (
                str(invocation["subAgentDescription"]).strip()
                if invocation.get("subAgentDescription") is not None
                else ""
            )
            if sub_agent_id and incoming_type and node.agent_type != incoming_type:
                raise MultiAgentViewError(
                    f"sub-agent {agent_id} has conflicting types"
                )
            if (
                sub_agent_id
                and incoming_description
                and node.description
                and node.description != incoming_description
            ):
                raise MultiAgentViewError(
                    f"sub-agent {agent_id} has conflicting descriptions"
                )
        agents[agent_id].tools.append(
            AgentToolEvent(
                index=index,
                tool_call_id=(
                    str(invocation["toolCallId"])
                    if invocation.get("toolCallId") is not None
                    else None
                ),
                tool_name=str(invocation.get("toolName") or ""),
                status=tool_status,
                input_json=(
                    str(invocation["inputJson"])
                    if invocation.get("inputJson") is not None
                    else None
                ),
                error_msg=(
                    str(invocation["errorMsg"])
                    if invocation.get("errorMsg") is not None
                    else None
                ),
                duration_ms=_optional_int(invocation.get("durationMs")),
                artifact_count=_optional_int(invocation.get("artifactCount")),
            )
        )

    total_tools = sum(len(node.tools) for node in agents.values())
    successes = sum(
        1
        for node in agents.values()
        for tool in node.tools
        if tool.status.upper() == "SUCCESS"
    )
    execution_status = "FAILED" if any(
        tool.status == "FAILED" for node in agents.values() for tool in node.tools
    ) else run_trace.status
    return MultiAgentTraceView(
        run_id=run_trace.run_uid or run_trace.request_id,
        request_id=run_trace.request_id,
        session_id=process_view.session_id,
        query=process_view.query,
        status=execution_status,
        final_text=run_trace.final_text,
        agents=[agents[agent_id] for agent_id in order],
        handoffs=handoffs,
        metrics={
            "agentCount": len(agents),
            "subAgentCount": len(agents) - 1,
            "toolCount": total_tools,
            "toolSuccessRate": successes / total_tools if total_tools else None,
            "handoffCount": len(handoffs),
        },
        source={
            "schemaVersion": run_trace.raw.get("schemaVersion"),
            "producer": run_trace.raw.get("producer") or {},
            "conversationSource": process_view.source.get("conversationSource"),
            "parentRelationship": "implicit-main",
        },
    )


def multi_agent_view_json(view: MultiAgentTraceView) -> str:
    return json.dumps(view.to_dict(), ensure_ascii=False, indent=2)
