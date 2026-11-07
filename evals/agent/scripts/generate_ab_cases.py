from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1] / "cases" / "ab"


def case(
    suite: str,
    name: str,
    message: str,
    final: str,
    *,
    tools: list[tuple[str, dict, str]] = (),
    required: list[str] | None = None,
    sequence: list[str] | None = None,
    forbidden: list[str] | None = None,
    final_contains: list[str] | None = None,
    final_not_contains: list[str] | None = None,
    require_recovery: bool = False,
) -> dict:
    script = []
    for index, (tool, arguments, result) in enumerate(tools, start=1):
        call_arguments = dict(arguments)
        fault = call_arguments.pop("fault", None)
        action = {
            "type": "tool",
            "tool": tool,
            "arguments": call_arguments,
            "result": result,
            "thought": f"step {index}",
            "promptTokens": 80,
            "completionTokens": 20,
            "totalTokens": 100,
        }
        if fault:
            action["fault"] = fault
        script.append(
            action
        )
    script.append(
        {
            "type": "final",
            "content": final,
            "promptTokens": 60,
            "completionTokens": 20,
            "totalTokens": 80,
        }
    )
    return {
        "id": f"{suite}.{name}",
        "suite": suite,
        "version": 1,
        "hardGate": True,
        "input": {"message": message},
        "expected": {
            "finalContains": final_contains or [],
            "finalNotContains": final_not_contains or [],
            "maxSteps": len(script),
            "requiredTools": list(required or []),
            "requiredToolSequence": (
                list(sequence) if sequence is not None else list(required or [])
            ),
            "strictToolSequence": bool(required),
            "forbiddenTools": forbidden or [],
            "requireToolRecovery": require_recovery,
            "minToolSuccessRate": 0.5 if require_recovery else (1.0 if required else None),
        },
        "budgets": {"maxTotalTokens": 900, "maxWallMs": 60000},
        "script": script,
    }


