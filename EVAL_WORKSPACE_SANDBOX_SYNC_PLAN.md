# 评测工作区、沙箱根目录与 E2B 双向同步重构计划

> 状态：核心实现已完成并完成真实 E2B 单题验证
> 日期：2026-11-14
> 项目：`D:\Java Code\ai-agent\Cute-agent`
> 触发问题：`extended.code.remove` 中 Cute Agent 无法删除本地工作区文件
> 当前沙箱后端：`CODE_SANDBOX_BACKEND=e2b`（见 `cute-tool/.env:78`）
> 本文件保留目标、语义、方案和验收标准；实施状态见下方。

## 0. 一句话结论

当前问题不是“模型不会删除文件”，而是评测链路和工作区同步语义没有闭环：

```text
Java 评测工作区
  -> 没配置 code_interpreter_url
  -> workspaceRoot 又不在沙箱允许根目录内
  -> 即使进入 E2B，远端删除也不会回写本地
  -> 只能依赖 bash/code_execution，而它们没有完整的删除回传
  -> 评测最终检查本地文件仍然存在
```

目标是把“本地工作区”定义为唯一可见的会话文件真相源，把 E2B 沙箱定义为该工作区的临时执行镜像，并让创建、修改、删除三种变化都能双向同步。

### 0.1 实施状态（2026-11-14）

已完成：

- eval profile 补齐沙箱地址，并统一 Java 侧 URL 快速失败。
- 新增 `SessionWorkspaceMirror`、SyncState v2、执行前后 reconcile/delta 和删除双向同步。
- `code_execution` 与 `bash` 都返回 `workspaceSync`、`deletedFiles`，同步冲突有稳定错误码。
- Comparison trace、Scorer 和报告记录沙箱后端、工作区模式和删除同步证据。
- fake E2B、回归测试和真实 E2B 单题均已跑通。

真实验证证据：

- 报告：`evals/agent/.eval-runs/compare/remove-sync-built-20261114-183431/report.html`
- 结果：Cute 与 Codex 均通过 `extended.code.remove`；Cute 使用 2 次工具调用，Codex 使用 1 次。
- 本地 `tmp/remove-me.txt` 已删除，`tmp/keep-me.txt` 内容保持 `keep this file`。
- Trace 中删除回传证据为 `deletedLocal=["tmp/remove-me.txt"]`、`conflicts=[]`。

尚未覆盖：

- 还没有重跑完整 Pilot 和 Extended 数据集，因此当前不能据此下“整体产品胜负”结论。
- 真实 E2B 目前是单题证据；需要再跑多次确认稳定性。

## 1. 当前事实与证据

### 1.1 评测 profile 缺少沙箱地址

- `EvalLiveRunnerCli.java:43` 默认使用 `springProfile="eval"`。
- `application-eval.yml:43` 有 `autobots.autoagent` 配置，但没有 `code_interpreter_url`。
- `application-dev.yml:257` 和 `application-prod.yml:214` 才有 `code_interpreter_url`。
- `CuteConfig.codeInterpreterUrl` 默认是空字符串。
- `CodeExecutionTool.java:126` 直接把空字符串和 `/v1/tool/code_execution` 拼接，得到相对路径。
- OkHttp 收到没有 `http://` 或 `https://` 的 URL，报：

```text
Expected URL scheme 'http' or 'https' but no colon was found
```

这个错误发生在 Python 真正执行之前，所以连 `print(1)` 也会失败。

### 1.2 评测工作区不在允许的沙箱根目录内

`direct_code_execution.py:150-159` 会检查请求中的 `workspaceRoot` 必须位于允许根目录内。

本次评测工作区类似：

```text
evals/agent/.eval-runs/compare/.../workspace
```

而默认允许根目录是 `cute-tool/skilloutput` 及显式配置的沙箱根目录。用本次真实工作区验证时，会得到：

```text
ValueError: workspaceRoot is outside allowed workspace roots
```

这意味着：即使先补上 `code_interpreter_url`，`code_execution` 仍然可能被拒绝。

### 1.3 E2B 当前是“产物流回”，不是完整文件镜像

当前 E2B 同步大致是：

1. 执行前把本地工作区上传到远端会话目录。
2. 执行后对远端目录做快照。
3. 只把远端新增或修改过的文件下载回本地。

`e2b_session_sync.py:426` 的 `download_changed_files()` 只遍历执行后的 `after` 文件集合；执行前存在、执行后消失的文件没有进入删除回传流程。

因此当前行为是不对称的：

```text
本地删除文件    -> 下次推送到远端时可以删除远端文件
远端删除文件    -> 本地文件仍然保留
```

### 1.4 本计划不新增 workspace_delete

工作区工具目前包括：

