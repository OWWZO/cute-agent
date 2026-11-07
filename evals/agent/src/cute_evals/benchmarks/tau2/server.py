from __future__ import annotations

import argparse
import json
import sys
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


def _json_default(value: Any) -> Any:
    if hasattr(value, "model_dump"):
        return value.model_dump(mode="json")
    return str(value)


class Tau2Runtime:
    def __init__(self, source: Path, domain: str) -> None:
        sys.path.insert(0, str(source / "src"))
        from tau2.data_model.message import AssistantMessage, ToolMessage, UserMessage
        from tau2.data_model.message import ToolCall as TauToolCall
        from tau2.data_model.simulation import SimulationRun, TerminationReason
        from tau2.data_model.tasks import Task
        from tau2.evaluator.evaluator import EvaluationType, evaluate_simulation
        from tau2.orchestrator.orchestrator import DEFAULT_FIRST_AGENT_MESSAGE
        from tau2.registry import registry
        from tau2.user.user_simulator import UserSimulator

        self.AssistantMessage = AssistantMessage
        self.TauToolCall = TauToolCall
        self.ToolMessage = ToolMessage
        self.UserMessage = UserMessage
        self.SimulationRun = SimulationRun
        self.TerminationReason = TerminationReason
        self.Task = Task
        self.EvaluationType = EvaluationType
        self.evaluate_simulation = evaluate_simulation
        self.DEFAULT_FIRST_AGENT_MESSAGE = DEFAULT_FIRST_AGENT_MESSAGE
        self.UserSimulator = UserSimulator
        self.registry = registry
        self.domain = domain
        self.source = source
        self.environment = None
        self.task = None
        self.user_simulator = None
        self.user_state = None
        self._load_task_set()

    def _load_task_set(self) -> None:
        module_name = f"tau2.domains.{self.domain}.environment"
        module = __import__(module_name, fromlist=["get_tasks", "get_environment"])
        self.environment_constructor = module.get_environment
        self.available_tasks = {task.id: task for task in module.get_tasks(None)}

    def reset(self, task_id: str) -> dict[str, Any]:
        task = self.available_tasks.get(str(task_id))
        if task is None:
            raise ValueError(f"tau2 task not found: {task_id}")
        self.task = task
        self.environment = self.environment_constructor()
        self.user_simulator = None
        self.user_state = None
        initial = task.initial_state
        self.environment.set_state(
            None if initial is None else initial.initialization_data,
            None if initial is None else initial.initialization_actions,
            [] if initial is None or initial.message_history is None else initial.message_history,
            strict=True,
        )
        return {
            "taskId": task.id,
            "domain": self.domain,
            "dbHash": self.environment.get_db_hash(),
            "policy": self.environment.get_policy(),
        }

    def start_user(self, model: str, llm_args: dict[str, Any]) -> dict[str, Any]:
        if self.task is None or self.environment is None:
            raise ValueError("task is not reset")
        tools = None
        try:
            tools = self.environment.get_user_tools(include=self.task.user_tools)
        except ValueError:
            pass
        self.user_simulator = self.UserSimulator(
            llm=model,
            instructions=str(self.task.user_scenario),
            tools=tools or None,
            llm_args=llm_args,
        )
        self.user_state = self.user_simulator.get_init_state()
        message, self.user_state = self.user_simulator.generate_next_message(
            self.DEFAULT_FIRST_AGENT_MESSAGE,
            self.user_state,
        )
        return self._user_result(message)

    def next_user(self, assistant_message: str) -> dict[str, Any]:
        if self.user_simulator is None or self.user_state is None:
            raise ValueError("user simulator is not started")
        message, self.user_state = self.user_simulator.generate_next_message(
            self.AssistantMessage.text(assistant_message),
            self.user_state,
        )
        return self._user_result(message)

    def _user_result(self, message: Any) -> dict[str, Any]:
        done = self.UserSimulator.is_stop(message)
        return {
            "message": "" if done else (message.content or ""),
            "done": done,
            "reason": "user_stop" if done else "in_progress",
            "usage": message.usage,
            "cost": message.cost,
        }

    def tools(self) -> list[dict[str, Any]]:
        if self.environment is None:
            raise ValueError("environment is not reset")
        definitions = []
        for tool in self.environment.get_tools():
            payload = tool.openai_schema
            definitions.append(payload.get("function", payload))
        return definitions

    def call(self, tool_name: str, arguments: dict[str, Any]) -> Any:
        if self.environment is None:
            raise ValueError("environment is not reset")
        return self.environment.make_tool_call(
            tool_name,
            requestor="assistant",
            **arguments,
        )

    def snapshot(self) -> dict[str, Any]:
        if self.environment is None:
            raise ValueError("environment is not reset")
        return {
            "taskId": self.task.id,
            "dbHash": self.environment.get_db_hash(),
        }

    def evaluate(self, payload: dict[str, Any]) -> dict[str, Any]:
        if self.task is None:
            raise ValueError("task is not reset")
        tools_by_turn: dict[str, list[dict[str, Any]]] = {}
        for call in payload.get("toolInvocations") or []:
            tools_by_turn.setdefault(str(call.get("turnId") or ""), []).append(call)
        messages = []
        conversation = payload.get("conversation") or []
        for item in conversation:
            role = item.get("role")
            if role == "user":
                messages.append(self.UserMessage.text(str(item.get("content") or "")))
                continue
            if role != "assistant":
                continue
            turn_id = str(item.get("turnId") or "")
            turn_calls = tools_by_turn.get(turn_id, [])
            if turn_calls:
                tau_calls = [
                    self.TauToolCall(
                        id=str(call.get("toolCallId") or call.get("id") or ""),
                        name=str(call.get("toolName") or ""),
                        arguments=_parse_arguments(call.get("inputJson")),
                        requestor="assistant",
                    )
                    for call in turn_calls
                ]
                messages.append(
                    self.AssistantMessage.text("", tool_calls=tau_calls)
                )
                for call in turn_calls:
                    messages.append(
                        self.ToolMessage(
                            id=str(call.get("toolCallId") or call.get("id") or ""),
                            role="tool",
                            content=str(call.get("toolResult") or ""),
                            requestor="assistant",
                            error=str(call.get("status") or "").upper() != "SUCCESS",
                        )
                    )
            messages.append(
                self.AssistantMessage.text(str(item.get("content") or ""))
            )
        now = datetime.now(UTC).isoformat()
        simulation = self.SimulationRun(
            id=str(payload.get("conversationId") or "cute-eval"),
            task_id=self.task.id,
            start_time=now,
            end_time=now,
            duration=0.0,
            termination_reason=self.TerminationReason.USER_STOP,
            messages=messages,
        )
        reward = self.evaluate_simulation(
            simulation=simulation,
            task=self.task,
            evaluation_type=self.EvaluationType.ALL,
            solo_mode=False,
            domain=self.domain,
        )
        return reward.model_dump(mode="json")


