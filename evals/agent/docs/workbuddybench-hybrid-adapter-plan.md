# WorkBuddy Bench 混合模式接入计划

## 1. 文档状态

- 状态：实施计划，已确认方向
- 目标仓库：`D:\Java Code\ai-agent\Cute-agent`
- 评测子项目：`evals/agent`
- 首个数据集：`wb-bench-code-v1.0`
- 首批任务类型：`pytest_injected`
- 首个验证题：`bug_fix-easy-a_crash_in_local`
- 运行模式：宿主机运行 Cute Agent，Docker 只运行官方测试和官方 scorer
- 评分口径：`compatibility-subset`，不标记为 WorkBuddy 官方榜单成绩

本计划只新增 WorkBuddy Bench 独立 Adapter 和 Docker verifier 生命周期，
不修改现有 BIRD、GAIA、Spider2、tau2 等 Benchmark 的语义。

## 2. 目标

实现一个可重复运行的最小闭环：

1. Python `cute-evals` 读取 WorkBuddy Code 题目。
2. 将官方 `workspace.tar.gz` 解压到本次评测的临时工作区。
3. 使用现有 Java Live Runner 让 Cute Agent 在宿主机工作区执行。
4. 收集 Agent 的 Trace、代码改动和最终状态。
5. 用 WorkBuddy 题目的官方 Docker 环境运行官方测试。
6. 读取官方测试结果或 `reward.json`。
7. 映射为现有 `CaseResult`、`ScoreRecord` 和 `report.json/report.html`。

最终目标是能回答：

- 同一个 Code 任务，Cute Agent 是否通过。
- Agent 改了什么文件，patch 是什么。
- 官方测试执行了哪些用例，失败原因是什么。
- 本次结果是否属于可验证的内部对照。
- 是否记录了题目、数据集、镜像、命令和评分证据。

## 3. 非目标

第一阶段明确不做：

- 不接 Security 子集。
- 不把 Cute Agent 放进 WorkBuddy/Harbor 容器。
- 不修改 WorkBuddy 官方源码、测试、gold patch 或 scorer。
- 不声称结果与 WorkBuddy 官方榜单可直接比较。
- 不实现 Web 的 VLM/LLM judge。
- 不实现 Office 的 host-side LLM judge。
- 不把 Web、Office、Security 全量接入。
- 不提交第三方数据集或大体积 workspace 到当前仓库。

## 4. 已确认事实和假设

### 4.1 已确认事实

- 官方框架源码位于：
  `D:\cute-eval-tmp\workbuddy-bench-main`
- Code 数据集位于：
  `D:\cute-eval-tmp\workbuddy-bench-main\datasets\wb-bench-code-v1.0`
- Web 数据集位于：
  `D:\cute-eval-tmp\workbuddy-bench-main\datasets\wb-bench-web-v1.0`
- Office 数据集位于：
  `D:\cute-eval-tmp\workbuddy-bench-main\datasets\wb-bench-office-v1.0`
- Code 数据集的压缩包 SHA-256 已通过官方 `SHA256SUMS` 校验。
- 官方 Code 数据集包含 80 道题。
- Code 题目的验证器分为：
  - `pytest_injected`：22 道
  - `script_verifier`：54 道
  - `repo_understanding`：4 道
- `bug_fix-easy-a_crash_in_local` 已用宿主机 Python 3.12 验证：
  - 修复前 3 个测试失败。
  - 应用官方 gold patch 后 3 个测试通过。
  - 官方 `scorer.py` 输出 `overall=1.0`、`test_status=full_pass`。
- 当前机器安装了 Docker CLI，但 Docker Desktop Linux engine 当前未启动。
- 现有 Python 评测框架通过 `BenchmarkAdapter` 接入公开 Benchmark。
- 现有 Java `EvalBenchmarkLiveRunnerCli` 支持：
  - `workspaceRoot`
  - `toolMode=production`
  - 真实 Trace、工具调用、Token、耗时和 Execution Ledger 导出