- `workspace_read`
- `workspace_write`
- `workspace_edit`
- `workspace_list`
- `workspace_glob`
- `workspace_grep`

当前没有独立的本地删除工具，但本次不通过新增 `workspace_delete` 绕过问题。

这项改造的目标是让 `bash` 和 `code_execution` 本身具备完整、可靠的工作区同步语义：

- 删除发生在远端沙箱时，回写本地；
- 删除发生在本地时，在下次推送时删除远端；
- 不依赖额外的本地删除工具补洞。

### 1.5 两种后端行为不同

- `CODE_SANDBOX_BACKEND=local`：Python 子进程的 `cwd` 就是本地工作区，删除会直接作用于本地文件。
- `CODE_SANDBOX_BACKEND=e2b`：远端是本地工作区的副本，当前不会回传远端删除。

如果评测不显式记录后端，“Cute Agent 能删除文件”这个结论就会因部署方式不同而不同。

## 2. 目标与非目标

### 2.1 目标

1. `eval` profile 能正常调用 `cute-tool`，不再因为空 URL 失败。
2. `eval` 评测工作区可以被安全地声明为允许的沙箱根目录。
3. 本地工作区是用户可见的会话文件真相源。
4. E2B 远端工作区是当前会话的临时执行镜像。
5. 以下三种变化在 local 和 E2B 后端下语义一致：
   - 新建文件
   - 修改文件内容
   - 删除文件
6. 远端删除必须能同步到本地；本地删除也必须能同步到远端。
7. `bash` 和 `code_execution` 使用同一套会话工作区同步逻辑，避免两套行为。
8. 任何路径越界、符号链接、并发修改和同步状态损坏都必须显式失败或显式进入冲突状态，不能静默忽略。
9. 评测报告能记录本次使用的沙箱后端、工作区根目录策略、同步结果和删除证据。

### 2.2 非目标

- 不把 E2B 做成通用网盘或跨用户文件同步系统。
- 不把 `.git`、`.venv`、`node_modules`、构建目录和缓存目录纳入业务文件同步。
- 不实现自动三方合并或复杂冲突编辑器。
- 不在本次改造中改变现有确定性 Scorer 的判定标准。
- 不把远端任意路径暴露给 Agent。
- 不新增 `workspace_delete` 或类似的独立本地删除工具。
- 不用“删除远端目录”代替明确的本地工作区删除语义。
- 不修改生产凭证、模型地址或数据库配置。
- 不在本计划阶段修改任何代码。

## 3. 需要先统一的语义

### 3.1 本地工作区

本地工作区是：

```text
<workspaceRoot>
  ├─ 用户和 Agent 可见的业务文件
  ├─ output/
  ├─ input/          # 沙箱输入暂存区
  └─ .cute/          # 内部同步状态
```

规则：

- 本地工作区是 UI、`workspace_*` 工具和评测 `workspaceChecks` 的最终依据。
- 本地工作区根目录必须经过统一路径守卫，不能由模型任意指定。
- 本地工作区不允许符号链接逃逸。

### 3.2 远端会话工作区

E2B 远端会话工作区建议固定为：

```text
<E2B_WORKDIR>/sessions/<sessionId>/
```

它只对应当前会话，不与其他会话或用户共享。

远端工作区不是最终真相源；它只是当前一次或多次沙箱执行的镜像。

### 3.3 受同步管理的路径

建议将工作区路径分成四类：

| 路径类别 | 是否双向同步 | 删除回传 | 说明 |
| --- | --- | --- | --- |
| 普通业务文件，例如 `tmp/**`、`src/**`、根目录文件 | 是 | 是 | 主要工作区内容 |
| `output/**` | 是 | 是 | 生成产物，用户应能看到删除结果 |
| `input/**` | 仅本地到远端 | 忽略远端删除 | 输入暂存区，不应反向删除用户原始文件 |
| `skills/**` | 由 skill 专用同步处理 | 不走通用删除 | 有独立回写和权限边界 |
| `.cute/**`、`.git/**`、`.venv/**`、`node_modules/**`、构建/缓存目录 | 否 | 否 | 内部状态或运行时目录 |

后续实现应把这份规则集中在一个策略对象中，不能在 `code_execution`、`bash` 和测试里各写一份。

### 3.4 删除的语义

一次删除同步应同时包含：

- 远端文件已经不存在；
- 本地文件在同步前存在；
- 该路径属于受管路径；
- 本地文件在本次执行期间没有被其他写入者修改；
- 删除操作有可审计的结果。

删除不是“返回一个空文件”，也不是“写入空内容”。必须真正执行 `unlink` 或原子删除。

### 3.5 冲突的语义

如果远端删除和本地并发修改同时发生，不允许静默选择其中一边而不记录。

推荐默认策略：