def _parse_arguments(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if value is None:
        return {}
    try:
        parsed = json.loads(str(value))
    except json.JSONDecodeError:
        return {"_raw": str(value)}
    return parsed if isinstance(parsed, dict) else {"_value": parsed}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--domain", default="airline")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()
    runtime = Tau2Runtime(Path(args.source).resolve(), args.domain)

    class Handler(BaseHTTPRequestHandler):
        def _body(self) -> dict[str, Any]:
            length = int(self.headers.get("Content-Length") or 0)
            return json.loads(self.rfile.read(length).decode("utf-8") or "{}")

        def _send(self, status: int, payload: dict[str, Any]) -> None:
            encoded = json.dumps(
                payload,
                ensure_ascii=False,
                default=_json_default,
            ).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self) -> None:
            if self.path == "/health":
                self._send(200, {"ok": True})
                return
            self._send(404, {"ok": False, "error": "not found"})

        def do_POST(self) -> None:
            try:
                body = self._body()
                if self.path == "/reset":
                    result = runtime.reset(str(body.get("taskId") or ""))
                elif self.path == "/tools":
                    result = runtime.tools()
                elif self.path == "/call":
                    result = runtime.call(
                        str(body.get("toolName") or ""),
                        dict(body.get("arguments") or {}),
                    )
                elif self.path == "/snapshot":
                    result = runtime.snapshot()
                elif self.path == "/evaluate":
                    result = runtime.evaluate(body)
                elif self.path == "/user/start":
                    result = runtime.start_user(
                        str(body.get("model") or ""),
                        dict(body.get("llmArgs") or {}),
                    )
                elif self.path == "/user/next":
                    result = runtime.next_user(str(body.get("assistantMessage") or ""))
                else:
                    self._send(404, {"ok": False, "error": "not found"})
                    return
                self._send(200, {"ok": True, "result": result})
            except Exception as exc:  # noqa: BLE001 - HTTP boundary reports failures
                self._send(500, {"ok": False, "error": str(exc)})

        def log_message(self, format: str, *args: Any) -> None:
            return

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(json.dumps({"port": server.server_address[1]}), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
