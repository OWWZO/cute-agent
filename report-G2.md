# Cute-agent critical G2 修复报告

分支：`OWWZO/crit-G2`

代码提交：`ffe843ad fix(infrastructure): 修复并发建行、心跳恢复、投影回滚与目录 SQL 注入`

说明：G2 清单共 5 条，全部核实成立并已修复。另按任务书排除项记录 1 条过期 finding。本报告在代码提交完成后生成，未 push、未 rebase、未 amend。

## 逐条结果

### 1. 子会话并发建行 - 已修

文件：

- `Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/adapter/port/SubAgentSessionPortAdapter.java`
- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/ledger/IExecutionLedgerWriteRepository.java`
- `Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/adapter/repository/ExecutionLedgerWriteRepository.java`
- `Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/dao/cute/IDialogueSessionLedgerDao.java`
- `Cute-agent-app/src/main/resources/mybatis/mapper/dialogue_session_ledger_mapper.xml`

改前：`ensureChildSession` 先 `querySessionBySessionId`，查不到再调用全量 `upsertSession`。两个并发派发都可能先看到“不存在”，随后都执行全量 upsert；后写入者会覆盖身份列和 `run_count`，破坏幂等保证。与已启动 run 的 `upsertSessionOnRunStart` 竞争时也可能丢失运行计数。

改后：新增 `insertSessionIfAbsent`，SQL 使用 `INSERT ... ON DUPLICATE KEY UPDATE session_id = VALUES(session_id)`。冲突时只写回相同 `session_id`，不更新任何既有业务列。`ensureChildSession` 不再先查再插，直接依赖数据库唯一键完成原子建行。

影响谁：并发子 Agent 派发、子会话续跑、执行账本 `run_count` / 身份列，以及依赖子会话数量的侧栏展示。修复后重复派发保持首次落库值，不再重置计数。

回归测试：`Cute-agent-app/src/test/java/org/wwz/ai/test/domain/SubAgentSessionPortRaceTest.java`。

- 修复前：两个并发线程均执行插入，断言 `insertCount == 1` 失败。
- 修复后：仅创建 1 行，`insertCount == 1`、`rows.size() == 1`。
- 原有幂等测试 `SubAgentSessionPortAdapterTest` 同时通过，确认续跑不重置 `run_count` 和身份列。

### 2. QQ `RESUMED` 后心跳恢复 - 已修

文件：`Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/channel/qqbot/QqBotWsClient.java`

改前：`RESUMED` 分支只清零重连次数并打日志，没有重新启动心跳。Resume 复用 session，但旧 socket 的心跳任务已随连接失效，连接可能在没有心跳的情况下静默失效。

改后：`RESUMED` 分支调用 `startHeartbeat()`。`startHeartbeat()` 保存 `ScheduledFuture<?>`，启动新任务前取消旧任务；`stop()` 关闭调度器并清空 future，避免重复心跳任务泄漏。

影响谁：QQ Bot 绑定连接、Resume 后的消息接收与长连接存活；影响依赖 QQ 渠道收发消息的用户。

回归测试：`Cute-agent-infrastructure/src/test/java/org/wwz/ai/infrastructure/channel/qqbot/QqBotResumeHeartbeatTest.java`。

- 修复前：临时移除 `RESUMED` 分支的 `startHeartbeat()` 后，2 秒内未观察到心跳帧，测试失败。
- 修复后：`RESUMED` 后收到 `op=1` 心跳帧，测试通过。

### 3. 工作记忆投影原子替换 - 已修

文件：`Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/cute/service/impl/SessionWorkingMemoryServiceImpl.java`

改前：`replaceReadyProjection` 先 `markReadyInvalidBySessionIdAndScope` 把旧 READY turn 全部标为 INVALID，再插入新 turn 和新消息；多步写入没有事务边界。若新消息插入失败，异常又被 catch 吞掉，旧投影已失效且新投影未落库，造成工作记忆丢失。

改后：方法增加 `@Transactional(rollbackFor = Exception.class)`；catch 记录日志后重新抛出异常，让事务边界回滚。失败时旧 READY 状态保留，同时调用方能看到失败并决定降级。

影响谁：会话压缩、工作记忆投影替换、后续上下文组装；影响需要跨轮保留上下文的用户会话和 Agent 运行。

回归测试：`Cute-agent-app/src/test/java/org/wwz/ai/test/infrastructure/SessionWorkingMemoryReplaceAtomicityTest.java`，使用真实 H2 与 Spring 事务代理，并模拟消息插入失败。

- 修复前：异常被吞，旧 READY turn 未回滚，测试失败。
- 修复后：异常向调用方上抛，旧 READY 状态恢复，新 turn 未残留。

### 4. ClickHouse 目录 SQL 注入 - 已修

文件：

- `Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/dataquery/jdbc/catalog/AbstractJdbcCatalog.java`
- `Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/dataquery/jdbc/catalog/clickhouse/ClickhouseCatalog.java`

原攻击路径：`schema` 直接拼入 `SELECT ... FROM system.tables WHERE database = '<schema>'`。传入 `x' OR 1=1 --` 会闭合字符串字面量并把查询条件改成恒真，可读取 `system.tables` 中不属于目标库的数据；其他引号/注释构造也可能扩大读取范围。