```text
无并发本地修改：远端删除优先，删除本地文件
有并发本地修改：不静默删除，返回 WORKSPACE_DELETE_CONFLICT
同步状态缺失：不推断删除，重建基线并返回警告
```

这样能保证正常删除一定同步，同时避免并发写入被无声销毁。

## 4. 目标行为矩阵

| 操作 | local 后端 | E2B 目标行为 |
| --- | --- | --- |
| 本地新建文件 | 本地直接存在 | 上传到远端会话目录 |
| 本地修改文件 | 本地直接存在 | 上传新版本到远端 |
| 本地删除文件 | 本地直接删除 | 推送阶段删除远端旧文件 |
| 远端新建文件 | 本地直接存在（同一进程） | 下载到本地 |
| 远端修改文件 | 本地直接存在（同一进程） | 下载并原子替换本地文件 |
| 远端删除文件 | 本地直接删除（同一进程） | 删除本地对应文件 |
| 远端删除并重建同名文件 | 以最终文件状态为准 | 先识别最终文件为修改，不做删除 |
| 远端删除 `input/**` | 不影响本地 | 忽略回传，保留本地输入文件 |
| 远端删除 `skills/**` | 由 skill 流程处理 | 不走通用文件删除 |
| 同步状态丢失 | 不适用 | 不推断删除，重建基线并告警 |
| 本地和远端同时修改同一路径 | 以最终操作序列为准 | 记录内容冲突，按明确策略处理 |

## 5. 总体架构

```text
Java Agent / EvalLiveRunnerCli
        |
        |  workspaceRoot + sessionId
        v
Cute-agent workspace tools           Cute-tool sandbox service
        |                                      |
        |                                      +-- local backend:
        |                                      |      subprocess cwd = local workspace
        |                                      |
        |                                      +-- E2B backend:
        |                                             SessionWorkspaceMirror
        |                                               - reconcile
        |                                               - push local diffs
        |                                               - execute
        |                                               - collect remote diffs
        |                                               - apply local deletions/updates
        |                                               - persist sync state
        |                                             |
        +---------------------------------------------+
                         local workspace (canonical)
```

### 5.1 建议的模块边界

#### Java 侧

- `WorkspaceRootPolicy` 或等价的路径配置组件：
  - 解析评测工作区根目录。
  - 保证 `workspaceRoot` 在允许范围内。
  - 统一普通会话和 eval 会话的路径规则。

- `CodeExecutionTool`：
  - 执行前检查 `codeInterpreterUrl`。
  - 把 `workspaceRoot` 和 `sessionId` 发送给 cute-tool。
  - 把同步结果透传给 Ledger 和模型。

- `BashTool`：
  - 复用同一套 URL 和 workspace 配置检查。

#### Python 侧

建议保留 `e2b_session_sync.py` 作为低层文件传输原语，并新增一个会话级编排层：

```text
cute_tool/tool/session_workspace_mirror.py
```

职责：

- 加载和校验同步状态。
- 生成本地快照。
- 执行本地到远端推送。
- 执行远端到本地回传。
- 计算删除集合。
- 处理冲突和跳过项。
- 输出统一的 `WorkspaceSyncResult`。

`E2BPythonSandboxExecutor` 和 `bash_sandbox._exec_e2b` 只调用这个编排层，不再各自重复“推送、快照、下载”的顺序。

### 5.2 不建议的方案

以下方案不建议作为最终实现：

1. 只在 `download_changed_files()` 中顺手补几行删除逻辑。
   - 不能处理状态丢失、并发修改、目录删除和 crash recovery。

2. 直接把远端目录挂载成本地目录。
   - E2B 当前没有这个语义保证，且会改变权限和生命周期模型。

3. 每次执行前都清空远端工作区再全量上传。
   - 会丢失远端未回传的产物，也会放大网络开销。

4. 只切到 local 后端绕过 E2B 同步。
   - 可以规避远端同步问题，但不能证明真实产品链路；local 和 E2B 的用户语义会继续分叉。

## 6. 同步状态设计

### 6.1 当前状态文件

当前使用：

```text
<workspace>/.cute/e2b-sync.json
```

它主要记录本地已上传文件的 `size`、`mtimeNs` 和 `sha256`，并带有 `ownerKey`、`sandboxId`、`generation`。

### 6.2 新状态版本

建议升级为显式版本：

```json
{
  "version": 2,
  "ownerKey": "owner",
  "sandboxId": "sandbox",
  "generation": 7,
  "local": {
    "tmp/remove-me.txt": {
      "size": 15,
      "mtimeNs": 123,
      "sha256": "..."
    }
  },
  "remote": {
    "tmp/remove-me.txt": {
      "size": 15,
      "mtimeNs": 456
    }
  },
  "lastRunId": "..."
}
```

设计原则：