### 4.2 本计划采用的假设

- 第一阶段只支持 Code 中 `pytest_injected` 型任务。
- 官方测试命令在任务镜像内执行时，可以访问 `/workspace`。
- Agent 在宿主机运行，工作区通过 Docker bind mount 或复制方式提供给 verifier。
- 对 pycache、构建缓存和测试注入文件不计入 Agent 的业务 patch。
- 首批结果只用于内部能力对照。

## 5. 总体架构

```text
cute-evals CLI
    |
    v
WorkBuddyBenchAdapter
    |
    +-- tasks()          读取官方 task.toml / instruction.md / verifier.toml
    +-- prepare()        解压官方 workspace，登记官方资产哈希
    +-- case_for_task()  构造现有 EvalCase，交给 Java Runner
    +-- collect()        收集 Trace、patch、workspace 快照和 Docker 信息
    +-- score()          调用 Docker verifier runner，生成 ScoreRecord
    |
    +-----------------------------+
    |                             |
    v                             v
Java EvalBenchmarkLiveRunner   WorkBuddyDockerVerifier
(宿主机 Cute Agent)            (Docker 官方测试环境)
    |                             |
    +---------- workspace --------+
                  |
                  v
        official pytest / reward.json
```

### 5.1 为什么不直接改现有 Case YAML

WorkBuddy 的题目不只是 Prompt：

- 题目自带仓库基线。
- 题目自带隐藏测试。
- 题目自带 Dockerfile 和测试依赖。
- 题目自带 scorer、gold patch 和通过条件。

如果把题目转换成普通 YAML Case，会丢失运行环境和官方测试，
只能得到“回答看起来对不对”的弱对照，不能得到可审计的测试结果。

### 5.2 为什么不让 Docker 承担 Agent

第一阶段让 Docker 只负责验证器：

- 不需要把 Java/Maven/Cute Agent 打包成 Linux harness。
- 不需要改 WorkBuddy 官方 harness 注册。
- 能直接复用现有 Java Live Runner 的 Trace 和预算控制。
- 失败边界清晰：Agent 在宿主，测试环境在容器。

代价是：这是混合模式，不是官方端到端环境，因此不能标记为
`official-subset`。

## 6. 目录设计

新增：

```text
evals/agent/src/cute_evals/benchmarks/workbuddybench/
├── __init__.py
├── adapter.py
├── docker_runner.py
└── manifest.yaml
```

测试：

```text
evals/agent/tests/
├── test_benchmark_workbuddybench.py
└── test_workbuddybench_docker_runner.py
```

文档：

```text
evals/agent/docs/workbuddybench-hybrid-adapter-plan.md
```

不新增数据库表，不修改现有 Trace schema，不修改现有 Report schema。

## 7. Adapter 接口映射

### 7.1 `tasks(split)`

第一阶段支持：

- `internal`：只读取 manifest 中明确列出的 `pytest_injected` 任务。
- `official-subset`：后续阶段再开启，且必须使用 Docker 官方 verifier。
- `official-full`：第一阶段拒绝。

首批 `internal` 任务：

```text
bug_fix-easy-a_crash_in_local
```

`BenchmarkTask` 映射：

- `id`：官方 task 目录名。
- `category`：`task.toml` 中的 `metadata.category`。
- `question`：`instruction.md` 原文。
- `raw`：`task.toml` 的可审计摘要。
- `metadata`：
  - `taskDir`
  - `workspaceArchive`
  - `workspaceSha256`
  - `verifierFamily`
  - `verifierCommand`
  - `agentTimeoutSec`
  - `verifierTimeoutSec`
  - `split`

### 7.2 `prepare(task, workspace)`

步骤：

1. 校验 `workspace.tar.gz` 的 SHA-256。
2. 在本次 attempt 的临时目录解压 workspace。
3. 保留原始 `tests/` 为只读输入，不把测试文件注入 Agent 工作区。
4. 初始化或记录 workspace 的 git 基线，用于生成 Agent patch。
5. 返回 `PreparedTask`，`workspace` 指向宿主工作区。

