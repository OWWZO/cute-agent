# WorkBuddy Code 评测工具链修复计划

## 目标

修复本轮 WorkBuddy Code 兼容性子集评测中暴露出的工具链与报告问题，让后续成绩更可信、更可解释。

成功标准：

- 报告不再把 Python/Django traceback 里的 `429` 或 `argument` 文本误判成限流/参数错误。
- `code_execution` 的工具描述明确说明 Jupyter 会话会保留变量/import/状态；不改变“跨次调用复用状态”这一产品特性。
- eval profile 中 Web 工具不再无意义干扰代码题；WebSearch 直接禁用，WebFetch 降级为可选并明确失败提示。
- workspace 工具错误信息给出结构化错误码和建议工具，减少 Agent 调错路径。
- 加入 Docker 前置预检，避免 Agent 跑完才发现裁判环境不可用。
- 用同一组 3 题复跑对比，确认 `unverified=0`、分类准确、工具误用下降。

## 明确不改的内容

- 不把 `code_execution` 改成“每次脚本独立进程”。
- 不改变 E2B 云端沙箱 + Jupyter 会话复用状态的设计。
- 不用 E2B 替代 Docker 官方 verifier。
- 不把官方 injected tests 暴露给 Agent。
- 不回滚、不清理工作区里已有的用户改动。

## 阶段 0：基线固化

步骤：

1. 记录当前有效基线报告：
   - `D:\cute-eval-tmp\workbuddy-code-pytest-subset-20261118-v18-three\report.json`
   - 当前结果：`cases=3`, `failed=3`, `unverified=0`, `pass@1=0`, 平均 reward 约 `0.3611`。
2. 保存三类失败样本：
   - `WebFetch` 502/timeout。
   - `WebSearch` 未配置。
   - Django 诊断脚本 traceback 被误分类。

验证：

- 基线报告文件存在且可解析。
- 误分类样本可从 trace 中按工具名和 errorMsg 复现。

## 阶段 1：修复报告失败分类

涉及文件：

- `evals/agent/src/cute_evals/report.py`
- `evals/agent/tests/test_report.py`

修复内容：

1. 把 `_failure_categories` 和 `_tool_failure_classes` 的字符串匹配改成结构化、上下文感知判断。
2. 新增分类：
   - `code_runtime_error`
   - `web_tool_error`
   - `workspace_argument_error`
3. 限流判断收紧：
   - 只有 `HTTP 429`、`status=429`、`429 Too Many Requests`、`rate limit exceeded` 等才归为 `tool_rate_limit`。
   - traceback 里的文件名行号（如 `query_utils.py:429`）不得归为限流。
4. 参数错误判断收紧：
   - 只有工具层错误，如 `missing required argument`、`invalid tool argument`、`unexpected keyword argument`，才归为 `invalid_arguments`。
   - Python 业务异常里的 `argument must be a string` 不得归为工具参数错误。
5. `code_execution` 返回 Python traceback 时，默认归为 `code_runtime_error`。
6. `WebFetch` 的 502/timeout 归为 `web_tool_error` 或 `tool_network/tool_timeout`，不再混入代码工具错误。

测试：

- traceback 含 `query_utils.py:429` → 不得出现 `tool_rate_limit`。
- 错误文本含 `relation_name argument must be a string` → 不得出现 `invalid_arguments`。
- 真 HTTP 429 → 必须是 `tool_rate_limit`。
- 真工具缺参 → 必须是 `invalid_arguments`。
- `code_execution` 抛 `RuntimeError: Settings already configured.` → `code_runtime_error`。

验证命令：

```powershell
cd evals\agent
uv run pytest -q tests/test_report.py
```

## 阶段 2：明确 code_execution 的 Jupyter 会话语义