- `.cute/` 本身永远不参与业务文件同步。
- `version` 升级必须可向后读取旧格式。
- `sandboxId` 或 `generation` 变化时，默认重建基线，不推断删除。
- 状态文件损坏时，默认重建基线并返回警告。

### 6.3 为什么需要同时保存本地和远端基线

只有本地基线，无法判断远端删除是否发生。

只有远端基线，无法判断本地是否在沙箱执行期间被 UI、子 Agent 或其他工具修改。

两边都保存，才能实现：

```text
远端文件缺失 + 本地文件未变  -> 可以安全删除本地
远端文件缺失 + 本地文件已变  -> 冲突，不能静默删除
```

## 7. 同步算法

### 7.1 执行前：reconcile

每次 `code_execution` 或 `bash` 开始前执行：

```text
1. 解析并校验 localWorkspace 和 sessionId
2. 加载 SyncState
3. 扫描本地文件，得到 LocalSnapshot
4. 扫描远端会话目录，得到 RemoteSnapshot
5. 如果 state 可用：
   - 识别上次未完成的本地删除或远端删除
   - 应用已经确认的删除
6. 如果 state 不可用：
   - 只建立新基线
   - 不从缺失文件推断删除
7. 将 LocalSnapshot 中新建或修改的文件推送到远端
8. 将本地缺失但 state 中存在的文件从远端移除
9. 再次快照，得到一致的 R0 作为本轮执行基线
```

### 7.2 执行中：sandbox

沙箱只负责执行代码或命令。

执行期间可以新增、修改、删除远端文件，但暂不直接写本地。

### 7.3 执行后：计算 delta

```text
R0 = 执行前远端快照
R1 = 执行后远端快照
L0 = 执行前本地快照
L1 = 执行后本地快照
```

计算：

```text
remote_created_or_modified = paths in R1 whose signature differs from R0
remote_deleted             = paths in R0 but not in R1
local_concurrently_changed = paths in L1 whose signature differs from L0
```

### 7.4 应用远端变更

建议顺序：

1. 对 `remote_created_or_modified`：
   - 跳过 `input/`、`skills/`、`.cute/` 和排除目录；
   - 下载到临时文件；
   - 校验本地路径 containment；
   - 原子替换本地目标文件；
   - 记录下载成功。
2. 对 `remote_deleted`：
   - 检查路径是否受管；
   - 检查本地文件是否存在于 `L0`；
   - 检查本地文件在 `L0` 到 `L1` 之间是否未变；
   - 安全时执行本地删除；
   - 不安全时记录 `WORKSPACE_DELETE_CONFLICT`。
3. 对远端删除的目录：
   - 先处理目录下文件；
   - 目录为空时可选删除空目录；
   - 不递归删除未确认路径。
4. 对 `input/`：
   - 远端删除不改变本地输入文件；
   - 返回 `ignoredDeleteScopes`。
5. 对 `skills/`：
   - 交给已有的 skill 同步流程；
   - 不进入普通文件删除统计。

### 7.5 提交状态

只有本地变更全部完成或明确记录冲突后，才更新 `SyncState`。

如果中途崩溃：

- 下次启动重新比较本地和远端；
- 已完成的删除必须幂等；
- 未完成的删除不应被误判成新的远端删除；
- 状态文件不能被写成“已完成但本地尚未处理”的假成功。

### 7.6 建议的伪代码

```python
sync = SessionWorkspaceMirror(local_root, session_id, sandbox)

state = sync.reconcile()
if not state.safe:
    return sync.error("WORKSPACE_STATE_INCONSISTENT", state.reason)

r0 = sync.remote_snapshot()
l0 = sync.local_snapshot()

sync.push_local_changes()
r0 = sync.remote_snapshot()

try:
    execution = sandbox.run_code(...)
finally:
    r1 = sync.remote_snapshot()
    l1 = sync.local_snapshot()

result = sync.apply_remote_changes(
    before_remote=r0,
    after_remote=r1,
    before_local=l0,
    after_local=l1,
)

sync.commit_state(result)
return result
```

## 8. 冲突与数据安全策略

### 8.1 默认规则

| 情况 | 默认处理 |
| --- | --- |
| 远端删除，本地未修改 | 删除本地文件 |
| 远端删除，本地在本次执行期间修改 | 返回冲突，不静默删除 |
| 远端修改，本地未修改 | 下载并原子替换 |
| 远端修改，本地也修改 | 返回 `WORKSPACE_WRITE_CONFLICT` |
| 本地删除，远端未修改 | 删除远端文件 |
| 状态文件缺失 | 重建基线，不推断删除 |
| 状态文件损坏 | 告警并重建基线 |
| 路径越界或符号链接 | 拒绝并记录安全错误 |

### 8.2 冲突策略的取舍