### 7.3 `case_for_task(task)`

构造现有 `EvalCase`：

- `id=task.id`
- `suite=workbuddybench-internal`
- `hard_gate=True`
- `query=instruction.md`
- `expected.max_steps=manifest.budgets.maxSteps`
- `budgets.max_wall_ms=task.toml [agent].timeout_sec * timeout multiplier`
- `budgets.max_total_tokens=manifest.budgets.maxTokens`
- `budgets.max_tool_calls=manifest.budgets.maxToolCalls`
- `source_path=taskDir/task.toml`

Agent 使用：

- `runner_class=org.wwz.ai.test.domain.EvalBenchmarkLiveRunnerCli`
- `tool_mode=production`
- `workspace_roots={task.id: workspace}`

### 7.4 `collect(...)`

收集：

- 现有 `RunTrace` 和 trace 路径。
- `git diff` 生成的 `agent.patch`。
- 工作区文件清单和关键文件 SHA-256。
- 是否出现不允许的测试文件改动。
- Docker 镜像 tag、镜像 ID、容器 ID。
- verifier 输出目录。

### 7.5 `score(...)`

Docker verifier 完成后读取：

- `reward.json`：数值评分。
- `score.json`：官方 scorer 诊断。
- `results.xml`：pytest JUnit 结果。
- `test_output.txt`：完整测试输出。

`ScoreRecord` 设计：

- `scorer=workbuddybench.official-test`
- `name=test_pass_rate`
- `value=reward.json.overall`
- `passed=(overall >= 1.0)`
- `status=verified`，仅表示官方测试已执行且结果可读。
- evidence 中必须包含：
  - `datasetSha256`
  - `taskId`
  - `dockerImage`
  - `dockerImageId`
  - `verifierCommand`
  - `reward`
  - `resultFiles`

报告顶层必须写入：

```json
{
  "officialScore": false,
  "scoreLabel": "compatibility-subset",
  "runnerMode": "hybrid-host-agent-docker-verifier"
}
```

## 8. Docker Verifier 设计

### 8.1 容器职责

容器只负责：

- 提供任务 Dockerfile 定义的 Linux/Python 环境。
- 将宿主 Agent 工作区复制或挂载到 `/workspace`。
- 把官方 `tests/` 挂载到 `/tests`。
- 在 `/logs/verifier` 输出测试和评分结果。
- 执行 `tests/verifier.toml` 中的命令。

容器不负责：

- 启动 Cute Agent。
- 启动 Cute-agent Spring Boot。
- 启动 MySQL、Qdrant 或现有业务系统。
- 修改 Cute Agent 的 Trace 或预算。

### 8.2 Docker 命令边界

第一版直接通过 Python `subprocess` 调用 Docker CLI，不新增 Docker SDK 依赖。

建议命令：

```text
docker build -t <image-tag> <task-environment-dir>
docker create --name <container-name> <image-tag>
docker start <container-name>
docker exec <container-name> <verifier-command>
docker cp ...
docker rm -f <container-name>
```

所有容器名、输出目录和镜像 tag 都由 Adapter 生成并写入 evidence。
不使用 `docker system prune`、`docker rm $(docker ps ...)` 等宽范围清理命令。

### 8.3 工作区隔离

评分前创建 `verification-workspace` 快照：

1. 保留 Agent 原始工作区。
2. 将 Agent 工作区复制为 verifier 快照。
3. 只在 verifier 快照中注入 `tests/injected`。
4. 在 verifier 快照中运行官方测试。

这样可以保证注入测试不会污染 Agent 的工作区和最终 patch。

### 8.4 测试命令读取

读取 `tests/verifier.toml`：

- `family=pytest_injected`：执行 `command`。
- `/logs/verifier` 是容器内固定日志目录。
- `/workspace` 是容器内固定工作区目录。

第一阶段不接受：

