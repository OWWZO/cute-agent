# Cute-Agent Bench

第一阶段内部评测侧车。执行事实只来自 Java Execution Ledger 导出的 `trace.json`，
不读 SSE，也不新增生产评测接口。

## 组成

- `java_runner.py`：调用 Java 测试专用 Fixture / Live Runner。
- `inspect_runner.py`：Inspect AI 主 Runner，负责一次 Suite Attempt 的 Solver / Scorer 生命周期。
- `scorers.py`：结果、轨迹、预算、恢复、引用、SQL、Artifact 的确定性 Oracle。
- `framework_scorers.py`：DeepEval、agentevals、Ragas 的统一 `ScoreRecord` 适配。
- `report.py`：pass@1、pass^k、失败分类、baseline diff、JSON / HTML 报告。
- `cases/`：`Cute-Agent Bench` 自有 Case。公开 Benchmark 接入后会放在独立 Adapter 中。

## 安装

```powershell
cd evals/agent
uv sync --extra dev --extra frameworks --extra phase3 --extra phase4
```

不安装 `frameworks` extra 时仍可运行，但 Inspect AI、DeepEval、agentevals、Ragas
对应的结果会明确标记为 `unverified` 或走 native fallback，不会默认通过。
BIRD 的官方 execution evaluator 依赖 `phase4` extra。

## PR 门禁

```powershell
uv run cute-evals run --profile pr --suite all
```

PR 固定使用 Fixture 模型脚本，但真实执行 Java ReAct 循环、工具管线、Ledger 和 Artifact。
默认使用 Inspect AI；未安装时自动回退到 native runner。

默认输出：

```text
.eval-runs/pr-all/
├── report.json
├── report.html
└── attempts/01/
    ├── inspect-logs/
    └── traces/<case-id>/trace.json
```

## Nightly / Release

```powershell
uv run cute-evals run --profile nightly --java-mode live
uv run cute-evals run --profile release --java-mode live
```

Nightly / Release 默认重复 3 次，报告包含 `pass@1`、`pass^k`、中位 token、中位耗时、
中位首 token 延迟和失败分类。Live Runner 会显式启用 `eval` Spring profile，
拒绝 `dev` / `prod`，连接由以下环境变量指定的独立评测库：

```text
CUTE_EVAL_DB_URL
CUTE_EVAL_DB_USER
CUTE_EVAL_DB_PASSWORD
```

## 裁判模型

DeepEval、agentevals LLM judge、Ragas 必须显式配置裁判模型：

```powershell
$env:CUTE_EVAL_JUDGE_MODEL = "group/deepseek"
$env:CUTE_EVAL_JUDGE_BASE_URL = "http://127.0.0.1:3425/v1"
$env:CUTE_EVAL_JUDGE_API_KEY = "<your-api-key>"
uv run cute-evals run --profile pr --require-judge
```

规则：

- 没有裁判模型时，结果记为 `unverified`，不伪造分数。
- 支持 OpenAI 兼容的本地 Judge 端点；`base URL / API key / model` 都只从参数或环境变量读取。
- 只有 `hardGate: true` 的 Case 会因 `--require-judge` 阻塞 PR。
- 相同 Trace 和 Case 的 DeepEval 结果使用内容哈希缓存。
- agentevals 确定性 trajectory match 始终执行；LLM 轨迹 judge 首版仍是 advisory。
- 对不严格执行 `json_schema` 的兼容端点，agentevals 会显式要求 JSON 输出，仍通过其公开 Evaluator 解析。

## Baseline

首次建立 baseline：

```powershell
uv run cute-evals run --profile pr --suite all --update-baseline
```

之后默认读取 `baselines/pr-all.json`。出现 pass@k 回退时命令返回非零，
`report.json` 的 `baselineDiff` 会列出 regression、recovery、新增/删除 Case
以及 token / latency 中位数变化。

## 阶段 3：多轮交互、环境与技能

阶段 3 复用同一套 `BenchmarkTask / RunTrace / ScoreRecord / report`，增加：

- `runtime.py`：统一 User Simulator、Task Environment、多轮 Trace 合并和任务级隔离。
- Java Conversation Runner：同一 `sessionId` 下逐轮执行，所有轮共享 conversation 级关联 ID。
- HTTP Tool Proxy：Java 临时工具可以把调用转发给官方 tau² task server。
- GAIA Adapter：固定 Hugging Face revision，附件按 hash 物化，调用官方 `question_scorer`。
- tau² Adapter：固定 `v1.0.1`，官方 airline task、官方 environment、官方
  `UserSimulator` 和官方 evaluator 不变。
- skill-sim：Cute-Agent Bench 自有的本地 skill 模拟评测，不依赖 Docker，不是 SkillsBench 官方成绩。

### 官方全量 split

`cute-evals benchmark` 现在支持显式选择：

