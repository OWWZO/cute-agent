from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Self


def _authorizer(action, arg1, arg2, db_name, trigger_name):
    del arg1, arg2, db_name, trigger_name
    allowed = {
        getattr(sqlite3, "SQLITE_SELECT", 21),
        getattr(sqlite3, "SQLITE_READ", 20),
        getattr(sqlite3, "SQLITE_FUNCTION", 31),
        getattr(sqlite3, "SQLITE_RECURSIVE", 33),
    }
    return sqlite3.SQLITE_OK if action in allowed else sqlite3.SQLITE_DENY


def validate_read_only_sql(sql: str) -> str:
    value = str(sql or "").strip()
    while value:
        if value.startswith("--"):
            newline = value.find("\n")
            value = value[newline + 1 :].lstrip() if newline >= 0 else ""
            continue
        if value.startswith("/*"):
            end = value.find("*/", 2)
            if end < 0:
                raise ValueError("unterminated SQL block comment")
            value = value[end + 2 :].lstrip()
            continue
        break
    if not re.match(r"(?is)^\s*(select|with)\b", value):
        raise ValueError("only a single read-only SELECT/CTE statement is allowed")
    _reject_multiple_statements(value)
    return value


def _reject_multiple_statements(statement: str) -> None:
    quote: str | None = None
    terminated = False
    index = 0
    while index < len(statement):
        char = statement[index]
        if quote is not None:
            if char == quote:
                if (
                    quote != "]"
                    and index + 1 < len(statement)
                    and statement[index + 1] == quote
                ):
                    index += 2
                    continue
                quote = None
            index += 1
            continue
        if char.isspace():
            index += 1
            continue
        if statement.startswith("--", index):
            newline = statement.find("\n", index + 2)
            if newline < 0:
                return
            index = newline + 1
            continue
        if statement.startswith("/*", index):
            end = statement.find("*/", index + 2)
            if end < 0:
                raise ValueError("unterminated SQL block comment")
            index = end + 2
            continue
        if terminated:
            raise ValueError("only a single read-only SELECT/CTE statement is allowed")
        if char in {"'", '"', "`"}:
            quote = char
        elif char == "[":
            quote = "]"
        elif char == ";":
            terminated = True
        index += 1


def read_only_query(
    database_path: Path,
    sql: str,
    *,
    timeout_seconds: float,
    max_rows: int | None = None,
) -> dict[str, Any]:
    statement = validate_read_only_sql(sql)
    connection = sqlite3.connect(
        database_path.resolve().as_uri() + "?mode=ro",
        uri=True,
    )
    deadline = time.monotonic() + timeout_seconds
    connection.set_progress_handler(
        lambda: 1 if time.monotonic() >= deadline else 0,
        10_000,
    )
    try:
        connection.execute("PRAGMA query_only=ON")
        connection.set_authorizer(_authorizer)
        cursor = connection.execute(statement)
        columns = [str(item[0]) for item in (cursor.description or [])]
        fetched = cursor.fetchmany(max_rows + 1) if max_rows is not None else cursor.fetchall()
        truncated = max_rows is not None and len(fetched) > max_rows
        visible = fetched[:max_rows] if max_rows is not None else fetched
        rows = [list(row) for row in visible]
        return {
            "columns": columns,
            "rows": rows,
            "rowCount": len(rows),
            "truncated": truncated,
        }
    finally:
        connection.close()


def result_hash(payload: dict[str, Any]) -> str:
    encoded = json.dumps(
        {
            "columns": payload.get("columns") or [],
            "rows": payload.get("rows") or [],
        },
        ensure_ascii=False,
        sort_keys=True,
        default=str,
        separators=(",", ":"),
    ).encode("utf-8")
    return f"sha256:{hashlib.sha256(encoded).hexdigest()}"