- `script_verifier`
- `repo_understanding`
- Web/Office/Security 专用 verifier

遇到不支持的 family 时返回 `unverified`，并明确写出原因。

### 8.5 环境检查

启动任何任务前检查：

- `docker version --format '{{.Server.APIVersion}}'` 是否成功。
- Docker Desktop Linux engine 是否运行。
- 任务 Dockerfile 是否存在。
- 工作区归档、测试目录和 verifier.toml 是否存在。

Docker 不可用属于 `environment` 错误，不能记成 Agent 能力失败。

## 9. 分阶段实施计划

## 阶段 0：资产和基线确认

状态：已完成（2026-11-17）。

已完成的资产：

- 官方框架已下载。
- Code/Web/Office 数据集已下载。
- Code 数据集 SHA-256 已校验。
- 单题宿主机测试已跑通。
- Docker CLI 已确认安装。

完成结果：

- Docker Desktop Linux engine 已启动。
- Docker client/server 版本均为 `28.5.1`，server API 为 `1.51`。
- 官方单题镜像已构建：`cute-workbuddybench:stage0-bugfix`。
- 镜像 ID：`sha256:6c246f9ba4770e3cbbdcbe4ca3dea075f3937521f4729ac41384c7ff4f659827`。
- 在容器内应用官方 gold patch 并运行官方 pytest，结果为 3/3 通过。

验收：

```text
docker version --format '{{.Server.APIVersion}}'
```

输出有效 API 版本。

## 阶段 1：Adapter 静态能力

状态：已完成（2026-11-17）。

实现：

- `manifest.yaml`
- `__init__.py`
- `adapter.py` 的 `tasks()`、`prepare()`、`case_for_task()`。
- 只支持首个 `pytest_injected` 任务。
- 拒绝未知 task ID、未知 verifier family、缺失资产。

验收：

```powershell
cd evals/agent
uv run python -m compileall -q src
uv run pytest -o addopts="" -q tests/test_benchmark_workbuddybench.py
```

验收标准：

- 题目 ID 和 instruction 与官方文件一致。
- workspace 解压后的文件存在。
- 官方资产哈希不一致时拒绝运行。
- 不支持的 family 返回清晰错误。

完成结果：

- 已新增 `WorkBuddyBenchAdapter`、manifest 和 7 个单元测试。
- `uv run python -m compileall -q src` 通过。
- `uv run pytest -o addopts="" -q tests/test_benchmark_workbuddybench.py` 结果为 7 passed。
- 使用真实 WorkBuddy Code 资产读取 `bug_fix-easy-a_crash_in_local` 成功。
- 真实 workspace 已解压到临时目录并通过文件检查。
- 真实任务返回的 suite 为 `workbuddybench-internal`，报告标记 `officialScore=false`。

## 阶段 2：Docker Verifier Runner

状态：已完成（2026-11-17）。

实现：

- `docker_runner.py`
- 镜像构建。
- 容器创建、启动、执行和清理。
- verifier 快照和测试注入。
- 输出文件收集。

验收：

```powershell
cd evals/agent
uv run pytest -o addopts="" -q tests/test_workbuddybench_docker_runner.py
```

离线测试先使用 fake Docker command，验证：

- 镜像 tag 和容器名确定。
- 路径正确传递。
- Docker 不存在时抛出 environment 错误。
- verifier 失败时保留日志。
- 清理只针对本次生成的容器。

真实 Docker 测试：

1. 构建 `bug_fix-easy-a_crash_in_local` 官方镜像。
2. 在临时 workspace 中应用官方 gold patch。
3. 注入官方测试。
4. 执行官方测试命令。
5. 期望 pytest 3/3 通过。
6. 期望 `reward.json.overall=1.0`。

完成结果：

- 新增 `docker_runner.py` 和 4 个 fake Docker 单元测试。
- Docker Linux engine 检查结果为 `linux|1.51`。
- 真实 gold patch 运行结果为 `status=verified`、pytest 3/3、
  `test_status=full_pass`、`overall=1.0`。