```text
--split official-subset  # 默认，保留已验证子集
--split official-full    # 官方完整选择
```

全量结果会写入独立的 run 目录和报告标签，不会覆盖子集结果。

- GAIA：`official-full` 加载固定 revision 的所有 validation 行；不会把 validation 当成隐藏 test。
- tau²：`official-full` 选择 manifest 中列出的全部官方 domain；task id 在内部按
  `<domain>:<official-task-id>` 去重，官方 task server reset 仍使用原始 task id。
- BIRD：`official-full` 选择官方 500 条 Mini-Dev 行。若使用本地数据库快照，
  `CUTE_EVAL_BIRD_DB_ROOT` 下必须具备全部 `<db_id>.sqlite`；首次使用会生成
  `database-lock.json` 锁定哈希。
- Spider2：`official-full` 只有在全部 547 道题的官方 gold、数据库和 evaluator
  都固定后才允许运行；当前本地资产不完整时会直接拒绝，不会静默漏题后冒充全量。

全量命令示例：

```powershell
uv run cute-evals benchmark --name gaia --split official-full --profile release --java-mode live
uv run cute-evals benchmark --name tau2 --split official-full --profile release
uv run cute-evals benchmark --name bird --split official-full --profile release --java-mode live
```

注意：`official-full` 只表示官方完整选择和官方 evaluator，不等于已经生成官网提交包。
当前仓库还没有统一的 `submission` 打包层；不能把 report.json 直接上传冒充官方成绩。

### 内部 production / holdout

`Cute-Agent Bench` 增加三个独立层：

- `production-core`：24 个项目自有的生产型 Case，覆盖多源研究、澄清、危险操作确认、
  会话分页记忆、工具发现、权限降级、参数纠错、上游故障、空数据不编造、
  SQL / RAG / Artifact、无工具与无产物控制、单位换算、冲突资料、
  文件定位和 Skill 干扰项。
- `holdout-core`：16 个私有留出 Case，默认不进入 PR 的 `all`，也不用于 Prompt 调参；
  YAML 默认被 Git 忽略。
- `private-badcase`：从真实 Execution Ledger Trace 人工确认后回流的 Badcase。

PR 的 `--suite all` 已包含 `production-core`，当前主门禁为 48 个 Case；`holdout-core`
单独运行，避免把留出集变成日常调参集。

Case 可以声明 `metadata.role / taskType / lengthBucket / toolCountBucket / risk`，
报告会按这些维度生成 `summary.strata`。同时支持 `subgoals` 和
`referenceToolSequence`，输出 `subgoal_progress`、`route_precision`、
`route_recall`、`route_f1`。其中子目标是硬门禁，路线指标是 advisory，避免把
“参考路线”误当成唯一正确路线。

报告会给出 Wilson 95% 置信区间。Release 前可将验证集和私有 holdout 的报告做一次
泛化审计：

```powershell
uv run cute-evals ops overfit `
  --validation-report .eval-runs\release-production-core\report.json `
  --holdout-report .eval-runs\release-holdout-core\report.json `
  --fail-on-risk
