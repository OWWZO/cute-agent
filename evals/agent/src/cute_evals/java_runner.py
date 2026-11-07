from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import jsonschema

from .models import EvalCase, RunTrace

REPO_ROOT = Path(__file__).resolve().parents[4]
TRACE_SCHEMA_PATH = (
    Path(__file__).resolve().parents[2] / "schemas" / "trace.schema.json"
)


class EvalRunnerError(RuntimeError):
    """Java runner failure with a stable category for reports and gates."""

    def __init__(self, category: str, message: str) -> None:
        super().__init__(message)
        self.category = category


@dataclass(slots=True)
class JavaConversationResult:
    task_id: str
    conversation_id: str
    status: str
    final_text: str
    turns: list[dict]
    traces: list[RunTrace]
    trace_paths: list[str]
    error: str | None = None


def _maven_command() -> str:
    return shutil.which("mvn.cmd") or shutil.which("mvn") or "mvn"


def _repo_root() -> Path:
    override = os.environ.get("CUTE_EVAL_REPO_ROOT")
    return Path(override).resolve() if override else REPO_ROOT


def _run(command: list[str], timeout: int) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env.setdefault("MAVEN_OPTS", "-Dfile.encoding=UTF-8")
    try:
        return subprocess.run(
            command,
            cwd=_repo_root(),
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            timeout=timeout,
            env=env,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise EvalRunnerError(
            "timeout",
            f"Java eval runner exceeded {timeout}s: {' '.join(command)}",
        ) from exc


def compile_java_fixture_runner() -> None:
    install = _run(
        [
            _maven_command(),
            "-q",
            "-pl",
            "Cute-agent-app",
            "-am",
            "install",
            "-Dmaven.test.skip=true",
        ],
        timeout=600,
    )
    if install.returncode != 0:
        raise EvalRunnerError(
            "adapter",
            "Java eval runner dependency install failed\n"
            f"stdout:\n{install.stdout[-4000:]}\n"
            f"stderr:\n{install.stderr[-4000:]}"
        )
    result = _run(
        [
            _maven_command(),
            "-q",
            "-pl",
            "Cute-agent-app",
            "test-compile",
            "-DskipTests=true",
        ],
        timeout=600,
    )
    if result.returncode != 0:
        raise EvalRunnerError(
            "adapter",
            "Java eval runner test compilation failed\n"
            f"stdout:\n{result.stdout[-4000:]}\n"
            f"stderr:\n{result.stderr[-4000:]}"
        )


def export_ledger_traces(
    output_dir: Path,
    *,
    run_id: str | None = None,
    session_id: str | None = None,
    from_time: str | None = None,
    to_time: str | None = None,
    limit: int = 50,
    statuses: list[str] | None = None,
    spring_profile: str = "eval",
    build: bool = True,
    timeout_seconds: int = 600,
) -> dict:
    run_id = run_id.strip() if run_id and run_id.strip() else None
    session_id = session_id.strip() if session_id and session_id.strip() else None
    from_time = from_time.strip() if from_time and from_time.strip() else None
    to_time = to_time.strip() if to_time and to_time.strip() else None
    if (from_time is None) != (to_time is None):
        raise ValueError("from/to must be provided together")
    selectors = sum(
        value is not None
        for value in (run_id, session_id, from_time)
    )
    if selectors != 1:
        raise ValueError("choose exactly one: run-id, session-id, or from/to")
    if any(" " in value for value in (run_id, session_id) if value):
        raise ValueError("run-id/session-id must not contain spaces")
    if from_time and to_time:
        start = datetime.fromisoformat(from_time)
        end = datetime.fromisoformat(to_time)
        if start >= end:
            raise ValueError("from must be earlier than to")
    if limit < 1 or limit > 500:
        raise ValueError("limit must be between 1 and 500")
    if spring_profile.lower() in {"dev", "prod"}:
        raise ValueError("ledger export refuses dev/prod Spring profiles")
    normalized_statuses = []
    for status in statuses or []:
        name = status.strip().upper()
        if name not in {"SUCCESS", "FAILED", "TIMEOUT", "STOPPED"}:
            raise ValueError(f"unsupported ledger export status: {status}")
        if name not in normalized_statuses:
            normalized_statuses.append(name)
    if build:
        compile_java_fixture_runner()

    with tempfile.TemporaryDirectory(prefix="cute-evals-ledger-") as tmp:
        java_out = Path(tmp) / "out"
        exec_args = f"--out {java_out} --limit {limit} --spring-profile {spring_profile}"
        if normalized_statuses:
            exec_args += f" --status {','.join(normalized_statuses)}"
        if run_id:
            exec_args += f" --run-id {run_id}"
        elif session_id:
            exec_args += f" --session-id {session_id}"
        else:
            exec_args += f" --from {from_time} --to {to_time}"
        result = _run(
            [
                _maven_command(),
                "-q",
                "-pl",
                "Cute-agent-app",
                "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
                "-Dexec.classpathScope=test",
                "-Dexec.mainClass=org.wwz.ai.test.domain.EvalTraceExportCli",
                f"-Dexec.args={exec_args}",
            ],
            timeout=timeout_seconds,
        )
        if result.returncode != 0:
            raise EvalRunnerError(
                "adapter",
                "Java ledger export failed\n"
                f"stdout:\n{result.stdout[-4000:]}\n"
                f"stderr:\n{result.stderr[-4000:]}",
            )
        manifest_path = java_out / "export-manifest.json"
        if not manifest_path.exists():
            raise EvalRunnerError(
                "adapter",
                f"Java ledger export did not produce manifest: {manifest_path}",
            )
        output_dir.mkdir(parents=True, exist_ok=True)
        shutil.copytree(java_out, output_dir, dirs_exist_ok=True)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        schema = json.loads(TRACE_SCHEMA_PATH.read_text(encoding="utf-8"))
        for relative in manifest.get("traces") or []:
            payload = json.loads((output_dir / relative).read_text(encoding="utf-8"))
            jsonschema.validate(payload, schema)
        return manifest


def run_java_cases(
    cases: list[EvalCase],
    output_dir: Path,
    *,
    mode: str = "fixture",
    profile: str = "pr",
    model_id: str | None = None,
    build: bool = True,
    timeout_seconds: int = 600,
    runner_class: str | None = None,
    workspace_roots: dict[str, Path] | None = None,
    tool_mode: str = "ephemeral",
    tool_endpoint: str | None = None,
) -> dict[str, RunTrace]:
    if build:
        compile_java_fixture_runner()

    # Maven exec 会重新解析参数中的空格；临时目录保持无空格，避免 Windows 路径被拆开。
    temp_root = os.environ.get("CUTE_EVAL_JAVA_TMP")
    with tempfile.TemporaryDirectory(
        prefix="cute-evals-",
        dir=temp_root if temp_root else None,
    ) as tmp:
        stage = Path(tmp)
        manifest = {
            "schemaVersion": "1.0",
            "runner": "cute-agent-java-fixture",
            "environment": {
                "profile": profile,
                "mode": mode,
                "toolMode": tool_mode,
                "toolEndpoint": tool_endpoint,
                "modelId": (
                    model_id
                    or (
                        "eval-fixture-model"
                        if mode == "fixture"
                        else "configured-live-model"
                    )
                ),
            },
            "cases": [
                {
                    "id": case.id,
                    "query": case.query,
                    "script": case.script,
                    "tools": case.tools,
                    "maxSteps": case.expected.max_steps,
                    "maxWallMs": case.budgets.max_wall_ms,
                    "maxTotalTokens": case.budgets.max_total_tokens,
                    "maxToolCalls": case.budgets.max_tool_calls,
                    "answerContract": case.answer_contract,
                    "toolMode": tool_mode,
                    "toolEndpoint": tool_endpoint,
                    "workspaceRoot": (
                        str(workspace_roots[case.id].resolve())
                        if workspace_roots and case.id in workspace_roots
                        else None
                    ),
                }
                for case in cases
            ],
        }
        manifest_path = stage / "cases.json"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        java_out = stage / "out"
        main_class = runner_class or (
            "org.wwz.ai.test.domain.EvalLiveRunnerCli"
            if mode == "live"
            else "org.wwz.ai.test.domain.EvalFixtureRunnerCli"
        )
        exec_args = f"--cases {manifest_path} --out {java_out}"
        if mode == "live":
            exec_args += f" --spring-profile eval --eval-profile {profile}"
        result = _run(
            [
                _maven_command(),
                "-q",
                "-pl",
                "Cute-agent-app",
                "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
                "-Dexec.classpathScope=test",
                f"-Dexec.mainClass={main_class}",
                f"-Dexec.args={exec_args}",
            ],
            timeout=timeout_seconds,
        )
        if result.returncode != 0:
            raise EvalRunnerError(
                "adapter",
                "Java eval runner failed\n"
                f"stdout:\n{result.stdout[-4000:]}\n"
                f"stderr:\n{result.stderr[-4000:]}"
            )

        output_dir.mkdir(parents=True, exist_ok=True)
        traces: dict[str, RunTrace] = {}
        result_path = java_out / "result.json"
        if not result_path.exists():
            raise EvalRunnerError(
                "adapter",
                f"Java eval runner did not produce result.json: {result_path}",
            )
        try:
            result_payload = json.loads(result_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise EvalRunnerError(
                "adapter", f"invalid Java result.json: {exc}"
            ) from exc
        schema = json.loads(TRACE_SCHEMA_PATH.read_text(encoding="utf-8"))
        for item in result_payload["cases"]:
            relative = str(item["tracePath"])
            source = java_out / relative
            if not source.exists():
                raise EvalRunnerError("adapter", f"missing trace: {source}")
            target = output_dir / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            trace_payload = json.loads(source.read_text(encoding="utf-8"))
            try:
                jsonschema.validate(trace_payload, schema)
            except jsonschema.ValidationError as exc:
                raise EvalRunnerError(
                    "adapter",
                    f"trace schema validation failed for {item['caseId']}: {exc.message}",
                ) from exc
            traces[str(item["caseId"])] = RunTrace.from_dict(trace_payload)

        target_result = output_dir / "result.json"
        shutil.copy2(java_out / "result.json", target_result)
        return traces


def run_java_conversation(
    case: EvalCase,
    turns: list[str],
    output_dir: Path,
    *,
    mode: str = "live",
    profile: str = "nightly",
    model_id: str | None = None,
    tool_mode: str = "ephemeral",
    tool_endpoint: str | None = None,
    workspace_root: Path | None = None,
    user_simulator_endpoint: str | None = None,
    initial_user_message: str | None = None,
    max_turns: int = 20,
    build: bool = True,
    timeout_seconds: int = 600,
) -> JavaConversationResult:
    if mode != "live":
        raise ValueError("multi-turn Java conversations require live mode")
    if not turns and not (user_simulator_endpoint and initial_user_message):
        raise ValueError(
            "multi-turn conversation requires turns or a user simulator endpoint"
        )
    if build:
        compile_java_fixture_runner()

    temp_root = os.environ.get("CUTE_EVAL_JAVA_TMP")
    with tempfile.TemporaryDirectory(
        prefix="cute-evals-conversation-",
        dir=temp_root if temp_root else None,
    ) as tmp:
        stage = Path(tmp)
        manifest = {
            "schemaVersion": "1.0",
            "runner": "cute-agent-java-conversation",
            "environment": {
                "profile": profile,
                "mode": mode,
                "modelId": model_id or "configured-live-model",
                "toolMode": tool_mode,
                "toolEndpoint": tool_endpoint,
            },
            "cases": [
                {
                    "id": case.id,
                    "turns": (
                        [
                            {"query": query, "maxSteps": case.expected.max_steps}
                            for query in turns
                        ]
                        if turns
                        else []
                    ),
                    "tools": case.tools,
                    "maxSteps": case.expected.max_steps,
                    "maxTurns": max_turns,
                    "toolMode": tool_mode,
                    "toolEndpoint": tool_endpoint,
                    "userSimulatorEndpoint": user_simulator_endpoint,
                    "initialUserMessage": initial_user_message,
                    "workspaceRoot": (
                        str(workspace_root.resolve())
                        if workspace_root is not None
                        else None
                    ),
                }
            ],
        }
        manifest_path = stage / "cases.json"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        java_out = stage / "out"
        exec_args = (
            f"--cases {manifest_path} --out {java_out} "
            f"--spring-profile eval --eval-profile {profile}"
        )
        result = _run(
            [
                _maven_command(),
                "-q",
                "-pl",
                "Cute-agent-app",
                "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
                "-Dexec.classpathScope=test",
                "-Dexec.mainClass=org.wwz.ai.test.domain.EvalBenchmarkLiveRunnerCli",
                f"-Dexec.args={exec_args}",
            ],
            timeout=timeout_seconds,
        )
        if result.returncode != 0:
            raise EvalRunnerError(
                "adapter",
                "Java conversation runner failed\n"
                f"stdout:\n{result.stdout[-4000:]}\n"
                f"stderr:\n{result.stderr[-4000:]}",
            )
        result_path = java_out / "result.json"
        if not result_path.exists():
            raise EvalRunnerError(
                "adapter",
                f"Java conversation runner did not produce result.json: {result_path}",
            )
        payload = json.loads(result_path.read_text(encoding="utf-8"))
        rows = payload.get("cases") or []
        if len(rows) != 1:
            raise EvalRunnerError(
                "adapter",
                f"Java conversation runner returned {len(rows)} cases, expected 1",
            )
        row = rows[0]
        output_dir.mkdir(parents=True, exist_ok=True)
        shutil.copytree(java_out, output_dir, dirs_exist_ok=True)
        schema = json.loads(TRACE_SCHEMA_PATH.read_text(encoding="utf-8"))
        traces: list[RunTrace] = []
        trace_paths = list(row.get("tracePaths") or [])
        for relative in trace_paths:
            trace_path = java_out / relative
            if not trace_path.exists():
                raise EvalRunnerError("adapter", f"missing conversation trace: {trace_path}")
            trace_payload = json.loads(trace_path.read_text(encoding="utf-8"))
            try:
                jsonschema.validate(trace_payload, schema)
            except jsonschema.ValidationError as exc:
                raise EvalRunnerError(
                    "adapter",
                    f"trace schema validation failed for {case.id}: {exc.message}",
                ) from exc
            traces.append(RunTrace.from_dict(trace_payload))
        return JavaConversationResult(
            task_id=str(row.get("caseId") or case.id),
            conversation_id=str(row.get("conversationId") or ""),
            status=str(row.get("status") or "UNKNOWN"),
            final_text=str(row.get("finalText") or ""),
            turns=list(row.get("turns") or []),
            traces=traces,
            trace_paths=trace_paths,
            error=row.get("error"),
        )