- `agent.patch` 为 10 行官方预期改动，未出现 CRLF 导致的整文件重写。
- 容器只按本次生成的名称清理，没有使用全局 prune。
- 结果目录：
  `D:\cute-eval-tmp\workbuddy-stage2-gold-20261117175454`。

## 阶段 3：CLI 注册和报告映射

状态：已完成（2026-11-17）。

实现：

- 在 `evals/agent/src/cute_evals/cli.py` 增加 `workbuddybench` 名称。
- 增加命令分支。
- `--source-dir` 指向 WorkBuddy 框架目录。
- `--limit` 用于单题 smoke。
- `--out` 固定本次结果目录。

目标命令：

```powershell
cd evals/agent
uv run cute-evals benchmark `
  --name workbuddybench `
  --split internal `
  --profile nightly `
  --java-mode live `
  --source-dir D:\cute-eval-tmp\workbuddy-bench-main `
  --limit 1 `
  --out D:\cute-eval-tmp\workbuddy-hybrid-smoke
```

验收：

- `report.json` 和 `report.html` 正常生成。
- 单题结果包含 `CaseResult`、`ScoreRecord` 和 Trace 路径。
- 报告标记 `officialScore=false`、`scoreLabel=compatibility-subset`。

完成结果：

- `cute-evals benchmark --name workbuddybench` 已注册，只接受
  `--split internal` 和 live 模式。
- `report.json` 顶层写入 `officialScore=false`、
  `scoreLabel=compatibility-subset`、
  `runnerMode=hybrid-host-agent-docker-verifier`。
- `ScoreRecord.evidence` 包含 Docker OS/API、镜像 ID、容器 ID、官方 wrapper
  SHA-256、pytest 结果、`score.json`、`reward.json` 和 `agent.patch` 路径。

## 阶段 4：真实 Agent 单题闭环

状态：已完成（2026-11-17）。

实现：

- 使用 `EvalBenchmarkLiveRunnerCli`。
- `toolMode=production`。
- 给 Agent 传入官方 instruction。
- `workspaceRoot` 指向该次 attempt 的宿主工作区。
- Agent 结束后执行 Docker verifier。

验收：

- Agent Trace 文件存在且 schema 校验通过。
- `agent.patch` 存在。
- Docker 测试结果存在。
- `reward.json` 可读取。
- 结果状态明确区分：
  - Agent 失败
  - 测试失败
  - Docker 环境失败
  - verifier 失败

完成结果：

- 真实 Cute Agent 单题运行成功，Trace 通过 `trace.schema.json` 校验，
  `agent.patch`、`results.xml`、`test_output.txt`、`score.json` 和
  `reward.json` 均已落盘。
- 本题 Agent 产出 15 行业务改动，官方 pytest 为 1/3 通过，
  `test_status=partial_pass`、`overall=0.3333`。
- 报告状态为 `failed`，失败分类为 `test_failure`，不是 Docker 环境错误。
- 宿主机 Cute Agent 使用 `ai-agent-eval` 库；Docker 只执行官方测试和 scorer。
- 结果目录：`D:\cute-eval-tmp\workbuddy-hybrid-smoke-v2`。
- 已知限制：Java Live Runner 的导出 Trace 中 `llmInvocations` /
  `toolInvocations` 为空，`run.totalTokens=0`；但 `result.json` 的
  `budgetControl` 记录了 `totalTokens=232733`、`toolCalls=18`。Adapter 已把
  这组数据作为非硬门禁诊断分数写入报告；该差异不影响 Docker 官方测试结果，
  但当前不能把 Trace 中的 Token/工具数当作完整用量依据。

注意：真实模型运行需要可用的 nightly 配置和模型凭据。如果模型不可用，
只完成 fake verifier 和官方 gold patch 验收，不伪造 Agent 成功。

## 阶段 5：扩展 pytest 子集

状态：已完成（2026-11-17）。

实现：

- 从 Code 数据集自动发现全部 `pytest_injected` 任务。
- 仍默认只运行 manifest 中明确列入的任务。
- 允许通过 `--limit` 控制批次。
- 为每个任务记录独立的 workspace、patch 和 verifier 目录。
- 将“已发现题目”和“可执行题目”分开记录：
  - `discoveredTaskIds`：22 道。
  - `taskIds`：19 道。
  - `blockedTaskIds`：3 道，并记录环境阻塞原因。
- 每个任务使用独立运行目录：
  `attempts/<attempt>/<task-id>/result.json`，
  不再让多题共享同一个 `result.json`。
- 工作区 Git 基线开启 `core.longpaths=true`，支持 Django 等长路径仓库。
- 运行前校验 `CODE_EXECUTION_WORKSPACE_ROOTS` 覆盖评测输出目录。
- `start-python.ps1` 支持透传 `-WorkspaceRoots` 给 `start-split.ps1`。
- Docker patch 采集排除宿主工具文件（`.cute`、`__last_source__.py`、
  `input`、`output`、`reports`），剔除官方基线中的 symlink 物化差异，并关闭
  `core.fileMode`，避免 Windows 可执行位漂移污染 Agent patch。

验收：

```powershell
$env:CODE_EXECUTION_WORKSPACE_ROOTS = "D:\cute-eval-tmp"
$env:CODE_SANDBOX_BACKEND = "local"
& .\scripts\start-python.ps1 -WorkspaceRoots "D:\cute-eval-tmp"