改后：新增统一严格标识符白名单 `^[A-Za-z_][A-Za-z0-9_]{0,63}$`。`ClickhouseCatalog.listTables` 在构造 SQL 前调用 `requireSafeIdentifier(schema, "schema")`，不匹配直接抛 `CatalogException`。

现在的拦截点：恶意 schema 在 `createStatement` / `executeQuery` 之前被拒绝，不会进入 SQL 执行。

影响谁：使用 ClickHouse 数据查询、元数据发现和 NL2SQL 上下文的调用方；正常库名 `sales` 等仍可查询，包含引号、注释符、反引号或超长名称的输入被拒绝。

回归测试：`Cute-agent-infrastructure/src/test/java/org/wwz/ai/infrastructure/dataquery/jdbc/catalog/CatalogSqlIdentifierTest.java`。

- 恶意输入 `x' OR 1=1 --` 被拒绝，且 `executeQuery` 未被调用。
- 正常输入 `sales` 仍生成 `database = 'sales'` 并查询。

### 5. MySQL 目录 SQL 注入 - 已修

文件：

- `Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/dataquery/jdbc/catalog/AbstractJdbcCatalog.java`
- `Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/dataquery/jdbc/catalog/mysql/MySqlCatalog.java`

原攻击路径：`getTableColumns` 使用 `String.format` 把 `schema` 和 `tablePath` 拼入 `INFORMATION_SCHEMA.COLUMNS` SQL，再通过普通 `Statement` 执行。传入 `x' OR 1=1 --` 可闭合 `TABLE_SCHEMA IN ('...')` 条件；恶意 `tablePath` 同样可闭合 `TABLE_NAME ='...'`，读取或破坏元数据查询语义。

改后：`schema` 和 `tablePath` 在执行前都经过同一严格标识符白名单校验，再拼入 SQL。合法标识符只含字母、数字和下划线，不能携带单引号、注释符或反引号。`INFORMATION_SCHEMA` 这里比较的是字符串值，因此保留 `'sales'` / `'orders'` 形式，不把反引号写入字符串值，避免合法库名/表名失配。

现在的拦截点：恶意 schema 或 tablePath 在 `String.format`、`createStatement` / `executeQuery` 之前抛 `CatalogException`，不会进入 SQL 执行。

影响谁：MySQL 数据查询、字段元数据发现、字段注释与类型上下文；正常 `sales` / `orders` 查询不受影响。

回归测试：`Cute-agent-infrastructure/src/test/java/org/wwz/ai/infrastructure/dataquery/jdbc/catalog/CatalogSqlIdentifierTest.java`。

- 恶意 schema `x' OR 1=1 --` 被拒绝，且 `executeQuery` 未被调用。
- 恶意 tablePath `orders' OR 1=1 --` 被拒绝，且 `executeQuery` 未被调用。
- 正常 `sales` / `orders` 仍生成预期 SQL 并查询。

## 跳过项

### `QdrantVectorAdapter.java:261` - 跳过（过期 finding）

实际文件：`Cute-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/dataquery/vector/QdrantVectorAdapter.java`。

代码依据：`deleteByFilter` 在约 308-312 行已有守卫；当 `filter == null || filter.getMust() == null || filter.getMust().isEmpty()` 时显式抛出 `IllegalArgumentException("filter is null or empty!")`。该 finding 所述问题已不存在，按任务书排除，不再改动。

## 验证

已先删除 `Cute-agent-infrastructure/target/test-classes`，强制重编译后执行指定回归：

```text
infrastructure: 16 tests, 0 failures, 0 errors
app:             6 tests, 0 failures, 0 errors
合计:           22 tests, 0 failures, 0 errors
```

覆盖测试：

- `SubAgentSessionPortRaceTest`
- `SubAgentSessionPortAdapterTest`
- `SessionWorkingMemoryReplaceAtomicityTest`
- `SessionWorkingMemoryServiceTokenEstimateTest`
- `QqBotResumeHeartbeatTest`
- `QqBotMentionStripTest`
- `QqBotCryptoTest`
- `CatalogSqlIdentifierTest`
- `JdbcDialectLoaderTest`

未执行 push、rebase、amend。