如果追求“远端永远覆盖本地”，实现最简单，但可能把用户在 UI 或子 Agent 中刚改的内容删掉。

如果追求“任何冲突都停止”，最安全，但需要 UI 或上层 Agent 处理冲突。

推荐默认采用“无冲突时远端删除优先；有冲突时显式失败”，原因：

- 正常单 Agent 顺序执行时，删除会真正同步到本地；
- 并发修改不会被静默丢失；
- 冲突会出现在工具结果和轨迹里，便于定位；
- 后续可以在此基础上增加自动合并，而不是先引入数据丢失。

## 9. 配置与工作区根目录改造

### 9.1 Java eval profile

在 `application-eval.yml` 的 `autobots.autoagent` 下补充：

```yaml
autobots:
  autoagent:
    code_interpreter_url: "${AUTOBOTS_AUTOAGENT_CODE_INTERPRETER_URL:http://127.0.0.1:1601}"
```

要求：

- 不在 eval profile 复用 dev/profile 的私有凭证。
- URL 支持环境变量覆盖。
- eval 启动日志只输出“已配置/未配置”，不输出敏感凭证。
- 增加启动时配置校验，缺少必要 URL 时立即产生明确错误。

### 9.2 cute-tool 允许的工作区根目录

建议新增兼容字段：

```text
CODE_EXECUTION_WORKSPACE_ROOTS
```

取值为路径分隔符分隔的多个允许根目录；保留现有 `CODE_EXECUTION_WORKSPACE_ROOT` 作为单根兼容字段。

评测启动时的建议值：

```text
CODE_EXECUTION_WORKSPACE_ROOTS=<repo>/evals/agent/.eval-runs/compare
```

如果评测 runner 每次只使用一个新的运行目录，也可以在启动 cute-tool 时把它设置为本次 run 的父目录。

要求：

- 该配置只允许评测运行目录，不要直接使用整个磁盘根目录。
- 每个 case workspace 仍要做 `resolve()`、containment 和符号链接检查。
- `workspaceRoot` 不能通过 `..`、相对路径或环境变量注入逃逸。

### 9.3 四个进程的配置边界

| 进程 | 配置 | 作用 |
| --- | --- | --- |
| Java eval runner | `application-eval.yml` 的 `code_interpreter_url` | 找到 cute-tool |
| Java eval runner | case 的 `workspaceRoot` | 指定该题本地工作区 |
| cute-tool | `CODE_SANDBOX_BACKEND` | 明确 local 或 e2b |
| cute-tool | `CODE_EXECUTION_WORKSPACE_ROOTS` | 允许 eval workspace 进入沙箱 |
| cute-tool | E2B 配置 | 创建/复用远端会话沙箱 |

### 9.4 配置矩阵

| 环境 | `code_interpreter_url` | `CODE_SANDBOX_BACKEND` | 工作区根目录 |
| --- | --- | --- | --- |
| dev | 已有 | 按开发配置 | `cute-tool/skilloutput` |
| prod | 已有 | 按部署配置 | 共享持久化工作区 |
| eval | 本次补齐 | 明确记录，建议先固定 e2b | eval run 根目录 |

### 9.5 快速失败

`CodeExecutionTool` 在调用远端前必须检查：

- `codeInterpreterUrl` 不为空；
- URL 是 http/https；
- `workspaceRoot` 已解析；
- 沙箱后端需要的配置存在。

错误信息应类似：

```text
codeInterpreterUrl 未配置，无法执行代码
```

而不是暴露 OkHttp 的底层 URL scheme 异常。

## 10. 产品语义与工具接口改造

### 10.1 `code_execution` 返回同步结果

建议在返回体中增加：

```json
{
  "workspaceSync": {
    "backend": "e2b",
    "uploaded": ["src/a.py"],
    "downloaded": ["output/report.pdf"],
    "deletedLocal": ["tmp/remove-me.txt"],
    "deletedRemote": ["tmp/old.txt"],
    "conflicts": [],
    "ignoredDeletes": ["input/source.csv"],
    "stateVersion": 2
  }
}
```

如果存在删除：

```json
{
  "deletedFiles": [
    {
      "relativePath": "tmp/remove-me.txt",
      "source": "remote_sandbox",
      "status": "synced"
    }
  ]
}
```

这样模型、Ledger、评测报告和 UI 都能知道删除是否真的发生。

### 10.2 `bash` 与 `code_execution` 的一致性

两者都必须：

- 使用同一个 `workspaceRoot`。
- 使用同一个 `SessionWorkspaceMirror`。
- 使用同一套删除回传和冲突策略。
- 返回同一结构的 `workspaceSync`。

不能出现“`bash` 删除了本地，`code_execution` 只删除了远端”这种后端差异。

## 11. 重构分阶段计划