uv run cute-evals benchmark `
  --name workbuddybench `
  --split internal `
  --java-mode live `
  --source-dir D:\cute-eval-tmp\workbuddy-bench-main `
  --limit 3 `
  --repeat 1 `
  --out D:\cute-eval-tmp\workbuddy-code-pytest-subset
```

验收标准：

- 3 道题都完成 Agent 执行和 Docker verifier。
- 每道题都有可回看的 Trace、patch、日志和 reward。
- 不能因为一题失败而丢失其他题目的结果。
- 报告明确样本量小，不能作为全量能力结论。

完成结果：

- 官方 `pytest_injected` 自动发现结果为 22 道。
- 22 道官方 gold patch 全量扫描结果：
  - 19 道 `full_pass`、`reward=1.0`。
  - 3 道官方基线阻塞，未纳入 Agent 对照：
    - `bug_fix-medium-incorrect_linenos_on_fstring`：官方环境解析到
      pytest 9，`tests/optional.py` 触发 `PytestRemovedIn10Warning`，
      scorer 报 `build_error`。
    - `feature-hard-fallback_signers_and_switch`：官方 gold patch 无法应用，
      `tests/test_itsdangerous/test_serializer.py` 报 `patch does not apply`。
    - `feature-medium-add_support_for_dependencies`：FastAPI 导入触发
      anyio `DeprecationWarning`，scorer 报 `build_error`。
- 首次 3 题真实运行（无后缀目录）在第二题暴露 Windows 长路径问题，未计入
  能力结论；已通过 `core.longpaths=true` 修复。
- 第二次 3 题真实运行（v2）中 Agent 的 `code_execution` 工作区白名单未覆盖
  评测目录，日志出现 `WORKSPACE_ROOT_NOT_ALLOWED`；该次结果为 2 个
  `test_failure`、1 个 `unverified`，未计入能力结论。
- 第三次 3 题真实运行（v3，`product` 预算）中 3 道题均在约 24 万 Token
  处触发 `EVAL_BUDGET_EXCEEDED`，Docker verifier 未执行，报告为
  `unverified`，未计入能力结论。
- 第四次 3 题真实运行（v4，`capability` 预算）结果：
  - `bug_fix-easy-a_crash_in_local`：1/3，reward `0.3333`。
  - `bug_fix-easy-filtered_relation_queryset_arg`：1/4，reward `0.25`。
  - `bug_fix-easy-invalid_filterwarnings_regex_error`：2/4，reward `0.5`。
  - 3 道题均完成 Agent 执行和 Docker 官方验证，状态均为 `test_failure`，
    无 `unverified`、无 Docker 环境错误。