涉及文件：

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/CodeExecutionTool.java`
- 相关 Java 工具描述测试（如已有则扩写，如没有则新增最小测试）

修复内容：

1. 在 `getDescription()` 里新增明确说明：
   - 同一会话的 `code_execution` 复用 Jupyter 内核。
   - 上一次脚本定义或 import 的变量、函数、模块状态会保留到下一次调用。
   - 文件改动会同步回工作区，但 Python 进程状态不会因为文件回写自动重置。
   - 如果脚本依赖全局状态，要保证命名不冲突；必要时显式清理变量、重新 import，或用子进程隔离。
2. 在 `source` 参数描述里补充：
   - 写诊断脚本时不要假设每个脚本都是干净进程。
   - 重复配置 Django settings 会触发 `Settings already configured`。
   - 需要干净进程时用 `subprocess` 在该次脚本内部启动。
3. 不改变 `code_execution` 的运行时行为，只改工具描述。

测试：

- 工具描述包含 `Jupyter`、`变量`、`保留`、`subprocess` 等关键词。
- 原有工具注册测试不失败。

验证命令：

```powershell
mvn.cmd -q -pl Cute-agent-domain -am test '-Dtest=*CodeExecutionTool*'
```

如果没有对应测试类，则用现有 domain 工具测试集验证：

```powershell
mvn.cmd -q -pl Cute-agent-domain -am test '-Dtest=ToolObservationFormatterTest,ToolExecutionPipelineObservationTest'
```

## 阶段 3：eval profile 收口 Web 工具

涉及文件：

- `Cute-agent-app/src/main/resources/application-eval.yml`
- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/factory/AgentToolCollectionFactory.java`
- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/WebFetchTool.java`
- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/WebSearchTool.java`

修复内容：

1. eval profile 明确不注册 `WebSearch`。
   - 目前 `web_search.mode=disabled` 只是后端禁用，工具仍暴露给 Agent，导致无意义调用。
   - 通过 eval 的 tool list 控制，移除 `web_search`。
2. `WebFetch` 在 WorkBuddy Code 评测中不作为 required tool。
   - 若保留，更新描述：外部网页不可用时不要反复尝试，直接用本地工作区。
3. 如果需要在工具层保证 eval 不暴露 WebSearch：
   - 在 `AgentToolCollectionFactory` 中尊重 eval profile 的显式 tool list。
   - 不再依赖默认 tool list 中隐含的 `web_search`。
4. `WebFetch` 已有 `ToolAccessPolicy` 熔断逻辑保留；补充更明确的 hint：
   - 连续 502/timeout 后提示 `external web unavailable; rely on local workspace`。

测试：

- eval profile 下 `availableTools` 不含 `WebSearch`。
- `WebFetch` 仍可选，但 preflight 不把它列为 required。
- WebFetch 连续失败后 hint 包含本地工作区建议。

验证命令：

```powershell
mvn.cmd -q -pl Cute-agent-app -am test '-Dtest=WorkBuddyToolPreflightCli'
```

或者直接跑预检并查看 `availableTools`。

## 阶段 4：workspace 工具错误信息结构化

涉及文件：

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/workspace/WorkspaceReadTool.java`
- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/workspace/WorkspaceGlobTool.java`
- 对应测试文件

修复内容：

1. `workspace_read` 读目录时返回：
   - `errorCode=WORKSPACE_READ_PATH_IS_DIR`
   - 提示 `Use workspace_list(path=...) to inspect directories.`
2. `workspace_glob` 传文件路径时返回：
   - `errorCode=WORKSPACE_GLOB_PATH_NOT_DIR`
   - 提示 `Use workspace_read(path=...) for files; workspace_glob path must be a directory.`
3. `workspace_grep` 缺少 pattern 时返回：
   - `errorCode=WORKSPACE_GREP_PATTERN_REQUIRED`
4. 工具描述里补最小示例：
   - `workspace_read(path="src/a.py")`
   - `workspace_list(path="src")`
   - `workspace_glob(path="src", pattern="*.py")`

测试：

- 传目录给 `workspace_read` 得到结构化错误码和建议工具。
- 传文件给 `workspace_glob` 得到结构化错误码和建议工具。
- 正常读写/glob 不受影响。

验证命令：

```powershell
mvn.cmd -q -pl Cute-agent-domain -am test '-Dtest=*Workspace*Test'
```

## 阶段 5：Docker 前置检查

涉及文件：

- `evals/agent/src/cute_evals/benchmarks/workbuddybench/docker_runner.py`
- `evals/agent/src/cute_evals/cli.py`
- `evals/agent/tests/test_workbuddybench_docker_runner.py`
- `evals/agent/tests/test_cli.py`

修复内容：

1. 在 `_run_workbuddybench_benchmark()` 中，tool preflight 之后、逐题 Agent 之前，调用 Docker verifier 的 `check_environment()`。
2. 如果 Docker Linux engine 不可用，直接抛 `EvalRunnerError("environment", ...)`。
3. 报告中记录 `dockerPreflight` 状态，便于确认裁判环境已检查。
4. 不改每题评分流程；只是把原来的评分阶段失败提前。