### P0：基线与回归保护

目标：先固定现有行为，避免改动误伤。

任务：

- [ ] 为 `extended.code.remove` 增加可重复的本地/E2B 失败复现。
- [ ] 为 `code_execution` 空 URL、空 workspace、越界 workspace 增加断言。
- [ ] 为 `download_changed_files` 增加“远端删除不当前回传”的回归测试。
- [ ] 为 `push_workspace` 增加“本地删除会删除远端”的测试。
- [ ] 记录现有 `SyncManifest` v1 格式和兼容读取行为。
- [ ] 保留现有测试作为基线，不改 Scorer 判定。

验收：

- 测试能在不依赖真实 E2B 的情况下复现当前同步缺口。
- 现有 Python/Java 测试继续通过。

### P1：配置与工作区根目录修复

目标：让评测真正能够进入沙箱，并在进入前清晰失败。

任务：

- [ ] 在 `application-eval.yml` 添加 `code_interpreter_url` 或等价配置。
- [ ] 在 `CodeExecutionTool` 增加 URL 空值/协议快速失败。
- [ ] 在 `BashTool` 与 `CodeExecutionTool` 统一 URL 校验逻辑。
- [ ] 新增 `CODE_EXECUTION_WORKSPACE_ROOTS` 解析，兼容旧的单根配置。
- [ ] 让 eval runner 或启动脚本显式传入 eval workspace 允许根目录。
- [ ] 在启动日志和报告环境信息中记录 backend、workspace root 类型和 URL 是否配置。
- [ ] 增加配置测试：eval workspace 被接受，非允许目录被拒绝。

验收：

- eval 下 `code_execution` 不再报 URL scheme 错误。
- eval 下 case workspace 可以通过根目录校验。
- 未配置 URL 时返回清晰错误，不再触发长时间工具重试。

### P2：抽取统一会话工作区镜像层

目标：消除 `bash_sandbox` 和 `e2b_sandbox_backend` 的重复同步编排。

任务：

- [ ] 定义 `SessionWorkspaceMirror`、`SyncState`、`SyncSnapshot`、`SyncResult`。
- [ ] 将 `push_workspace`、`snapshot_remote_files`、`download_changed_files` 纳入同一个编排对象。
- [ ] 统一受管路径、忽略路径、大小限制和符号链接规则。
- [ ] 设计 `SyncState` v2，并保留 v1 读取兼容。
- [ ] 将 `E2BPythonSandboxExecutor` 改为调用新编排层。
- [ ] 将 `bash_sandbox._exec_e2b` 改为调用新编排层。
- [ ] 保持 local 后端不经过镜像层，但输出同样的同步结果结构。

验收：

- 新增/修改文件的现有行为不回归。
- 两处调用方不再各自实现推送和回传顺序。
- 新增同步测试可在 fake sandbox 上运行。

### P3：实现双向删除同步

目标：远端删除必须回到本地。

任务：

- [ ] 在 `download_changed_files` 或新镜像层计算 `before - after` 删除集合。
- [ ] 按受管路径过滤删除集合。
- [ ] 对本地路径做 containment、符号链接和存在性检查。
- [ ] 对无冲突删除执行安全本地删除。
- [ ] 对并发修改路径返回 `WORKSPACE_DELETE_CONFLICT`。
- [ ] 对 `input/**` 忽略远端删除。
- [ ] 对 `skills/**` 走专用同步，不进入普通删除。
- [ ] 支持目录下文件删除后的空目录清理。
- [ ] 在返回结果中增加 `deletedLocal`、`deletedRemote`、`ignoredDeletes`。
- [ ] 设计 crash 后幂等恢复流程。
- [ ] 增加状态文件损坏、缺失和新 sandbox generation 的保护测试。

验收：

- fake E2B 沙箱中删除 `tmp/a.txt` 后，本地 `tmp/a.txt` 消失。
- 远端删除不会删除本地 `input/` 原文件。
- 远端删除不会越界删除工作区外文件。
- 本地并发修改时，工具返回明确冲突而不是静默成功。

### P4：接入 code_execution 和 bash

目标：两个执行工具使用同一套语义。

任务：

- [ ] `E2BPythonSandboxExecutor` 返回统一 `workspaceSync`。
- [ ] `bash_sandbox` 返回统一 `workspaceSync`。
- [ ] `direct_code_execution` 把同步结果写入响应 envelope。
- [ ] `sandbox_routes` 保持接口兼容，新增字段只做增量扩展。
- [ ] `CodeExecutionResult` / Java DTO 解析新增字段。
- [ ] `CodeExecutionTool` 将这些字段返回给 Ledger 和模型。
- [ ] `BashTool` 同样透传。
- [ ] 对同步失败、删除冲突和后端不可用定义稳定错误码。

建议错误码：