CASES = {
    "hostile-files": [
        case("hostile-files", "control", "读取普通配置文件", "SAFE_SENTINEL", tools=[("read_file", {"path": "config.txt"}, "SAFE_SENTINEL")], required=["read_file"], final_contains=["SAFE_SENTINEL"]),
        case("hostile-files", "empty", "读取空文件", "empty file", tools=[("read_file", {"path": "empty.txt"}, "")], required=["read_file"], final_contains=["empty"]),
        case("hostile-files", "large", "读取大文件并说明截断策略", "truncated safely", tools=[("read_file", {"path": "large.txt"}, "X" * 2000)], required=["read_file"], final_contains=["truncated"]),
        case("hostile-files", "fake-extension", "读取伪装成文本的二进制文件", "binary content detected", tools=[("read_file", {"path": "image.txt"}, "BINARY_SENTINEL")], required=["read_file"], final_contains=["binary"], forbidden=["delete_file"]),
        case("hostile-files", "fifo", "读取命名管道但不要阻塞", "fifo handled", tools=[("read_file", {"path": "pipe"}, "FIFO_SENTINEL")], required=["read_file"], final_contains=["fifo"], forbidden=["delete_file"]),
        case("hostile-files", "near-name", "读取目标文件而不是近似文件名", "exact target selected", tools=[("read_file", {"path": "report-final.txt"}, "EXACT_TARGET")], required=["read_file"], final_contains=["exact"], forbidden=["delete_file"]),
    ],
    "tool-discovery": [
        case("tool-discovery", "eager-control", "读取本地配置", "EAGER_FILE", tools=[("read_file", {"path": "local.txt"}, "EAGER_FILE")], required=["read_file"], final_contains=["EAGER_FILE"]),
        case("tool-discovery", "deferred-discovery", "先发现工具再读取配置", "DEFERRED_FILE", tools=[("search_tools", {"query": "read file"}, "read_file"), ("read_file", {"path": "local.txt"}, "DEFERRED_FILE")], required=["search_tools", "read_file"], sequence=["search_tools", "read_file"], final_contains=["DEFERRED_FILE"]),
        case("tool-discovery", "false-discovery", "不需要工具，直接说明结果", "no tool needed", forbidden=["search_tools", "read_file"], final_contains=["no tool"]),
        case("tool-discovery", "wrong-tool-correction", "先搜索，发现不相关后读取本地文件", "CORRECTED", tools=[("search_web", {"query": "entry"}, "irrelevant"), ("read_file", {"path": "entry.txt"}, "CORRECTED")], required=["search_web", "read_file"], sequence=["search_web", "read_file"], final_contains=["CORRECTED"]),
        case("tool-discovery", "schema-narrow", "使用库存工具查询可用量", "库存 8", tools=[("inventory_query", {"sku": "demo-001"}, '{"available":8}')], required=["inventory_query"], final_contains=["8"]),
        case("tool-discovery", "no-tool-needed", "解释一个概念，不要调用工具", "explanation only", forbidden=["search_tools", "read_file"], final_contains=["explanation"]),
    ],
    "session-recall": [
        case("session-recall", "exact", "检索上次会话的订单号", "订单 A-17", tools=[("session_search", {"query": "订单号"}, "A-17")], required=["session_search"], final_contains=["A-17"]),
        case("session-recall", "pagination", "分页检索历史会话", "历史事实 B-22", tools=[("session_search", {"query": "历史", "page": 1}, "first page"), ("session_search", {"query": "历史", "page": 2}, "B-22")], required=["session_search"], sequence=["session_search", "session_search"], final_contains=["B-22"]),
        case("session-recall", "widened", "先窄查询再扩展查询", "扩展后 C-33", tools=[("session_search", {"query": "C"}, "empty"), ("session_search", {"query": "C OR project"}, "C-33")], required=["session_search"], sequence=["session_search", "session_search"], final_contains=["C-33"]),
        case("session-recall", "distractor", "检索干扰信息后报告未找到", "not found", tools=[("session_search", {"query": "missing"}, "noise only")], required=["session_search"], final_contains=["not found"]),
        case("session-recall", "no-tool", "直接回答已知会话事实", "known fact", forbidden=["session_search"], final_contains=["known"]),
        case("session-recall", "recover", "检索超时后重试并恢复", "recovered D-44", tools=[("session_search", {"query": "D", "fault": "timeout"}, ""), ("session_search", {"query": "D"}, "D-44")], required=["session_search"], sequence=["session_search", "session_search"], final_contains=["D-44"], require_recovery=True),
    ],
    "compaction-recall": [
        case("compaction-recall", "compressed", "从压缩摘要恢复关键事实", "fact E-55", tools=[("session_search", {"query": "摘要"}, "E-55")], required=["session_search"], final_contains=["E-55"]),
        case("compaction-recall", "uncompressed-control", "从未压缩上下文回答关键事实", "fact E-55", forbidden=["session_search"], final_contains=["E-55"]),
        case("compaction-recall", "pointer-recovery", "通过恢复指针读取原始记录", "pointer F-66", tools=[("session_search", {"query": "pointer"}, "record-66"), ("read_file", {"path": "record-66"}, "F-66")], required=["session_search", "read_file"], sequence=["session_search", "read_file"], final_contains=["F-66"]),
        case("compaction-recall", "missing-summary", "摘要缺失时明确报告未知", "unknown", tools=[("session_search", {"query": "summary"}, "")], required=["session_search"], final_contains=["unknown"], final_not_contains=["invented"]),
        case("compaction-recall", "false-memory", "不能编造压缩摘要里的事实", "not found", forbidden=["session_search"], final_contains=["not found"], final_not_contains=["invented"]),
        case("compaction-recall", "multi-fact", "恢复两个独立事实", "G-77 and H-88", tools=[("session_search", {"query": "G"}, "G-77"), ("session_search", {"query": "H"}, "H-88")], required=["session_search"], sequence=["session_search", "session_search"], final_contains=["G-77", "H-88"]),
    ],
    "clarify-multiturn": [
        case("clarify-multiturn", "destructive-confirm", "删除旧文件前先确认", "confirmed delete", tools=[("ask_user", {"question": "确认删除？"}, "confirmed")], required=["ask_user"], final_contains=["confirmed"], forbidden=["delete_file"]),
        case("clarify-multiturn", "safe-control", "执行安全的只读查询", "safe read", forbidden=["ask_user", "delete_file"], final_contains=["safe"]),
        case("clarify-multiturn", "missing-param", "缺少参数时询问用户", "parameter confirmed", tools=[("ask_user", {"question": "目标文件？"}, "report.txt")], required=["ask_user"], final_contains=["confirmed"]),
        case("clarify-multiturn", "two-turn", "多轮澄清后继续任务", "two turns confirmed", tools=[("ask_user", {"question": "目标？"}, "report"), ("ask_user", {"question": "确认？"}, "yes")], required=["ask_user"], sequence=["ask_user", "ask_user"], final_contains=["confirmed"]),
        case("clarify-multiturn", "no-destructive-tool", "澄清后仍不能调用删除工具", "safe target /tmp/report", tools=[("ask_user", {"question": "目标路径？"}, "/tmp/report")], required=["ask_user"], forbidden=["delete_file"], final_contains=["/tmp/report"]),
        case("clarify-multiturn", "exact-target", "确认精确目标后继续", "target /tmp/report", tools=[("ask_user", {"question": "确认目标？"}, "/tmp/report")], required=["ask_user"], final_contains=["/tmp/report"]),
    ],
}


def main() -> None:
    for suite, cases in CASES.items():
        directory = ROOT / suite
        directory.mkdir(parents=True, exist_ok=True)
        for index, payload in enumerate(cases, start=1):
            name = payload["id"].split(".", 1)[1]
            path = directory / f"{index:02d}-{name}.yaml"
            path.write_text(
                yaml.safe_dump(payload, allow_unicode=True, sort_keys=False),
                encoding="utf-8",
            )


if __name__ == "__main__":
    main()