测试：

- mock Docker 返回 Windows engine → benchmark 启动即失败，不进入 Agent。
- mock Docker 返回 Linux engine → 正常进入后续流程。
- 现有 Docker verifier 测试通过。

验证命令：

```powershell
cd evals\agent
uv run pytest -q tests/test_workbuddybench_docker_runner.py tests/test_cli.py
```

## 阶段 6：Code 评测提示约束

涉及文件：

- 评测 case 生成逻辑：`evals/agent/src/cute_evals/benchmarks/workbuddybench/adapter.py`
- 或在 eval profile 的 agent system/developer prompt 注入点

修复内容：

在 WorkBuddy Code case 的 query 前追加固定工作约束：

```text
WorkBuddy Code 工作约束：
1. 必须修改源文件；不要只写临时诊断脚本替代修复。
2. 修改前后检查 workspace diff，确认关键文件确实被改。
3. 如果题目涉及异常，要区分“不抛异常”和“仍然完成必要清理/状态维护”。
4. 如果测试校验错误文案，必须逐字匹配大小写、标点和类别名。
5. 不要调用 WebSearch/WebFetch；题目信息都在本地工作区。
6. code_execution 的 Jupyter 会话会保留变量和 import；诊断脚本不要假设是干净进程。
```

注意：

- 不暴露官方 injected tests、gold patch、verifier 命令。
- 只约束工作方式，不泄露答案。

测试：

- WorkBuddy case 的 `query` 包含约束前缀。
- 其他 benchmark 不受影响。

验证命令：

```powershell
cd evals\agent
uv run pytest -q tests/test_benchmark_workbuddybench.py
```

## 阶段 7：回归复跑

步骤：

1. 重新构建并安装 Java 模块：

```powershell
mvn.cmd -q -pl Cute-agent-app -am install '-Dmaven.test.skip=true'
```

2. 重启 `cute-tool`：

```powershell
& .\scripts\start-python.ps1 -WorkspaceRoots "D:\cute-eval-tmp"
```

3. 跑工具预检，确认 `requiredPassed=true`。

4. 跑 Docker 前置检查。

5. 用同一组 3 题复跑：

```powershell
cd evals\agent
$env:CUTE_EVAL_DB_USER='root'
$env:CUTE_EVAL_DB_PASSWORD='123456'
$env:CODE_EXECUTION_WORKSPACE_ROOTS='D:\cute-eval-tmp'
uv run cute-evals benchmark `
  --name workbuddybench `
  --split internal `
  --profile nightly `
  --java-mode live `
  --budget-mode capability `
  --source-dir D:\cute-eval-tmp\workbuddy-bench-main `
  --limit 3 `
  --repeat 1 `
  --out D:\cute-eval-tmp\workbuddy-code-pytest-subset-20261118-v19-three
```

成功标准：

- `unverified=0`。
- 报告中不再出现由 traceback 行号导致的 `tool_rate_limit`。
- 报告中不再出现由业务异常单词导致的 `invalid_arguments`。
- `availableTools` 不含 `WebSearch`。
- `WebFetch` 调用次数下降；如果仍调用，失败分类明确是 `web_tool_error`。
- `code_execution` 的 Python 脚本错误归为 `code_runtime_error`。
- 与 v18 三题成绩做同口径对比，能解释每个 reward 变化。

## 风险与权衡

- 修改工具描述可能影响所有 Agent 行为，不只 WorkBuddy；需要在 description 中保持通用、不过度绑定评测。
- 禁用 WebSearch 可能影响需要外网能力的工作流；因此只在 eval profile 生效。
- 报告分类改动会改变历史报告对比口径；需在报告中保留旧分类字符串，或通过测试明确新语义。
- Docker 前置检查会让评测启动更早失败，但这是期望行为，避免浪费 Agent token。

## 建议执行顺序

1. 阶段 1：报告分类修复。
2. 阶段 2：code_execution 会话语义描述。
3. 阶段 3：Web 工具收口。
4. 阶段 4：workspace 结构化错误。
5. 阶段 5：Docker 前置检查。
6. 阶段 6：Code 工作约束提示。
7. 阶段 7：完整复跑。