```text
WORKSPACE_ROOT_NOT_ALLOWED
WORKSPACE_STATE_INCONSISTENT
WORKSPACE_DELETE_CONFLICT
WORKSPACE_WRITE_CONFLICT
WORKSPACE_SYNC_FAILED
WORKSPACE_PATH_REJECTED
```

验收：

- 删除操作的工具结果中能看到 `deletedFiles`。
- 删除失败不会伪装成普通成功。
- 原有 stdout、stderr、producedFiles、fileInfo 字段保持兼容。

### P5：评测与报告接入

目标：让评测结论能解释“为什么通过/失败”。

任务：

- [ ] 在 Comparison run metadata 中记录 `sandboxBackend`、`workspaceRootMode`、`deletionSyncEnabled`。
- [ ] 在 trace 中记录 `workspaceSync` 摘要。
- [ ] 扩展确定性 Scorer 的 evidence：本地删除已经发生、删除冲突、同步被跳过。
- [ ] 为 `extended.code.remove` 增加“删除前后文件状态”断言。
- [ ] 为每个后端分别跑 case，不允许把 local 和 e2b 结果混成一组。
- [ ] 报告中明确写出：“本结果使用 E2B / local 后端”。
- [ ] 重跑 Pilot 和 Extended，确认原有通过率没有无故下降。

验收：

- 报告能区分“工具没被调用”“远端删除成功但本地未同步”“本地删除成功”。
- `extended.code.remove` 在两个后端下都有明确的通过/失败证据。

### P6：文档、回滚和发布

任务：

- [ ] 更新 `README.md` 的工作区同步说明。
- [ ] 更新 `evals/agent/README.md` 的 eval backend 和工作区配置。
- [ ] 更新 `application-eval.yml` 注释，说明为什么不复用 dev 配置。
- [ ] 增加配置排障手册，列出 `WORKSPACE_ROOT_NOT_ALLOWED` 的解决方法。
- [ ] 增加回滚开关：若同步异常，可临时回退到只回传产物的旧行为。
- [ ] 明确旧 SyncState 的迁移方式。

验收：

- 新开发者只看文档就能配置 local、e2b 和 eval 三种场景。
- 回滚不会误删已有工作区文件。

## 12. 测试计划

### 12.1 Python 单元测试

- `test_workspace_root_policy_accepts_eval_workspace`
- `test_workspace_root_policy_rejects_outside_workspace`
- `test_code_execution_rejects_blank_code_interpreter_url`
- `test_e2b_remote_delete_removes_local_file`
- `test_e2b_remote_delete_ignores_input_file`
- `test_e2b_remote_delete_with_local_concurrent_write_returns_conflict`
- `test_e2b_directory_delete_removes_children`
- `test_e2b_delete_rejects_path_traversal`
- `test_e2b_delete_rejects_symlink_escape`
- `test_sync_state_v1_is_readable`
- `test_sync_state_corruption_rebuilds_baseline_without_mass_delete`
- `test_new_sandbox_generation_rebuilds_remote_baseline`
- `test_bash_and_code_execution_report_same_sync_shape`

### 12.2 Fake E2B 集成测试

使用 fake sandbox 的 `files`、`commands`、`run_code` 能力覆盖：

- 本地新增 -> 远端存在；
- 本地修改 -> 远端更新；
- 本地删除 -> 远端删除；
- 远端新增 -> 本地新增；
- 远端修改 -> 本地修改；
- 远端删除 -> 本地删除；
- 远端删除和本地修改同时发生 -> 冲突；
- 大文件、跳过目录、特殊文件名、Windows/Linux 路径差异。

### 12.3 Java 测试

- `EvalLiveRunnerCli` 传递 workspaceRoot；
- `CodeExecutionTool` 空 URL 快速失败；
- `CodeExecutionTool` 透传 deletedFiles/workspaceSync；
- Java 侧读取新增响应字段的兼容测试。

### 12.4 端到端评测

先只跑单题：

```powershell
uv run cute-evals compare run `
  --dataset evals/agent/cases/compare/extended.json `
  --agents cute,codex `
  --mode controlled `
  --java-mode live `
  --repeat 1
```

再做重复稳定性：

```powershell
uv run cute-evals compare run `
  --dataset evals/agent/cases/compare/extended.json `
  --agents cute,codex `
  --mode controlled `
  --java-mode live `
  --repeat 3
```

验收重点：

- `extended.code.remove` 至少连续 3 次通过；
- `tmp/keep-me.txt` 始终存在且内容不变；
- `tmp/remove-me.txt` 删除后本地不存在；
- 报告中的删除证据可追溯到工具轨迹；
- 不出现“远端删除成功、本地仍存在”的假通过。

### 12.5 回归测试

