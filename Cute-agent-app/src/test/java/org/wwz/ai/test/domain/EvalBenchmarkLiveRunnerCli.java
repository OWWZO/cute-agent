package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.wwz.ai.Application;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.agent.ReactImplAgent;
import org.wwz.ai.domain.agent.runtime.budget.RunBudget;
import org.wwz.ai.domain.agent.runtime.cancel.ActiveAgentRunRegistry;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.enums.AgentState;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.prompt.AgentPrompt;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.service.execute.support.PrepareAgentContextAssembly;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 公开 Benchmark 的真实模型入口。
 *
 * <p>官方函数只存在于当前 Case 的临时 ToolCollection；运行结束即丢弃，
 * 不注册到生产工具目录，也不改写任何生产 MCP 配置。</p>
 */
public final class EvalBenchmarkLiveRunnerCli {

    private EvalBenchmarkLiveRunnerCli() {
    }

    public static void main(String[] args) throws Exception {
        Path casesFile = null;
        Path outDir = null;
        String springProfile = "eval";
        String evalProfile = "nightly";
        for (int i = 0; i < args.length; i++) {
            if ("--cases".equals(args[i]) && i + 1 < args.length) {
                casesFile = Path.of(args[++i]);
            } else if ("--out".equals(args[i]) && i + 1 < args.length) {
                outDir = Path.of(args[++i]);
            } else if ("--spring-profile".equals(args[i]) && i + 1 < args.length) {
                springProfile = args[++i];
            } else if ("--eval-profile".equals(args[i]) && i + 1 < args.length) {
                evalProfile = args[++i];
            }
        }
        if (casesFile == null || outDir == null) {
            System.err.println("usage: EvalBenchmarkLiveRunnerCli --cases <cases.json> "
                    + "--out <dir> [--spring-profile eval] [--eval-profile nightly|release]");
            System.exit(2);
        }
        if ("dev".equalsIgnoreCase(springProfile) || "prod".equalsIgnoreCase(springProfile)) {
            throw new IllegalArgumentException("benchmark live eval refuses dev/prod profiles");
        }

        JSONObject manifest = JSON.parseObject(
                Files.readString(casesFile, StandardCharsets.UTF_8));
        JSONArray cases = manifest.getJSONArray("cases");
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("cases manifest must contain cases");
        }
        Files.createDirectories(outDir);
        JSONObject environment = manifest.getJSONObject("environment");
        if (environment == null) {
            environment = new JSONObject();
        }
        environment.put("springProfile", springProfile);
        environment.put("evalProfile", evalProfile);
        environment.put("toolScope", "ephemeral-benchmark-only");

        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(springProfile);
        try (ConfigurableApplicationContext applicationContext = application.run()) {
            PrepareAgentContextAssembly assembly =
                    applicationContext.getBean(PrepareAgentContextAssembly.class);
            ExecutionLedgerQueryService query =
                    applicationContext.getBean(ExecutionLedgerQueryService.class);
            ActiveAgentRunRegistry runRegistry =
                    applicationContext.getBean(ActiveAgentRunRegistry.class);
            JSONArray results = new JSONArray();
            for (int i = 0; i < cases.size(); i++) {
                results.add(runCase(
                        cases.getJSONObject(i),
                        outDir,
                        assembly,
                        query,
                        runRegistry,
                        applicationContext,
                        environment));
            }
            JSONObject result = new JSONObject();
            result.put("schemaVersion", "1.0");
            result.put("runner", "cute-agent-java-benchmark-live");
            result.put("cases", results);
            Files.writeString(
                    outDir.resolve("result.json"),
                    result.toJSONString(),
                    StandardCharsets.UTF_8);
        }
    }

    private static JSONObject runCase(JSONObject evalCase,
                                      Path outDir,
                                      PrepareAgentContextAssembly assembly,
                                      ExecutionLedgerQueryService query,
                                      ActiveAgentRunRegistry runRegistry,
                                      ConfigurableApplicationContext applicationContext,
                                      JSONObject environment) throws Exception {
        String caseId = evalCase.getString("id");
        JSONArray turns = evalCase.getJSONArray("turns");
        String userSimulatorEndpoint = evalCase.getString("userSimulatorEndpoint");
        boolean dynamicTurns = userSimulatorEndpoint != null
                && !userSimulatorEndpoint.isBlank();
        boolean independentTurns = evalCase.getBooleanValue("independentTurns");
        if (dynamicTurns) {
            String initialUserMessage = evalCase.getString("initialUserMessage");
            if (initialUserMessage == null || initialUserMessage.isBlank()) {
                throw new IllegalArgumentException(
                        "dynamic benchmark case requires initialUserMessage: " + caseId);
            }
            turns = new JSONArray();
            JSONObject firstTurn = new JSONObject();
            firstTurn.put("query", initialUserMessage);
            turns.add(firstTurn);
        } else if (turns == null || turns.isEmpty()) {
            turns = new JSONArray();
            JSONObject singleTurn = new JSONObject();
            singleTurn.put("query", evalCase.getString("query"));
            turns.add(singleTurn);
        }
        JSONArray toolDefinitions = evalCase.getJSONArray("tools");
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("benchmark case requires id");
        }
        String userId = evalCase.getString("userId");
        if (userId == null || userId.isBlank()) {
            userId = "eval-user";
        }

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String sessionId = "eval-benchmark-session-" + suffix;
        JSONObject memorySeed = evalCase.getJSONObject("memorySeed");
        if (memorySeed != null) {
            JSONObject seedPayload = new JSONObject();
            seedPayload.putAll(memorySeed);
            seedPayload.putIfAbsent("userId", userId);
            seedPayload.putIfAbsent(
                    "namespace",
                    "eval-seed-" + caseId.replaceAll("[^A-Za-z0-9_-]", "-"));
            EvalMemorySeedCli.seed(seedPayload, applicationContext);
        }
        String toolMode = evalCase.getString("toolMode");
        if (toolMode == null || toolMode.isBlank()) {
            toolMode = environment.getString("toolMode");
        }
        if (toolMode == null || toolMode.isBlank()) {
            toolMode = "ephemeral";
        }
        String toolEndpoint = evalCase.getString("toolEndpoint");
        if (toolEndpoint == null || toolEndpoint.isBlank()) {
            toolEndpoint = environment.getString("toolEndpoint");
        }
        String workspaceRoot = evalCase.getString("workspaceRoot");
        JSONObject answerContract = evalCase.getJSONObject("answerContract");
        String cacheVariant = System.getenv("CUTE_EVAL_CACHE_VARIANT");
        if (cacheVariant == null) {
            cacheVariant = "stable";
        }
        JSONArray runToolDefinitions = variantTools(toolDefinitions, cacheVariant, suffix);
        BudgetLimits budgetLimits = BudgetLimits.from(evalCase);
        int maxTurns = evalCase.getIntValue("maxTurns");
        if (maxTurns <= 0) {
            maxTurns = dynamicTurns ? 20 : turns.size();
        }
        List<Message> workingMemory = new ArrayList<>();
        JSONArray turnResults = new JSONArray();
        JSONArray tracePaths = new JSONArray();
        String finalText = "";
        String aggregateError = null;
        String aggregateErrorCode = null;
        int aggregateStatus = ExecutionLedgerConstants.STATUS_SUCCESS;
        boolean dynamicCompleted = false;

        for (int turnIndex = 0; turnIndex < turns.size() && turnIndex < maxTurns; turnIndex++) {
            JSONObject turn = turns.getJSONObject(turnIndex);
            String queryText = turn == null ? null : turn.getString("query");
            if (queryText == null || queryText.isBlank()) {
                throw new IllegalArgumentException(
                        "benchmark turn requires query: " + caseId + "#" + (turnIndex + 1));
            }
            String turnId = String.format("turn-%03d", turnIndex + 1);
            // Execution Ledger stores requestId in VARCHAR(64). Keep the eval
            // run id short while retaining 128 bits of uniqueness.
            String requestId = "eval-bm-" + suffix + "-" + turnId;
            String promptText = promptWithContract(queryText, answerContract);
            AgentExecutionCommand.AgentExecutionCommandBuilder requestBuilder =
                    AgentExecutionCommand.builder()
                    .requestId(requestId)
                    .sessionId(sessionId)
                    .userId(userId)
                    .query(promptText)
                    .agentType(AgentType.REACT.getValue())
                    .isStream(Boolean.TRUE)
                    .workspaceRoot(workspaceRoot);
            if (!independentTurns && !workingMemory.isEmpty()) {
                requestBuilder.workingMemoryMessages(new ArrayList<>(workingMemory));
            }
            AgentExecutionCommand request = requestBuilder.build();

            NoopPrinter printer = new NoopPrinter();
            PrepareAgentContextAssembly.Assembled assembled = assembly.assemble(
                    request,
                    printer,
                    ExecutionLedgerConstants.ENTRY_AGENT_REACT,
                    PrepareAgentContextAssembly.ToolScope.SINGLE);
            AgentContext context = assembled.context();
            context.getAgentRunState().setStrictLedgerWrites(true);

            if (!"production".equalsIgnoreCase(toolMode)) {
                ToolCollection ephemeral = new ToolCollection();
                ephemeral.setAgentContext(context);
                if (runToolDefinitions != null) {
                    for (int i = 0; i < runToolDefinitions.size(); i++) {
                        ephemeral.addTool(new SchemaTool(
                                runToolDefinitions.getJSONObject(i),
                                toolEndpoint));
                    }
                }
                context.setToolCollection(ephemeral);
            }

            ReactImplAgent agent = new ReactImplAgent(context);
            Integer maxSteps = turn.getInteger("maxSteps");
            if (maxSteps == null) {
                maxSteps = evalCase.getInteger("maxSteps");
            }
            if (maxSteps != null && maxSteps > 0) {
                agent.setMaxSteps(maxSteps);
            }
            if (answerContract != null) {
                agent.setSystemPrompt(
                        AgentPrompt.REACT_SYSTEM_PROMPT + "\n\n"
                                + "# Benchmark output contract\n"
                                + "The user's answer contract overrides any general reply format. "
                                + "If it requires `FINAL_ANSWER:`, every no-tool final reply must "
                                + "end with exactly one `FINAL_ANSWER: <answer>` line. Never output "
                                + "`$$$`, an artifact list, or a bare `None` in a benchmark run."
                );
            }
            RunBudget runBudget = RunBudget.create(
                    budgetLimits.maxTotalTokens,
                    budgetLimits.maxWallMs,
                    budgetLimits.maxToolCalls,
                    agent.getMaxSteps(),
                    budgetLimits.softLimitPercent
            );
            context.setRunBudget(runBudget);

            String turnText = "";
            String errorCode = null;
            String errorMsg = null;
            String budgetExceeded = null;
            int status = ExecutionLedgerConstants.STATUS_FAILED;
            BudgetWatchdog watchdog = BudgetWatchdog.start(
                    requestId,
                    budgetLimits,
                    runRegistry,
                    runBudget
            );
            try {
                turnText = agent.run(promptText);
                budgetExceeded = runBudget.exceededReason();
                if (budgetExceeded == null) {
                    budgetExceeded = watchdog.exceededReason();
                }
                if (budgetExceeded == null
                        && answerContract != null
                        && agent.getState() == AgentState.FINISHED
                        && finalAnswerError(turnText) != null) {
                    String recovered = agent.recoverEmptyFinalAnswer();
                    if (recovered != null && !recovered.isBlank()) {
                        turnText = recovered;
                    }
                    budgetExceeded = runBudget.exceededReason();
                    if (budgetExceeded == null) {
                        budgetExceeded = watchdog.exceededReason();
                    }
                }
                if (budgetExceeded != null) {
                    errorCode = "EVAL_BUDGET_EXCEEDED";
                    errorMsg = "evaluation budget exceeded: " + budgetExceeded;
                } else if (agent.getState() == AgentState.FINISHED) {
                    String finalAnswerError = finalAnswerError(turnText);
                    if (finalAnswerError == null) {
                        status = ExecutionLedgerConstants.STATUS_SUCCESS;
                    } else {
                        errorCode = "BENCHMARK_EMPTY_FINAL_ANSWER";
                        errorMsg = finalAnswerError;
                    }
                } else {
                    errorCode = "BENCHMARK_RUN_NOT_FINISHED";
                    errorMsg = "agent state=" + agent.getState();
                }
            } catch (Exception e) {
                budgetExceeded = runBudget.exceededReason();
                if (budgetExceeded == null) {
                    budgetExceeded = watchdog.exceededReason();
                }
                if (budgetExceeded != null) {
                    errorCode = "EVAL_BUDGET_EXCEEDED";
                    errorMsg = "evaluation budget exceeded: " + budgetExceeded;
                } else {
                    errorCode = "BENCHMARK_RUN_ERROR";
                    errorMsg = e.getMessage();
                }
                turnText = errorMsg;
            } finally {
                watchdog.close();
                ExecutionLedgerRunSupport.finishRun(
                        context,
                        status,
                        turnText == null ? "" : turnText,
                        errorCode,
                        errorMsg);
                printer.close();
            }

            ExecutionRunDetail detail = query.queryRunDetail(requestId);
            if (detail == null || detail.getRun() == null) {
                throw new IllegalStateException("ledger trace not found for " + requestId);
            }
            JSONObject turnEnvironment = new JSONObject();
            turnEnvironment.putAll(environment);
            turnEnvironment.put("conversationId", sessionId);
            turnEnvironment.put("turnId", turnId);
            turnEnvironment.put("turnIndex", turnIndex + 1);
            turnEnvironment.put("toolMode", toolMode);
            turnEnvironment.put("cacheVariant", cacheVariant);
            turnEnvironment.put("officialQuery", queryText);
            turnEnvironment.put("promptQuery", promptText);
            turnEnvironment.put("answerContract", answerContract);
            JSONObject budget = new JSONObject();
            budget.put("maxWallMs", budgetLimits.maxWallMs);
            budget.put("maxTotalTokens", budgetLimits.maxTotalTokens);
            budget.put("maxToolCalls", budgetLimits.maxToolCalls);
            budget.put("softLimitPercent", budgetLimits.softLimitPercent);
            budget.put("usedTotalTokens", sumTokens(detail));
            budget.put("usedToolCalls", detail.getToolInvocations().size());
            budget.put("control", runBudget.snapshot());
            if (budgetExceeded != null) {
                budget.put("exceeded", budgetExceeded);
            }
            turnEnvironment.put("budget", budget);
            if (workspaceRoot != null && !workspaceRoot.isBlank()) {
                turnEnvironment.put("workspaceRoot", workspaceRoot);
            }
            JSONObject trace = EvalFixtureRunnerCli.toTrace(caseId, detail, turnEnvironment);
            applyInvocationTotals(trace, detail);
            String relativeTracePath = turns.size() == 1
                    ? "traces/" + caseId + "/trace.json"
                    : "traces/" + caseId + "/" + turnId + "/trace.json";
            Path tracePath = outDir.resolve(relativeTracePath);
            Files.createDirectories(tracePath.getParent());
            Files.writeString(tracePath, trace.toJSONString(), StandardCharsets.UTF_8);
            tracePaths.add(relativeTracePath);

            JSONObject turnResult = new JSONObject();
            turnResult.put("turnId", turnId);
            turnResult.put("turnIndex", turnIndex + 1);
            turnResult.put("requestId", requestId);
            turnResult.put("query", queryText);
            turnResult.put("status", EvalFixtureRunnerCli.statusName(status));
            turnResult.put("finalText", turnText);
            turnResult.put("officialQuery", queryText);
            turnResult.put("tracePath", relativeTracePath);
            turnResult.put("budgetControl", runBudget.snapshot());
            if (errorMsg != null) {
                turnResult.put("error", errorMsg);
            }
            if (budgetExceeded != null) {
                turnResult.put("budgetExceeded", budgetExceeded);
            }
            turnResults.add(turnResult);

            if (!independentTurns && agent.getMemory() != null) {
                workingMemory = new ArrayList<>(agent.getMemory().getMessages());
            }
            finalText = turnText == null ? "" : turnText;
            if (status != ExecutionLedgerConstants.STATUS_SUCCESS) {
                aggregateStatus = status;
                aggregateError = errorMsg;
                aggregateErrorCode = errorCode;
                if (dynamicTurns) {
                    break;
                }
            }
            if (dynamicTurns) {
                JSONObject next = requestUserSimulator(
                        userSimulatorEndpoint,
                        sessionId,
                        turnId,
                        turnIndex + 1,
                        finalText);
                if (!next.getBooleanValue("ok")) {
                    aggregateStatus = ExecutionLedgerConstants.STATUS_FAILED;
                    aggregateError = next.getString("error");
                    break;
                }
                if (next.getBooleanValue("done")) {
                    dynamicCompleted = true;
                    break;
                }
                String nextMessage = next.getString("message");
                if (nextMessage == null || nextMessage.isBlank()) {
                    aggregateStatus = ExecutionLedgerConstants.STATUS_FAILED;
                    aggregateError = "user simulator returned an empty message";
                    break;
                }
                JSONObject nextTurn = new JSONObject();
                nextTurn.put("query", nextMessage);
                turns.add(nextTurn);
            }
        }
        if (dynamicTurns && !dynamicCompleted && turnResults.size() >= maxTurns) {
            aggregateStatus = ExecutionLedgerConstants.STATUS_FAILED;
            aggregateError = "conversation exceeded " + maxTurns + " turns";
        }

        JSONObject result = new JSONObject();
        result.put("caseId", caseId);
        result.put("conversationId", sessionId);
        result.put("sessionId", sessionId);
        result.put("userId", userId);
        result.put("status", EvalFixtureRunnerCli.statusName(aggregateStatus));
        result.put("finalText", finalText);
        result.put("turns", turnResults);
        result.put("tracePaths", tracePaths);
        if (tracePaths.size() == 1) {
            result.put("tracePath", tracePaths.getString(0));
        }
        if (aggregateError != null) {
            result.put("error", aggregateError);
        }
        if (aggregateErrorCode != null) {
            result.put("errorCode", aggregateErrorCode);
        }
        return result;
    }

    static JSONArray variantTools(JSONArray source,
                                  String variant,
                                  String suffix) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        JSONArray copy = new JSONArray();
        for (int i = 0; i < source.size(); i++) {
            copy.add(source.getJSONObject(i).clone());
        }
        if ("schema-change".equalsIgnoreCase(variant)
                || "schema-churn".equalsIgnoreCase(variant)) {
            for (int i = 0; i < copy.size(); i++) {
                copy.set(i, reorderObjectFields(copy.getJSONObject(i)));
            }
        } else if ("volatile-description".equalsIgnoreCase(variant)) {
            JSONObject first = copy.getJSONObject(0);
            String description = first.getString("description");
            first.put("description",
                    (description == null ? "" : description)
                            + "\nEvaluation cache nonce: " + suffix);
        }
        return copy;
    }

    private static JSONObject reorderObjectFields(JSONObject source) {
        JSONObject reordered = new JSONObject(true);
        for (String key : List.of("parameters", "description", "name")) {
            if (source.containsKey(key)) {
                reordered.put(key, source.get(key));
            }
        }
        for (String key : source.keySet()) {
            reordered.putIfAbsent(key, source.get(key));
        }
        return reordered;
    }

    static String promptWithContract(String query, JSONObject answerContract) {
        String instruction = answerContract == null
                ? null
                : answerContract.getString("instruction");
        if (instruction == null || instruction.isBlank()) {
            return query;
        }
        return query + "\n\n" + instruction;
    }

    static int sumTokens(ExecutionRunDetail detail) {
        int total = 0;
        for (var invocation : detail.getLlmInvocations()) {
            total += invocation.getTotalTokens() == null
                    ? 0
                    : invocation.getTotalTokens();
        }
        return total;
    }

    static void applyInvocationTotals(JSONObject trace, ExecutionRunDetail detail) {
        JSONObject run = trace.getJSONObject("run");
        if (run != null) {
            run.put("promptTokens", sumPromptTokens(detail));
            run.put("completionTokens", sumCompletionTokens(detail));
            run.put("totalTokens", sumTokens(detail));
        }
    }

    private static int sumPromptTokens(ExecutionRunDetail detail) {
        int total = 0;
        for (var invocation : detail.getLlmInvocations()) {
            total += invocation.getPromptTokens() == null ? 0 : invocation.getPromptTokens();
        }
        return total;
    }

    private static int sumCompletionTokens(ExecutionRunDetail detail) {
        int total = 0;
        for (var invocation : detail.getLlmInvocations()) {
            total += invocation.getCompletionTokens() == null
                    ? 0
                    : invocation.getCompletionTokens();
        }
        return total;
    }

    static boolean budgetExceeded(long elapsedMs,
                                  int totalTokens,
                                  int toolCalls,
                                  BudgetLimits limits) {
        return (limits.maxWallMs != null && elapsedMs > limits.maxWallMs)
                || (limits.maxTotalTokens != null
                && totalTokens > limits.maxTotalTokens)
                || (limits.maxToolCalls != null && toolCalls > limits.maxToolCalls);
    }

    static String finalAnswerError(String finalText) {
        return finalText == null || finalText.isBlank()
                ? "agent finished without a final answer"
                : null;
    }

    static final class BudgetLimits {
        private final Integer maxWallMs;
        private final Integer maxTotalTokens;
        private final Integer maxToolCalls;
        private final double softLimitPercent;

        private BudgetLimits(Integer maxWallMs,
                             Integer maxTotalTokens,
                             Integer maxToolCalls,
                             Double softLimitPercent) {
            this.maxWallMs = maxWallMs;
            this.maxTotalTokens = maxTotalTokens;
            this.maxToolCalls = maxToolCalls;
            this.softLimitPercent = softLimitPercent == null ? 0.8 : softLimitPercent;
        }

        static BudgetLimits from(JSONObject evalCase) {
            return new BudgetLimits(
                    positiveOrNull(evalCase.getInteger("maxWallMs")),
                    positiveOrNull(evalCase.getInteger("maxTotalTokens")),
                    positiveOrNull(evalCase.getInteger("maxToolCalls")),
                    evalCase.getDouble("softLimitPercent")
            );
        }

        boolean enabled() {
            return maxWallMs != null
                    || maxTotalTokens != null
                    || maxToolCalls != null;
        }

        double softLimitPercent() {
            return softLimitPercent;
        }

        private static Integer positiveOrNull(Integer value) {
            return value == null || value <= 0 ? null : value;
        }
    }

    private static final class BudgetWatchdog implements AutoCloseable {
        private static final ScheduledExecutorService EXECUTOR =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "eval-budget-watchdog");
                    thread.setDaemon(true);
                    return thread;
                });

        private final String requestId;
        private final BudgetLimits limits;
        private final ActiveAgentRunRegistry runRegistry;
        private final RunBudget runBudget;
        private final long startedNanos;
        private final AtomicReference<String> exceededReason = new AtomicReference<>();
        private final ScheduledFuture<?> future;

        private BudgetWatchdog(String requestId,
                               BudgetLimits limits,
                               ActiveAgentRunRegistry runRegistry,
                               RunBudget runBudget) {
            this.requestId = requestId;
            this.limits = limits;
            this.runRegistry = runRegistry;
            this.runBudget = runBudget;
            this.startedNanos = System.nanoTime();
            this.future = limits.enabled()
                    ? EXECUTOR.scheduleAtFixedRate(
                    this::check,
                    250,
                    250,
                    TimeUnit.MILLISECONDS
            )
                    : null;
        }

        static BudgetWatchdog start(String requestId,
                                    BudgetLimits limits,
                                    ActiveAgentRunRegistry runRegistry,
                                    RunBudget runBudget) {
            return new BudgetWatchdog(requestId, limits, runRegistry, runBudget);
        }

        String exceededReason() {
            return exceededReason.get();
        }

        private void check() {
            if (exceededReason.get() != null) {
                return;
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedNanos
            );
            if (limits.maxWallMs != null && elapsedMs > limits.maxWallMs) {
                exceed("wall_time");
                return;
            }
        }

        private void exceed(String reason) {
            if (exceededReason.compareAndSet(null, reason)) {
                runBudget.markHardExceeded(reason);
                runRegistry.cancel(requestId, "eval_budget_exceeded:" + reason);
            }
        }

        @Override
        public void close() {
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private static JSONObject requestUserSimulator(String endpoint,
                                                   String sessionId,
                                                   String turnId,
                                                   int nextTurnIndex,
                                                   String assistantMessage) {
        JSONObject body = new JSONObject();
        body.put("sessionId", sessionId);
        body.put("turnId", turnId);
        body.put("nextTurnIndex", nextTurnIndex);
        body.put("assistantMessage", assistantMessage);
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(endpoint.endsWith("/next")
                                    ? endpoint
                                    : endpoint + "/next"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body.toJSONString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JSONObject payload = JSON.parseObject(response.body());
            if (payload == null) {
                payload = new JSONObject();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                payload.put("ok", false);
                payload.putIfAbsent("error", "user simulator HTTP " + response.statusCode());
            }
            return payload;
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("ok", false);
            error.put("error", e.getMessage());
            return error;
        }
    }

    private static final class SchemaTool implements BaseTool {
        private static final HttpClient HTTP = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final JSONObject definition;
        private final String endpoint;

        private SchemaTool(JSONObject definition, String endpoint) {
            this.definition = definition;
            this.endpoint = endpoint;
        }

        @Override
        public String getName() {
            return definition.getString("name");
        }

        @Override
        public String getDescription() {
            return definition.getString("description");
        }

        @Override
        public Map<String, Object> toParams() {
            JSONObject parameters = definition.getJSONObject("parameters");
            return parameters == null ? Map.of("type", "object") : parameters;
        }

        @Override
        public Object execute(Object input, AgentContext runtimeContext) {
            if (endpoint == null || endpoint.isBlank()) {
                return ToolResultPayload.text("BENCHMARK_TOOL_CALL_ACCEPTED");
            }
            JSONObject body = new JSONObject();
            body.put("toolName", getName());
            body.put("arguments", input);
            String url = endpoint.endsWith("/call") ? endpoint : endpoint + "/call";
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                body.toJSONString(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = HTTP.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JSONObject payload = JSON.parseObject(response.body());
                if (response.statusCode() < 200 || response.statusCode() >= 300
                        || payload == null || !payload.getBooleanValue("ok")) {
                    String error = payload == null
                            ? "HTTP " + response.statusCode()
                            : payload.getString("error");
                    return ToolResultPayload.builder()
                            .failed(true)
                            .toolResult(error)
                            .errorMsg(error)
                            .build();
                }
                Object result = payload.get("result");
                if (result == null) {
                    return "";
                }
                return result instanceof String ? result : JSON.toJSONString(result);
            } catch (Exception e) {
                return ToolResultPayload.builder()
                        .failed(true)
                        .toolResult(e.getMessage())
                        .errorMsg(e.getMessage())
                        .build();
            }
        }
    }

    private static final class NoopPrinter implements Printer {
        @Override
        public void send(String messageId,
                         String messageType,
                         Object message,
                         String digitalEmployee,
                         Boolean isFinal) {
        }

        @Override
        public void send(String messageId,
                         String messageType,
                         Object message,
                         Map<String, Object> extraResultMap,
                         String digitalEmployee,
                         Boolean isFinal) {
        }

        @Override
        public void close() {
        }

        @Override
        public void updateAgentType(AgentType agentType) {
        }
    }
}