- 单题复核（v6）确认 `agent.patch` 只包含业务文件，不再包含 `.cute`、
  `__last_source__.py`、`input`、`output` 等宿主工具文件。
- 干净复评分（v8，复用 v4 的 Agent 工作区和 Trace，不重复消耗模型）：
  - 3 道题全部进入官方 Docker verifier，`failed=3`、`unverified=0`。
  - 3 份 `agent.patch` 均不再包含 `.cute`、`__last_source__.py`、`input`、
    `output`、`reports` 或 symlink 物化差异。
- 当前样本只有 3 道题，只能用于内部能力对照，不能当作 WorkBuddy 官方榜单
  成绩，也不能推断 19 道可执行题的整体通过率。

结果目录：

- `D:\cute-eval-tmp\workbuddy-code-pytest-subset-20261117`
  - 长路径失败证据，不是 Agent 能力结论。
- `D:\cute-eval-tmp\workbuddy-code-pytest-subset-20261117-v2`
  - 工作区白名单未覆盖时的运行证据，不是 Agent 能力结论。
- `D:\cute-eval-tmp\workbuddy-code-pytest-subset-20261117-v3`
  - 产品预算触发 `EVAL_BUDGET_EXCEEDED` 的证据，不是 Agent 能力结论。
- `D:\cute-eval-tmp\workbuddy-code-pytest-subset-20261117-v4`
  - 3 题 `capability` 预算真实结果，全部进入官方 Docker verifier。
- `D:\cute-eval-tmp\workbuddy-code-pytest-subset-20261117-v5-one`
  - pathspec 语法错误导致的 verifier 失败证据，不是 Agent 能力结论。
- `D:\cute-eval-tmp\workbuddy-code-pytest-subset-20261117-v6-one`
  - patch 边界修复后的单题复核结果。
- `D:\cute-eval-tmp\workbuddy-code-pytest-subset-20261117-v7-rescore`
  - 3 题干净复评分中间结果，仍保留 symlink 物化差异。
- `D:\cute-eval-tmp\workbuddy-code-pytest-subset-20261117-v8-rescore`
  - 最终 3 题干净复评分结果，patch 审计边界已收口。

预算说明：

- `product` 预算下 3 道题均在约 24 万 Token 处触发
  `EVAL_BUDGET_EXCEEDED`，Docker verifier 未执行；这属于产品预算结果，
  不应与模型能力失败混在一起。
- `capability` 预算将 token/wall/tool/step 统一放大，3 道题都能完成并进入
  官方验证；该结果用于内部能力对照，不能替代产品预算结论。

已知限制：

- Java Live Runner 导出的 Trace 中 `llmInvocations` / `toolInvocations`
  仍为空，`run.totalTokens=0`；真实用量从
  `result.json.turns[0].budgetControl` 读取，并作为非硬门禁诊断分数写入报告。
- `reports` 目录被排除在 Agent patch 之外，但它仍会出现在 Docker 测试工作区；
  官方测试命令未依赖该目录，当前 3 题未受影响。
- 仅支持 `pytest_injected`，不支持 `script_verifier`、
  `repo_understanding` 和 Security 子集。

## 阶段 6：文档、打包和回归

状态：已完成（2026-11-17）。

实现：

- README 增加 WorkBuddy 混合模式入口。
- `pyproject.toml` 增加 manifest 打包项。
- 增加 Docker 不可用、verifier 失败、超时和非法数据的测试。
- 确认没有改变现有适配器的默认行为。

验证命令：

```powershell
cd evals/agent
uv run python -m compileall -q src
uv run pytest -o addopts="" -q tests
```

如果基线存在已知失败，必须记录：

- 失败测试名。
- 失败是否与本次改动相关。
- 改动前和改动后的对比。

完成结果：

- `evals/agent/README.md` 已增加 WorkBuddy 混合模式启动命令、22/19/3
  题量口径和结果目录说明。