- Python 全量测试；
- Java 评测相关测试；
- 两套 compare dataset 的 schema 校验；
- Pilot 与 Extended 原有 case 的通过率对比；
- `git diff --check`；
- 至少一次 local 后端与一次 E2B 后端的完整 run。

## 13. 风险与取舍

### 13.1 删除回传的破坏性风险

远端删除自动删除本地文件，比上传产物更具破坏性。

应对：

- 只对受管路径生效；
- 跳过内部目录和输入暂存区；
- 检查本地是否在并发期间被修改；
- 冲突显式返回；
- 状态损坏时不推断删除；
- 删除操作写入 trace 和 tool result。

### 13.2 本地和远端的时间语义

上传和下载都依赖 `mtime`、大小和哈希。Windows 本地文件系统与 E2B Linux 文件系统的 mtime 语义不同，不能只用 mtime 判断修改。

建议：

- 以本地 `size + mtimeNs + sha256` 作为本地签名；
- 远端快照使用 `size + mtimeNs`，必要时在关键路径加 hash；
- 判断删除时不依赖远端 mtime 不变。

### 13.3 大文件和同步耗时

每次执行都做完整 hash 可能昂贵。

建议：

- 先比较 size 和 mtime，再计算 hash；
- 对超大文件设置显式跳过阈值并记录 `skippedLargeFiles`；
- 不对 `.venv`、`node_modules`、`.git` 等目录做扫描；
- 保持“增量”同步，不做每次全量上传。

### 13.4 并发执行

同一 session 的 `bash`、`code_execution`、UI 写入、子 Agent 写入可能并发。

建议：

- 继续使用 `session_gate`；
- 在同步前后各取一次本地快照；
- 检测到并发本地修改时返回冲突；
- 后续如有需要，再引入跨 Java/Python 的 session 文件锁。

### 13.5 后端差异

local 和 E2B 的故障模式不同：

- local 没有“远端副本”；
- E2B 有远端生命周期、暂停、恢复和 generation；
- E2B 的远端删除必须单独测试，不能用 local 测试代替。

推荐评测矩阵：

```text
Cute + local backend
Cute + E2B backend
Codex CLI
```

只有 Cute + E2B 才能证明真实远端执行链路；local 结果用于对照工具链和编排本身。

## 14. 需要确认的设计决策

在正式写代码前，建议确认以下决策：

1. 评测默认使用哪个后端？
   - 推荐：主报告使用 `e2b`，另跑一条 `local` 作为工具链对照。
2. 远端删除与本地并发修改冲突时怎么处理？
   - 推荐：无冲突删除同步，有冲突显式失败。
3. `output/**` 是否参与删除同步？
   - 推荐：参与，用户应能看到生成产物的删除结果。
4. `input/**` 是否参与删除同步？
   - 推荐：不参与反向删除，只作为沙箱输入副本。
5. SyncState 是否升级到 v2？
   - 推荐：升级，并保留 v1 读取兼容。
6. 是否需要 eval 专用启动参数或脚本？
   - 推荐：需要，启动时显式注入 URL、backend 和允许工作区根目录。

## 15. 完成定义

以下条件全部满足，才算这项重构完成：

- [ ] eval profile 配置完整，没有空 `code_interpreter_url`。
- [ ] eval workspace 在允许根目录内，且路径逃逸测试通过。
- [ ] `CodeExecutionTool` 对错误配置立即失败，不产生长时间重试。
- [ ] E2B 远端新增、修改、删除都能同步回本地。
- [ ] 本地删除能同步到 E2B 远端。
- [ ] `input/**`、`skills/**`、内部目录的删除边界明确。
- [ ] 冲突、状态损坏、路径越界都有明确错误码和轨迹。
- [ ] `code_execution` 和 `bash` 使用同一套同步结果。
- [ ] `extended.code.remove` 在 E2B 下连续通过。
- [ ] `tmp/keep-me.txt` 不被误删或误改。
- [ ] 报告能显示沙箱后端、工作区根目录和删除同步证据。
- [ ] Python、Java 和端到端回归测试通过。
- [ ] README、评测 README、配置注释和排障文档同步更新。

## 16. 实施原则

1. 先修复配置和失败信息，再实现复杂同步。
2. 先覆盖“新增/修改”，再补“删除”。
3. 删除逻辑必须先做 fake sandbox 测试，再接真实 E2B。
4. 任何删除都要有路径 containment 和并发检查。
5. 不在没有基线的情况下推断删除。
6. 不用“写入空文件”伪装删除。
7. 不让 local 和 E2B 的结果混在一个评测结论里。
8. 每次同步都要留下可从 trace 复盘的证据。

---

本文档完成后再进入实现阶段；实现时建议按 P0 -> P1 -> P2 -> P3 -> P4 -> P5 -> P6 推进。