class SqliteRuntime:
    def __init__(
        self,
        database_path: Path,
        *,
        query_timeout_seconds: float,
        max_result_rows: int,
        tool_definitions: list[dict[str, Any]],
        runtime_name: str = "BIRD",
    ) -> None:
        self.database_path = database_path.resolve()
        self.query_timeout_seconds = query_timeout_seconds
        self.max_result_rows = max_result_rows
        self.tool_definitions = tool_definitions
        self.runtime_name = runtime_name

    def tools(self) -> list[dict[str, Any]]:
        return list(self.tool_definitions)

    def snapshot(self) -> dict[str, Any]:
        return {
            "database": self.database_path.name,
            "sha256": hashlib.sha256(self.database_path.read_bytes()).hexdigest(),
            "size": self.database_path.stat().st_size,
        }

    def call(self, tool_name: str, arguments: dict[str, Any]) -> Any:
        if tool_name == "list_tables":
            return self._list_tables()
        if tool_name == "describe_table":
            return self._describe_table(str(arguments.get("table_name") or ""))
        if tool_name == "sql_query":
            return read_only_query(
                self.database_path,
                str(arguments.get("sql") or ""),
                timeout_seconds=self.query_timeout_seconds,
                max_rows=self.max_result_rows,
            )
        if tool_name == "submit_sql":
            statement = validate_read_only_sql(str(arguments.get("sql") or ""))
            preview = read_only_query(
                self.database_path,
                statement,
                timeout_seconds=self.query_timeout_seconds,
                max_rows=1,
            )
            return {
                "submitted": True,
                "sql": statement,
                "preview": preview,
            }
        raise ValueError(f"unknown {self.runtime_name} tool: {tool_name}")

    def _list_tables(self) -> list[str]:
        connection = sqlite3.connect(
            self.database_path.as_uri() + "?mode=ro",
            uri=True,
        )
        try:
            return [
                str(row[0])
                for row in connection.execute(
                    "SELECT name FROM sqlite_master "
                    "WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' "
                    "ORDER BY name"
                )
            ]
        finally:
            connection.close()

    def _describe_table(self, table_name: str) -> dict[str, Any]:
        if not table_name:
            raise ValueError("table_name is required")
        connection = sqlite3.connect(
            self.database_path.as_uri() + "?mode=ro",
            uri=True,
        )
        try:
            exists = connection.execute(
                "SELECT 1 FROM sqlite_master "
                "WHERE type IN ('table', 'view') AND name = ?",
                (table_name,),
            ).fetchone()
            if exists is None:
                raise ValueError(f"table or view not found: {table_name}")
            quoted = table_name.replace('"', '""')
            rows = connection.execute(f'PRAGMA table_info("{quoted}")').fetchall()
            return {
                "table": table_name,
                "columns": [
                    {
                        "name": str(row[1]),
                        "type": str(row[2]),
                        "notNull": bool(row[3]),
                        "default": row[4],
                        "primaryKey": bool(row[5]),
                    }
                    for row in rows
                ],
            }
        finally:
            connection.close()


class SqliteTaskServer:
    def __init__(
        self,
        database_path: Path,
        *,
        query_timeout_seconds: float = 30,
        max_result_rows: int = 200,
        tool_definitions: list[dict[str, Any]],
        runtime_name: str = "BIRD",
    ) -> None:
        self.runtime = SqliteRuntime(
            database_path,
            query_timeout_seconds=query_timeout_seconds,
            max_result_rows=max_result_rows,
            tool_definitions=tool_definitions,
            runtime_name=runtime_name,
        )
        self.runtime_name = runtime_name
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        self.port: int | None = None

    @property
    def endpoint(self) -> str:
        if self.port is None:
            raise RuntimeError(f"{self.runtime_name} SQL task server is not started")
        return f"http://127.0.0.1:{self.port}"

    def start(self) -> Self:
        runtime = self.runtime

        class Handler(BaseHTTPRequestHandler):
            def _body(self) -> dict[str, Any]:
                length = int(self.headers.get("Content-Length") or 0)
                return json.loads(self.rfile.read(length).decode("utf-8") or "{}")

            def _send(self, status: int, payload: dict[str, Any]) -> None:
                encoded = json.dumps(
                    payload,
                    ensure_ascii=False,
                    default=str,
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
                        result = {"readOnly": True}
                    elif self.path == "/tools":
                        result = runtime.tools()
                    elif self.path == "/call":
                        result = runtime.call(
                            str(body.get("toolName") or ""),
                            dict(body.get("arguments") or {}),
                        )
                    elif self.path == "/snapshot":
                        result = runtime.snapshot()
                    else:
                        self._send(404, {"ok": False, "error": "not found"})
                        return
                    self._send(200, {"ok": True, "result": result})
                except Exception as exc:  # noqa: BLE001 - HTTP boundary reports failures
                    self._send(400, {"ok": False, "error": str(exc)})

            def log_message(self, format: str, *args: Any) -> None:
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(
            target=server.serve_forever,
            name=f"cute-evals-{self.runtime_name.lower().replace(' ', '-')}-sql",
            daemon=True,
        )
        thread.start()
        self._server = server
        self._thread = thread
        self.port = int(server.server_address[1])
        return self

    def close(self) -> None:
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=2)
        self._server = None
        self._thread = None
        self.port = None

    def __enter__(self) -> Self:
        return self.start()

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()