```

审计会检查重复题、克隆题、验证集与 holdout 的 pass@1 / pass^k 差距、token 与耗时差异，
并明确标记样本不足只能作为方向性信号。holdout 结果只用于判断泛化，不回流改题。

2026-11-07 Fixture 实测：

- `--suite all`：48/48，`pass@1=100%`、`pass^k=100%`。
- `production-core` + GEval 裁判：24/24；裁判只评最终答案，工具顺序由确定性
  Scorer 和 agentevals 分别验收。
- `holdout-core`：16/16；与 production 的 `pass^k` 差距为 0，泛化审计为 `no-signal`。

### GAIA

```powershell
$env:EVAL_DATA_HOME = "D:\cute-evals-data"
$env:HF_TOKEN = "<token-with-GAIA-access>"
uv sync --extra dev --extra phase3
uv run cute-evals benchmark --name gaia --profile nightly
```

GAIA 是 gated dataset，不会把 validation 数据提交进仓库。Adapter 只读取固定 revision
的官方数据；附件下载后写入任务 workspace 并记录 SHA-256。没有附件证据时，官方
exact-match 结果标记为 `unverified`，不会伪造通过。

GAIA 的答案契约固定为 `gaia-final-answer-v1`：

- 官方 Question、附件、标准答案和 `question_scorer` 不修改；
- Java Live Runner 在题目后追加统一的 `FINAL_ANSWER: <answer>` 输出要求；
- Adapter 只取最后一个 `FINAL_ANSWER:` 后面的文本交给官方 scorer；
- 报告同时保留 `rawAnswer` 和 `finalAnswer`；缺失契约单独记为 `answer_contract` 失败。

每个 Case 还会把 `maxWallMs`、`maxTotalTokens`、`maxToolCalls` 传给 Java Runner。
超限时 watchdog 调用现有 run cancel，错误码固定为 `EVAL_BUDGET_EXCEEDED`，报告分类为
`budget`；预算超限不能因为最终答案碰巧正确而被判为通过。

2026-11-06 已完成 GAIA Level 1 两题的 live 子集：`2/2` 通过，`pass@1 = 100%`、
`pass^k = 100%`。两题分别消耗 34,647 / 9,517 tokens，耗时 16.879 / 2.639 秒。
报告标记为 `official-subset`，只证明这两道官方题跑通，不是完整 GAIA validation 成绩。

完整 GAIA validation 已登记为延后项，不在当前开发阶段执行。当前 Adapter 仍只提供
`official-subset`；补全 `official-full` 选择、全量附件、工具覆盖和运行预算后，再单独
安排全量运行，不能把子集结果当成完整官方成绩。

### 阶段 4A：MRAG Core

`rag-core` 现在包含 7 个项目自有 MRAG 标注 Case，覆盖直接页面引用、多来源、
版本冲突、表格阈值、图片证据、时间条款和无证据拒答。数据集定义和标签固定在
`cases/rag/dataset.json`，运行前会校验 `cases/rag/core/*.yaml` 的 SHA-256；
改动 Case 但不同时更新 hash 时直接拒绝运行。

运行 PR fixture：

```powershell
uv run cute-evals run --profile pr --suite rag-core
```

评测结果分三层：

- `citation_recall`：检索结果是否包含 gold citation
- `citation_coverage`：最终回答是否引用 gold citation
- `faithfulness`：Ragas 判断回答是否被检索上下文支持

当前 deterministic fixture 为 `7/7`；配置本地 Judge 并加 `--require-judge` 后，
三层指标均通过。无证据 Case 使用 `noEvidence: true`，不会把“没有 gold citation”
误报成召回失败。报告示例：`D:\cute-eval-tmp\rag-core-v5\report.html`。

### 阶段 4B：数据分析内部基准集

`data-core` 现在包含 6 个项目自有 Case，覆盖单表计算、联表聚合、时间窗口、
空结果、NULL 分类和数值聚合。SQLite fixture 固定在
`cases/data/core/fixtures/analytics.sql`，数据集版本、标签和文件 hash 固定在
`cases/data/dataset.json`，运行前由 `data_dataset.py` 校验，Case 或 fixture
改动但没有同步更新 hash 时会直接拒绝运行。

SQL 判定不是“工具调用成功即可”。`sqlOracle` 会在内存 SQLite 中导入 fixture，
只允许单条只读 `SELECT` / `WITH`，开启 `PRAGMA query_only=ON`，并使用 SQLite
authorizer 拒绝写操作；随后比较列名和结果集，支持数值容差及有序/无序比较。
`sql_execution` 检查执行事实，`sql_result` 检查真实数据结果，失败统一归入
`data_oracle`。

运行项目自有数据基准集：

```text
uv run cute-evals run --profile pr --suite data-core
```

2026-11-06 实测：

- deterministic fixture：`6/6` 通过，`pass@1=100%`、`pass^k=100%`
- 本地 Judge + `--require-judge`：`6/6` 通过
- 报告示例：`D:\cute-eval-tmp\data-core-v4\report.html`
- 本地 Judge 报告：`D:\cute-eval-tmp\data-core-v5-judge\report.html`

4B 只完成项目自有的内部数据分析基准集，不代表 BIRD 或 Spider 官方成绩；
BIRD 由下一节的 4C Adapter 单独评测。

### 阶段 4C：BIRD Mini-Dev 2026 官方子集

BIRD Adapter 使用 2026-10 更新的官方 Mini-Dev SQLite 数据，不修改题目、evidence、
gold SQL、数据库或 evaluator：

- Hugging Face revision：
  `5b256b5a9c17478f06ed6912612e9a14d17065c8`
- `mini_dev_sqlite.jsonl` SHA-256：
  `926af2a0d1b47e5d0d15a7aa8a34d4721007f4550a881555b6a89496b54a2606`
- 官方代码 commit：
  `1c49a375e76c471e218564da3f4617513d33e371`
- 官方 EX evaluator：`evaluation/evaluation_ex.py` 和
  `evaluation/evaluation_utils.py`，文件 SHA-256 固定在 manifest
- 首轮固定子集：`0, 1, 49, 50, 149, 194, 195, 196`
- 覆盖数据库：`california_schools`、`card_games`、
  `debit_card_specializing`、`financial`
- 覆盖难度：simple / moderate / challenging

BIRD 的官方数据库以 OCI 镜像发布。Adapter 不要求 Docker daemon，而是直接读取固定
镜像中 `COPY seed.sqlite` 的 OCI layer，同时校验：

1. 官方镜像 digest。
2. `seed.sqlite` layer SHA-256。
3. 解压后 SQLite 文件 SHA-256。

每个 task 都复制一份独立数据库快照。Java Agent 只获得三个临时工具：

- `list_tables`
- `describe_table`
- `sql_query`

SQL runtime 使用只读 URI、`PRAGMA query_only=ON` 和 SQLite authorizer，写语句不会
进入数据库。最终成绩取 Execution Ledger 中的 `submit_sql`，调用固定版本官方
`calculate_ex` 比较实际结果集与 gold SQL 结果集。`bird-final-sql-v1` 只约束
“如何提交最终 SQL”，不修改官方题目。

运行：

```powershell
uv sync --extra dev --extra phase4
$env:EVAL_DATA_HOME = "D:\cute-evals-data"
$env:CUTE_EVAL_DB_URL = "jdbc:mysql://127.0.0.1:3306/ai-agent-eval"
$env:CUTE_EVAL_DB_USER = "<user>"
$env:CUTE_EVAL_DB_PASSWORD = "<password>"
uv run cute-evals benchmark --name bird --profile nightly --java-mode live --repeat 1
```

如果已经有可信的离线官方 SQLite 快照，可用
`$env:CUTE_EVAL_BIRD_DB_ROOT` 指向目录；文件名必须是 `<db_id>.sqlite`，Adapter
仍会逐个校验 manifest 中的 SHA-256。

2026-11-06 验证结果：

- gold-to-gold 官方 EX 自检：`8/8` verified。
- live 子集首轮：`6/8`，`pass@1=75%`，`pass^k=75%`。
- live 子集 `repeat=3`：`pass@1=87.5%`，`pass^k=75%`；6 题稳定通过，
  task `0` 稳定失败，task `49` 不稳定。
- task `0`：模型答案语义接近，但实际 SQL 结果列和过滤条件不满足官方 EX。
- task `49` 的 3 次官方 EX 为 `2/3`；第 3 次最终 SQL 正确，但消耗
  `97,263 tokens`、21 次 tool call，超过 `60,000 tokens / 20 calls` 预算，
  因此按预算门禁判失败。
- 通过任务中，单题消耗示例为 14,940 到 67,846 tokens，耗时约 11.2 到 38.4 秒。
- 首轮报告：`D:\cute-eval-tmp\bird-live-8\report.html`
- 三次重复报告：`D:\cute-eval-tmp\bird-live-8-r3\report.html`
- 数据集 hash：
  `sha256:bf49e8e5e9e25256779b0e2da594979200be2797af4cb9a0f83d172ac4edbf44`

报告只能标记为 `official-subset`。它证明这 8 道官方题的真实 Agent 链路和官方
Oracle 已跑通，不代表 BIRD Mini-Dev 500 题全量成绩。

### 阶段 4D：Spider2-Lite 官方 SQLite 子集

Spider2 Adapter 只接官方 Spider2-Lite 的本地 SQLite 子集，不接需要 BigQuery 或
Snowflake 账号的题，也不修改官方题目、gold SQL、gold CSV 或比较规则：

- 官方代码 commit：
  `cafb867313aab4e674652054198f383cf4018943`
- `spider2-lite.jsonl` SHA-256：
  `4ba48916576fbd60311a2478c6d4550b5d8cf3fcbc512457ea493b5941ca009d`
- 官方 `evaluate.py` SHA-256：
  `e93624e4ffa00e51c67bdee1d9e42b534630087cb453270c0dedc0c73e618480`
- 官方 `spider2lite_eval.jsonl` SHA-256：
  `5113ddaea6107492c08a898241a9f69ec99b03bc60d014e9f0b12ec59fa28970`
- 官方 SQLite 数据库包 SHA-256：
  `d56acf7c9d89be4bdf1f4f1281f4a03d91735f1858f6d6d0cfe0f9d562e3a94f`
- 固定子集：
  `local004, local008, local019, local022, local039, local058, local078, local199`
- 覆盖数据库：`E_commerce`、`Baseball`、`WWE`、`IPL`、`Pagila`、
  `education_business`、`bank_sales_trading`、`sqlite-sakila`

Adapter 会校验官方数据、evaluator、gold SQL、gold CSV 和每道题所用数据库的
SHA-256。每个 task 使用独立数据库副本，Java Agent 只获得
`list_tables`、`describe_table`、`sql_query`、`submit_sql` 四个临时工具；
SQL runtime 继续使用只读 URI、`PRAGMA query_only=ON` 和 SQLite authorizer。

官方 `evaluate.py` 顶层依赖 BigQuery，不能直接 import 全文件。Adapter 先校验
evaluator hash，再只加载其中官方的
`extract_sql_query`、`compare_pandas_table`、`compare_multi_pandas_table`
函数；比较算法、`condition_cols`、`ignore_order` 和 `1e-2` 数值容差都保持官方
实现不变。最终成绩取 Execution Ledger 中最后一次 `submit_sql`。

运行：

```powershell
uv sync --extra dev --extra phase4
$env:EVAL_DATA_HOME = "D:\cute-evals-data"
uv run cute-evals benchmark --name spider2 --profile nightly --java-mode live --repeat 1
```

目录约定：

```text
$EVAL_DATA_HOME\spider2-lite\official\spider2-lite.jsonl
$EVAL_DATA_HOME\spider2-lite\official\evaluation_suite\
$EVAL_DATA_HOME\spider2-lite\local_sqlite.zip
$EVAL_DATA_HOME\spider2-lite\databases\<db>.sqlite
```

也可用 `CUTE_EVAL_SPIDER2_ROOT`、`CUTE_EVAL_SPIDER2_DB_ROOT` 和
`CUTE_EVAL_SPIDER2_DB_ARCHIVE` 指向已校验的离线文件。若选择了带
`external_knowledge` 的官方题但对应文档不可用，官方结果会标记为 `unverified`，
不会伪造通过。

2026-11-06 验证结果：

- gold-to-gold 官方结果集自检：`8/8` verified。
- live `repeat=1`：`7/8`，`pass@1=87.5%`，`pass^k=87.5%`，无 `unverified`。
- task `local019` 的官方结果集正确，但消耗 `68,762 tokens / 20 tool calls`，
  超过 `60,000 tokens` 预算，并同时到达 20 次工具调用上限，因此整体判失败。
- 其余 7 题单题消耗 15,527 到 36,189 tokens，耗时约 13.5 到 38.5 秒。
- 报告：`D:\cute-eval-tmp\spider2-live-8\report.html`
- 数据集 hash：
  `sha256:37a8aea86736d01609bf5b420f2a01f6e84f89f8a6f17ce363ee759535cb9a32`

报告只能标记为 `official-subset`。它证明 8 道官方 Spider2-Lite SQLite 题的真实
Agent 链路和官方 Oracle 已跑通，不代表 Spider2-Lite 547 题或 135 道 SQLite 题
全量成绩。

### tau²-bench

tau² 需要 Python 3.12+。先准备固定版本的官方源码目录：

```text
$EVAL_DATA_HOME\tau2\v1.0.1\source
```

Adapter 也可以从固定 GitHub tarball 自动下载并校验 SHA-256；建议设置
`$env:GITHUB_TOKEN` 避免 GitHub API 匿名限流。

然后配置独立评测库、被测模型和用户模拟器：

```powershell
$env:CUTE_EVAL_TAU2_PYTHON = "C:\path\to\python312\python.exe"
uv run cute-evals benchmark `
  --name tau2 `
  --profile nightly `
  --user-simulator-model "group/deepseek" `
  --user-simulator-base-url "http://127.0.0.1:3425/v1" `
  --user-simulator-api-key "<key>"
```

每轮 Java 调用通过 task server 执行官方 domain tool；用户模拟器使用官方
`tau2.user.UserSimulator`，不是项目自造的近似 prompt。最终成功与否只取官方
evaluator 的 reward，不把“用户说完成了”当成通过。

task server 的 stderr 会由后台线程持续消费。不要把 stderr 重新改回无人读取的
`PIPE`，否则长会话写满管道会制造假的工具超时。

2026-11-06 已实测 airline 官方子集 task `0 / 1 / 3`。首轮 3/3 通过；
随后 2 次重复的报告为 `pass@1=2/3`、`pass^k=2/3`，task `0` 有一次
因模型违规取消被官方 DB 终态 Oracle 判 0。报告为 `official-subset`，
不能写成完整 tau² 成绩。

### skill-sim

官方 SkillsBench 已从 `cute-evals benchmark` 入口退役，不再要求 Docker、官方镜像
或官方 verifier。技能评测改为 `Cute-Agent Bench` 自有的 `skill-sim`：

```powershell
uv run cute-evals run --profile pr --suite skill-sim
```

首批 Case 位于 `cases/skill/core/`，覆盖：

- 正确选择需要的 skill。
- 多个候选中的干扰项抵抗。
- skill 加载失败后的恢复。
- 不需要 skill 时的控制组。
- skill 加载次数、步骤和 token 预算。

PR 使用 Java Fixture Runner：脚本固定模型动作，但 Java 的 ReAct 循环、工具管线、
Execution Ledger 和 Trace 仍然真实执行。`skill_selection` 和 `skill_load_count`
是确定性 Oracle，直接检查 `skill_view` 的 `name` 参数和加载次数。

`skill-sim` 的报告只能标记为 Cute-Agent Bench 自有结果，不能写成 SkillsBench
官方成绩。Nightly / Release 的 live 模型能力仍需单独建立，不能把 Fixture 结果
冒充真实模型结论。

## 真实 Ledger 轨迹回流

阶段 1.5 的数据访问边界固定为：

```text
Execution Ledger
  -> Java ExecutionLedgerQueryService
  -> Java EvalTraceExportCli（只读导出端口）
  -> sanitized trace.json
  -> Python 评测侧车
```

Python 不连接数据库、不读取 Ledger 表、不依赖 JDBC/MySQL 驱动；`ledger-export`
只负责启动 Java 导出器并接收导出的文件。数据库配置属于 Java `eval` profile。

导出前设置给 **Java 导出进程** 使用的只读连接：

```powershell
$env:CUTE_EVAL_DB_URL = "jdbc:mysql://127.0.0.1:3306/ai-agent-eval"
$env:CUTE_EVAL_DB_USER = "readonly"
$env:CUTE_EVAL_DB_PASSWORD = "<password>"
```

按 Run、Session 或时间窗口导出已完成 Run：

```powershell
uv run cute-evals ledger-export --request-id <request-id> --out ..\..\private-traces
uv run cute-evals ledger-export --session-id <session-id> --out ..\..\private-traces
uv run cute-evals ledger-export `
  --from 2026-11-01T00:00:00 --to 2026-11-07T00:00:00 `
  --out ..\..\private-traces
```

导出的 `trace.json` 已脱敏：

- API key、Authorization、Cookie、密码和 Token 不落盘。
- 手机号、邮箱、身份证和明显地址不落盘。
- 内网地址、绝对路径和敏感下载链接不落盘。
- Artifact 只保留关联 ID、类型、大小、hash 和相对引用，不复制原始文件。
- 导出后的 JSON 会再执行一次残留敏感模式扫描，扫描失败则拒绝写入。

从已脱敏 Trace 生成 Badcase 草稿：

```powershell
uv run cute-evals case-draft `
  --trace ..\..\private-traces\<request-id>\trace.json `
  --out cases\private\badcase\<case-id>.yaml
```

草稿的 `expected` 永远为空，`oracleReviewed` 为 `false`。必须人工补齐 Oracle 和预算；
只有需要重新执行 Agent 时才补 `script`。真实 Badcase 默认被 Git 忽略，不进入公开 Benchmark。

## A/B 实验内核

阶段 2.5 增加了独立的 A/B 实验入口。它不另造 Runner，而是在现有
`EvalCase`、Java ReAct 执行、Execution Ledger 和 `ScoreRecord` 上增加：

- `base` / `candidate` 双 Arm。
- 每个 `(arm, model, case, repetition)` 独立 Cell。
- Cell 子进程、临时 HOME、file-backed crontab 和宿主副作用 shim。
- resume-safe 原子结果、基础设施重试和连续错误 Fuse。
- 同一分母、`pass@1` / `pass^k`、token / turn / tool 指标和异常审计。
- `ship / block / needs-more-runs / inconclusive` 机器建议。

阶段 2.5 首批内部任务族位于 `cases/ab/`，每族 6 个 Case：

```text
hostile-files
tool-discovery
session-recall
compaction-recall
clarify-multiturn
```

运行本地 deterministic A/B：

```powershell
cd evals/agent
uv run cute-evals ab run `
  --experiment experiments/tool-discovery-ab.yaml `
  --out .eval-runs/ab/tool-discovery
```

五个任务族的完整 deterministic smoke 使用：

```powershell
uv run cute-evals ab run `
  --experiment experiments/stage2.5-fixture-ab.yaml `
  --out .eval-runs/ab/stage2.5-fixture
```

中断后续跑、重建报告和查看单个 Cell：

```powershell
uv run cute-evals ab resume --out .eval-runs/ab/tool-discovery
uv run cute-evals ab report --out .eval-runs/ab/tool-discovery
uv run cute-evals ab audit --out .eval-runs/ab/tool-discovery --cell <cell-key>
```

本地 `source: local` Arm 适合 PR 和离线实验；真实代码 A/B 使用：

```yaml
base:
  source: git
  ref: origin/main
candidate:
  source: git
  ref: HEAD
```

`git` Arm 默认要求候选工作树干净，并在 `.eval-runs/ab/<experiment>/worktrees/`
下创建独立 worktree。公开 Benchmark 数据仍然只读；阶段 2.5 的陷阱题只属于
`Cute-Agent Bench`。

Live A/B 的数据库隔离通过 Arm 环境注入，例如：

```yaml
base:
  source: git
  ref: origin/main
  env:
    CUTE_EVAL_DB_URL: jdbc:mysql://127.0.0.1:3306/ai-agent-eval-base
candidate:
  source: git
  ref: HEAD
  env:
    CUTE_EVAL_DB_URL: jdbc:mysql://127.0.0.1:3306/ai-agent-eval-candidate
```

每个 Cell 还会获得独立 `CUTE_EVAL_RUN_PREFIX`、`CUTE_EVAL_DB_SCHEMA` 和
`CUTE_EVAL_CELL_KEY`，用于数据库行级追踪和清理。PR Fixture 不连接数据库。

## Replay

`trace-replay` 只复用已有 Trace 重算评分，不重跑 Agent：

```powershell
uv run cute-evals trace-replay `
  --case cases\private\badcase\<case-id>.yaml `
  --trace ..\..\private-traces\<request-id>\trace.json `
  --out ..\..\replay\<case-id>
```

`private-replay` 从全部私有 Case 的 `replay.baselineTrace` 读取 Trace，生成一份统一报告：

```powershell
uv run cute-evals private-replay --out ..\..\.eval-runs\private-badcase
```

已确认的 Badcase 在回放中显示 `failed` 是预期结果，表示历史失败被稳定复现。

`counterfactual-replay` 保持 Case 输入不变，换当前模型重新跑一轮并比较工具序列、成功率、
Token、耗时、首 Token 延迟和最终结果：

```powershell
uv run cute-evals counterfactual-replay `
  --case cases\private\badcase\<case-id>.yaml `
  --baseline-trace ..\..\private-traces\<request-id>\trace.json `
  --out ..\..\replay\counterfactual-<case-id> `
  --java-mode live
```

## 阶段 8：持续评测运营

阶段 8 第一版把已有 `report.json`、A/B 报告、Trace 和 Badcase 草稿串成持续运营
闭环。该层不使用数据库，历史是 append-only JSONL，所有结论都可以回溯到原始报告。

### 历史与趋势

记录一次标准评测：

```powershell
uv run cute-evals ops record `
  --report .eval-runs\pr\report.json `
  --history ..\..\.eval-runs\ops\history.jsonl `
  --series react-core `
  --variant deepseek
```

同一 `series + variant` 的多次运行会进入同一条趋势线。生成趋势报告：

```powershell
uv run cute-evals ops trend `
  --history ..\..\.eval-runs\ops\history.jsonl `
  --out ..\..\.eval-runs\ops\trend `
  --limit 20
```

趋势会报告：

- `pass@1` / `pass^k` 变化。
- 逐 Case 的 regression / recovery。
- token 中位数变化；默认上涨超过 20% 记为成本回归。
- wall time 中位数变化；默认上涨超过 25% 记为延迟回归。
- 失败分类和最近运行记录。

加 `--fail-on-regression` 可作为 nightly 的质量门禁。

### 模型 / Prompt / 工具矩阵

每个 A/B 实验仍然只负责一个隔离变量。多个已完成实验可以汇总为矩阵：

```powershell
uv run cute-evals ops matrix `
  --reports `
    .eval-runs\ab\prompt-a\report.json `
    .eval-runs\ab\tool-schema-b\report.json `
    .eval-runs\ab\model-c\report.json `
  --out ..\..\.eval-runs\ops\matrix
```

矩阵报告统一展示 base/candidate 分数、`pass@1` / `pass^k` delta、token ratio、
异常数量和 `ship / block / needs-more-runs / inconclusive` 建议。

### 发布关联

发布记录不会自动部署任何东西，只把一次评测报告与版本、commit、模型绑定：

```powershell
uv run cute-evals ops release `
  --report .eval-runs\release\report.json `
  --out ..\..\.eval-runs\releases\v1.0.0 `
  --version v1.0.0 `
  --commit <git-commit> `
  --model group/deepseek
```

默认硬门禁包括：无 failed、无 unstable、无 unverified、存在 baseline、无 baseline
regression、`pass^k` 不低于指定值。任一项失败时命令返回非零，并输出
`release-record.json` / `release-record.md`。

### Badcase 审核入库

Badcase 仍然先由 `case-draft` 生成，不能自动变成 regression。人工补齐
`expected` 并把 `replay.oracleReviewed` 改为 `true` 后执行：

```powershell
uv run cute-evals case-approve `
  --draft cases\private\badcase\<case-id>.yaml `
  --out cases\private\badcase\<case-id>.yaml `
  --reviewer <name> `
  --notes "oracle confirmed"
```

审批后 Case 默认变成 hard gate；如果只想观察，加 `--advisory`。没有确定性 Oracle、
没有人工审核、或目标文件已存在时都会拒绝。

2026-11-06 运营 smoke：

- 3 个已有 A/B 实验成功汇总为矩阵。
- Spider2 的 `7/8` 报告被 release gate 判为 `block`，原因是存在 failed 且没有 baseline。
- PR fixture 报告在完整 6 项检查下判为 `ship`，证明正负 gate 都可达。
- 产物示例：`D:\cute-eval-tmp\ops-demo\trend\trend.html`、
  `D:\cute-eval-tmp\ops-demo\matrix\matrix.html`、
  `D:\cute-eval-tmp\ops-demo\release-spider2\release-record.json`。

阶段 8 第一版没有接入 Langfuse / Opik / Phoenix；这些保留为后续可选增强。
线上 Ledger 采样和每日调度现在由 Python 侧的 `ops sample` 与 `ops schedule` 提供，
不注册 Windows Task Scheduler。运营历史、Badcase 和 Trace 默认不进入公开数据。

### 线上 Ledger 自动采样与健康审计

`ops sample` 仍然只通过 Java `ledger-export` 只读导出，不直连数据库，也不调用 SSE。
Java 按 `[from, to)` 时间窗口、状态和 keyset 游标分批查询，最多导出 500 条已结束 Run；
默认状态是 `SUCCESS / FAILED / TIMEOUT / STOPPED`，不会导出 `RUNNING` 或 `WAITING_INPUT`。

默认配置在 `config/ops-sample.yaml`：扫描最近 24 小时、最多候选 300 条、最多挑 20 条问题样本，
再用固定 seed 挑 10 条健康对照。审计覆盖 Run 状态、空终答、预算标记、工具失败率、重复工具调用、
总 Token、总耗时和首 Token 延迟。这里的阈值用于发现可疑 Trace，不是任务正确性的 Oracle。

手动运行：

```powershell
$env:CUTE_EVAL_DB_URL = "jdbc:mysql://127.0.0.1:3306/ai-agent-eval"
$env:CUTE_EVAL_DB_USER = "readonly"
$env:CUTE_EVAL_DB_PASSWORD = "<password>"
uv run cute-evals ops sample `
  --config config/ops-sample.yaml `
  --from 2026-11-06T00:00:00 `
  --to 2026-11-07T00:00:00 `
  --out D:\cute-eval-tmp\ops-daily\2026-11-07
```

不传 `--from/--to` 时按配置的 `lookbackHours` 计算窗口。产物包括：

- `export/`：Java 脱敏后的只读原始导出副本。
- `sample-manifest.json`：窗口、配置、数据来源 hash、seed、选中样本和 Badcase 草稿清单。
- `audit-report.json` / `audit-report.html`：候选总体的状态、失败类别、工具成功率、Token 和延迟分布。
- `samples/`：按“问题样本 / 健康对照”分层抽取的 Trace。
- `drafts/`：问题 Trace 对应的 Badcase 草稿，Oracle 为空、`oracleReviewed=false`。

真正的定时循环由 Python 进程自己维护：

```powershell
uv run cute-evals ops schedule `
  --config config/ops-sample.yaml `
  --enable `
  --at 02:30 `
  --timezone Asia/Shanghai `
  --out-root D:\cute-eval-tmp\ops-daily
```

自动调度默认关闭：配置里的 `schedule.enabled` 是 `false`。不加 `--enable` 时，命令只打印
“disabled by default”并退出，不会常驻、不会连接数据库。`--disable` 可以强制关闭配置中临时开启的
调度。显式传 `--enable` 后进程才需要保持运行；按 `Ctrl+C` 停止。

它启动时会校验 `CUTE_EVAL_DB_URL / USER / PASSWORD`，
缺少任一项直接失败。每次运行都串行执行，不会重叠；输出目录用 UTC 时间命名，窗口由配置的
`lookbackHours` 决定。`--run-now` 可以先执行一轮再进入等待。

调度状态写在 `<out-root>\scheduler-state.json`，包括下一次运行时间、最近开始/完成时间、
最近成功时间、最近输出目录和最近错误。运行失败会保留 `status=error` 与 `lastError`，进程继续等待
下一周期，不会把失败静默写成成功。旧运行只清理包含本工具 `sample-manifest.json` 且超过保留天数的
目录，不会递归删除输出根目录中的其他内容。

这是单进程调度器；不要同时启动两个 `ops schedule` 指向同一个 `out-root`。以后部署到容器或常驻
服务时，只需让一个 Python 进程持续运行该命令，不需要改评测逻辑。

重要：采样报告是“运行健康审计”，不是成功率评测。自动生成的候选 Badcase 不能直接进 regression；
必须人工补上确定性 `expected` Oracle、设 `replay.oracleReviewed=true`，再运行 `case-approve`。

## 边界

- `react-core`、`rag-core`、`data-core` 都是项目专属 Benchmark，不冒充公开 Benchmark。
- 公开 Benchmark 后续必须原样使用官方题目、数据、标签、工具定义和 Oracle。
- 需要改题、补题或改判分的测试只能进入 `Cute-Agent Bench`。
- Fixture 只验证 Runtime 契约和评测基础设施；真实模型能力必须看 Nightly / Release。
- 工具故障报告区分 `timeout`、`permission`、`invalid_arguments`、
  `malformed_payload`、`rate_limit` 和 `upstream`，恢复成功也不隐藏故障事实。
