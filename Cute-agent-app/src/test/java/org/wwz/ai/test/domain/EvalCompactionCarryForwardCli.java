package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.wwz.ai.Application;
import org.wwz.ai.application.agent.dispatch.AgentDispatchService;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;
import org.wwz.ai.domain.agent.memory.SessionWorkingMemoryService;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.dto.Message;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内部 compaction-carry-forward 评测入口。
 *
 * <p>压缩组先调用真实生产压缩器，再把其结果以评测 session 的 working-memory
 * projection 装载给真实 Agent；control 组跳过压缩但走完全相同的装载和执行路径。</p>
 */
public final class EvalCompactionCarryForwardCli {

    private EvalCompactionCarryForwardCli() {
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
            System.err.println("usage: EvalCompactionCarryForwardCli --cases <cases.json> "
                    + "--out <dir> [--spring-profile eval]");
            System.exit(2);
        }
        if ("dev".equalsIgnoreCase(springProfile) || "prod".equalsIgnoreCase(springProfile)) {
            throw new IllegalArgumentException(
                    "compaction carry-forward eval refuses dev/prod Spring profiles");
        }

        JSONObject manifest = JSON.parseObject(
                Files.readString(casesFile, StandardCharsets.UTF_8));
        JSONArray cases = manifest.getJSONArray("cases");
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("cases manifest must contain a non-empty cases array");
        }
        Files.createDirectories(outDir);
        JSONObject environment = manifest.getJSONObject("environment");
        if (environment == null) {
            environment = new JSONObject();
        }
        environment.put("springProfile", springProfile);
        environment.put("evalProfile", evalProfile);

        // 压缩组由 CLI 显式触发；后续 Agent 运行禁用自动压缩，保证 control 组不被二次压缩。
        System.setProperty("autobots.autoagent.compaction.enabled", "false");
        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(springProfile);
        try (ConfigurableApplicationContext context = application.run()) {
            AgentDispatchService dispatch = context.getBean(AgentDispatchService.class);
            ExecutionLedgerQueryService query = context.getBean(ExecutionLedgerQueryService.class);
            SessionWorkingMemoryService workingMemory =
                    context.getBean(SessionWorkingMemoryService.class);
            JSONArray results = new JSONArray();
            for (int i = 0; i < cases.size(); i++) {
                results.add(runCase(
                        cases.getJSONObject(i),
                        outDir,
                        dispatch,
                        query,
                        workingMemory,
                        context,
                        environment));
            }
            JSONObject result = new JSONObject();
            result.put("schemaVersion", "1.0");
            result.put("runner", "cute-agent-java-compaction-carry-forward");
            result.put("cases", results);
            Files.writeString(
                    outDir.resolve("result.json"),
                    result.toJSONString(),
                    StandardCharsets.UTF_8);
        }
    }

    private static JSONObject runCase(JSONObject evalCase,
                                      Path outDir,
                                      AgentDispatchService dispatch,
                                      ExecutionLedgerQueryService query,
                                      SessionWorkingMemoryService workingMemory,
                                      ConfigurableApplicationContext context,
                                      JSONObject environment) throws Exception {
        String caseId = required(evalCase, "id");
        String caseQuery = required(evalCase, "query");
        JSONObject compaction = evalCase.getJSONObject("compaction");
        if (compaction == null) {
            throw new IllegalArgumentException("case " + caseId + " requires compaction");
        }
        JSONArray sourceRows = compaction.getJSONArray("messages");
        if (sourceRows == null || sourceRows.isEmpty()) {
            throw new IllegalArgumentException("case " + caseId + " requires compaction.messages");
        }
        String mode = compaction.getString("mode");
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("case " + caseId + " requires compaction.mode");
        }
        String cacheVariant = System.getenv("CUTE_EVAL_CACHE_VARIANT");
        if ("compressed".equalsIgnoreCase(cacheVariant)
                || "uncompressed-control".equalsIgnoreCase(cacheVariant)) {
            mode = cacheVariant.toLowerCase(java.util.Locale.ROOT);
        }

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String runPrefix = System.getenv("CUTE_EVAL_RUN_PREFIX");
        String prefix = runPrefix == null || runPrefix.isBlank() ? "" : runPrefix + "-";
        String requestId = requestId(prefix, caseId, suffix);
        String sessionId = prefix + "eval-compaction-session-" + suffix;
        JSONObject compactionResult = null;
        JSONArray contextRows = sourceRows;

        if (!"uncompressed-control".equals(mode)) {
            JSONObject compactRequest = new JSONObject();
            compactRequest.put("sessionId", sessionId);
            compactRequest.put("requestId", requestId);
            compactRequest.put("messages", sourceRows);
            compactRequest.put("budget", compaction.getJSONObject("budget"));
            compactRequest.put("persistAudit", true);
            compactionResult = EvalCompactionCli.compress(compactRequest, context);
            contextRows = compactionResult.getJSONArray("messages");
            if (contextRows == null || contextRows.isEmpty()) {
                throw new IllegalStateException("compaction produced no messages for " + caseId);
            }
        }

        String seedRequestId = "eval-compaction-seed-" + suffix;
        workingMemory.replaceReadyProjection(
                sessionId,
                "main",
                seedRequestId,
                EvalCompactionCli.toMessages(contextRows));

        AgentExecutionCommand request = AgentExecutionCommand.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .userId(evalUserId(suffix, evalCase.getString("userId")))
                .query(caseQuery)
                .agentType(AgentType.REACT.getValue())
                .isStream(Boolean.TRUE)
                .build();
        String error = null;
        try {
            dispatch.dispatch(request, new NoopAgentSessionStream());
        } catch (Exception e) {
            error = e.getMessage();
        }

        ExecutionRunDetail detail = query.queryRunDetail(requestId);
        if (error == null
                && detail != null
                && detail.getRun() != null
                && detail.getRun().getStatus() != null
                && detail.getRun().getStatus() == ExecutionLedgerConstants.STATUS_RUNNING) {
            error = "run did not reach a terminal state";
        }

        JSONObject result = new JSONObject();
        result.put("caseId", caseId);
        result.put("requestId", requestId);
        result.put("sessionId", sessionId);
        result.put("compactionMode", mode);
        result.put("cacheVariant", cacheVariant);
        if (compactionResult != null) {
            result.put("compaction", compactionResult);
        }
        if (detail == null || detail.getRun() == null) {
            result.put("status", "FAILED");
            result.put("finalText", error == null ? "run detail not found" : error);
            return result;
        }

        JSONObject trace = EvalFixtureRunnerCli.toTrace(caseId, detail, environment);
        Path caseDir = outDir.resolve("traces").resolve(caseId);
        Files.createDirectories(caseDir);
        Files.writeString(
                caseDir.resolve("trace.json"),
                trace.toJSONString(),
                StandardCharsets.UTF_8);
        result.put("status", detail.getRun().getStatus() == null
                ? "UNKNOWN"
                : EvalFixtureRunnerCli.statusName(detail.getRun().getStatus()));
        result.put("finalText", detail.getRun().getFinalSummaryText());
        result.put("tracePath", "traces/" + caseId + "/trace.json");
        if (error != null) {
            result.put("error", error);
        }
        return result;
    }

    static String evalUserId(String suffix) {
        return evalUserId(suffix, null);
    }

    static String evalUserId(String suffix, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        String safeSuffix = suffix.length() > 24 ? suffix.substring(0, 24) : suffix;
        return "eval-user-" + safeSuffix;
    }

    static String requestId(String prefix, String caseId, String suffix) {
        String caseTag = UUID.nameUUIDFromBytes(
                        caseId.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 8);
        String safeSuffix = suffix.length() > 24 ? suffix.substring(0, 24) : suffix;
        String base = "eval-carry-forward-" + caseTag + "-" + safeSuffix;
        int maxPrefixLength = Math.max(0, 64 - base.length());
        String safePrefix = prefix == null ? "" : prefix;
        if (safePrefix.length() > maxPrefixLength) {
            safePrefix = safePrefix.substring(0, maxPrefixLength);
        }
        return safePrefix + base;
    }

    private static String required(JSONObject row, String key) {
        String value = row == null ? null : row.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        return value;
    }

    private static final class NoopAgentSessionStream implements AgentSessionStream {
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void send(Object payload) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void completeWithError(Throwable throwable) {
            error.compareAndSet(null, throwable);
        }

        @Override
        public void onAbort(Runnable abortHandler) {
        }

        @Override
        public boolean isAborted() {
            return aborted.get();
        }
    }
}