- `pyproject.toml` 已包含 `workbuddybench/manifest.yaml` 的 wheel 打包项。
- Docker 不可用、verifier 退出失败、工作区哈希漂移、非法归档链接等测试已覆盖。
- 定向回归结果：`43 passed`。
- 全量回归结果：`338 passed, 3 skipped`。
- `uv build` 成功生成 wheel 和 sdist；wheel 内已确认包含
  `cute_evals/benchmarks/workbuddybench/manifest.yaml`。
- `scripts/start-python.ps1` 语法检查通过，并已实际用
  `-WorkspaceRoots D:\cute-eval-tmp` 启动 1601/1602 服务。

## 10. 文件级改动清单

预计新增：

- `evals/agent/src/cute_evals/benchmarks/workbuddybench/__init__.py`
- `evals/agent/src/cute_evals/benchmarks/workbuddybench/adapter.py`
- `evals/agent/src/cute_evals/benchmarks/workbuddybench/docker_runner.py`
- `evals/agent/src/cute_evals/benchmarks/workbuddybench/manifest.yaml`
- `evals/agent/tests/test_benchmark_workbuddybench.py`
- `evals/agent/tests/test_workbuddybench_docker_runner.py`
- `evals/agent/docs/workbuddybench-hybrid-adapter-plan.md`

预计修改：

- `evals/agent/src/cute_evals/cli.py`
- `evals/agent/pyproject.toml`
- `evals/agent/README.md`

明确不修改：

- WorkBuddy 官方源码和数据集。
- 现有 BIRD/GAIA/Spider2/tau2 题目和评分逻辑。
- Cute-agent Java 主链路。
- 数据库 schema。
- 前端。

## 11. 失败处理和评分状态

状态定义：

- `passed`：Agent 运行成功，官方测试执行成功且 reward 达标。
- `failed`：Agent 运行成功，但官方测试明确失败。
- `unverified`：Docker 不可用、verifier 未执行、reward 无法读取或测试环境损坏。
- `agent-failed`：Agent 没有产出有效工作区或 Trace。

不得混淆：

- 环境错误不能记成模型能力失败。
- 官方测试失败不能因为最终回答看起来正确而改成通过。
- 没有官方 reward 不能伪造 `officialScore=true`。
- 只跑宿主机测试不能标记为 Docker verifier 已验证。

## 12. 安全边界

- 不运行 Security 子集。
- 不执行未知二进制、恶意样本或漏洞服务。
- Docker verifier 只运行 Code 子集的测试命令。
- 所有容器名由固定前缀和本次 run id 组成。
- 只清理本次创建的容器。
- 不执行 `docker system prune` 或按关键字批量删除容器。
- 官方测试输出、镜像 ID、命令和 reward 必须落盘。

## 13. 完成定义

本计划第一阶段完成需要同时满足：

1. `workbuddybench` 已注册到 `cute-evals benchmark`。
2. 单题 `bug_fix-easy-a_crash_in_local` 可从官方数据读取。
3. 宿主机 workspace 可被 Cute Agent 修改。
4. Docker 可从官方 Dockerfile 构建测试环境。
5. 官方 pytest 可在 Docker 内执行。
6. `reward.json` 可读取并映射到 `ScoreRecord`。
7. 报告明确标记为 `compatibility-subset`。
8. 单题 gold patch 验证达到 3/3 通过和 `overall=1.0`。
9. 新增测试通过，现有测试失败基线没有扩大。
10. 结果目录可以被人独立复核。

## 14. 推荐推进顺序

严格按以下顺序提交和验证：

1. 先写 Adapter 数据读取和资产校验测试。
2. 再写 Docker verifier runner 的 fake 测试。
3. 再跑真实 Docker gold patch。
4. 再接 CLI 和报告。
5. 最后才跑真实 Cute Agent。
6. 单题稳定后才扩到多题。

每一步都必须先有可观察的验收结果，再进入下一步。
