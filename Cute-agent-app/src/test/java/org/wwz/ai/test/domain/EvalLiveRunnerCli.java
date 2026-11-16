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
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.askuser.IUserQuestionRepository;
import org.wwz.ai.domain.agent.runtime.askuser.UserQuestionRecord;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试 profile 的真实模型评测入口。
 *
 * <p>只用于 Nightly / Release；PR 使用 {@link EvalFixtureRunnerCli}。
 * 该入口不启动 Web Server，也不暴露生产 HTTP API。</p>
 */
public final class EvalLiveRunnerCli {

    private EvalLiveRunnerCli() {
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
            System.err.println("usage: EvalLiveRunnerCli --cases <cases.json> --out <dir> "
                    + "[--spring-profile eval] [--eval-profile nightly|release]");
            System.exit(2);
        }
        if ("dev".equalsIgnoreCase(springProfile) || "prod".equalsIgnoreCase(springProfile)) {
            throw new IllegalArgumentException("live eval refuses dev/prod Spring profiles");
        }

        JSONObject manifest = JSON.parseObject(Files.readString(casesFile, StandardCharsets.UTF_8));
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

        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(springProfile);
        try (ConfigurableApplicationContext context = application.run()) {
            AgentDispatchService dispatch = context.getBean(AgentDispatchService.class);
            ExecutionLedgerQueryService query = context.getBean(ExecutionLedgerQueryService.class);
            IUserQuestionRepository userQuestions = context.getBean(IUserQuestionRepository.class);
            JSONArray results = new JSONArray();
            for (int i = 0; i < cases.size(); i++) {
                results.add(runCase(
                        cases.getJSONObject(i),
                        outDir,
                        dispatch,
                        query,
                        userQuestions,
                        environment
                ));
            }
            JSONObject result = new JSONObject();
            result.put("schemaVersion", "1.0");
            result.put("runner", "cute-agent-java-live");
            result.put("cases", results);
            Files.writeString(outDir.resolve("result.json"), result.toJSONString(), StandardCharsets.UTF_8);
        }
    }

    private static JSONObject runCase(JSONObject evalCase,
                                      Path outDir,
                                      AgentDispatchService dispatch,
                                      ExecutionLedgerQueryService query,
                                      IUserQuestionRepository userQuestions,
                                      JSONObject environment) throws Exception {
        String caseId = evalCase.getString("id");
        String caseQuery = evalCase.getString("query");
        if (caseId == null || caseId.isBlank() || caseQuery == null || caseQuery.isBlank()) {
            throw new IllegalArgumentException("live case requires id and query");
        }
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String runPrefix = System.getenv("CUTE_EVAL_RUN_PREFIX");
        String prefix = runPrefix == null || runPrefix.isBlank() ? "" : runPrefix + "-";
        String requestId = requestId(prefix, suffix);
        String sessionId = prefix + "eval-live-session-" + suffix;
        String workspaceRoot = evalCase.getString("workspaceRoot");
        AgentExecutionCommand request = executionCommand(
                requestId,
                sessionId,
                caseQuery,
                workspaceRoot
        );

        String error = null;
        try {
            dispatch.dispatch(request, new NoopAgentSessionStream());
        } catch (Exception e) {
            error = e.getMessage();
        }

        ExecutionRunDetail detail = error == null
                ? awaitTerminalRun(query, requestId)
                : query.queryRunDetail(requestId);
        if (error == null
                && detail != null
                && detail.getRun() != null
                && detail.getRun().getStatus() != null
                && detail.getRun().getStatus() == ExecutionLedgerConstants.STATUS_RUNNING) {
            error = "run did not reach a terminal state within 180s";
        }
        JSONObject result = new JSONObject();
        result.put("caseId", caseId);
        result.put("requestId", requestId);
        result.put("sessionId", sessionId);
        if (detail == null || detail.getRun() == null) {
            result.put("status", "FAILED");
            result.put("finalText", error == null ? "run detail not found" : error);
            return result;
        }

        Integer runStatus = detail.getRun().getStatus();
        String finalText = detail.getRun().getFinalSummaryText();
        if (runStatus != null
                && runStatus == ExecutionLedgerConstants.STATUS_WAITING_INPUT) {
            finalText = pendingQuestionText(userQuestions.listOpenBySessionId(sessionId));
            if (finalText == null || finalText.isBlank()) {
                finalText = detail.getRun().getErrorMsg();
            }
        }
        JSONObject trace = EvalFixtureRunnerCli.toTrace(caseId, detail, environment);
        if (runStatus != null
                && runStatus == ExecutionLedgerConstants.STATUS_WAITING_INPUT) {
            JSONObject traceRun = trace.getJSONObject("run");
            traceRun.put("finalSummaryText", finalText);
            traceRun.put("evaluationStatus", "WAITING_INPUT");
            traceRun.put("status", "SUCCESS");
        }
        Path caseDir = outDir.resolve("traces").resolve(caseId);
        Files.createDirectories(caseDir);
        Files.writeString(caseDir.resolve("trace.json"), trace.toJSONString(), StandardCharsets.UTF_8);
        result.put("status", runStatus == null
                ? "UNKNOWN"
                : runStatus == ExecutionLedgerConstants.STATUS_WAITING_INPUT
                ? "SUCCESS"
                : EvalFixtureRunnerCli.statusName(runStatus));
        result.put("finalText", finalText);
        result.put("tracePath", "traces/" + caseId + "/trace.json");
        if (error != null) {
            result.put("error", error);
        }
        return result;
    }

    static String pendingQuestionText(List<UserQuestionRecord> records) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (UserQuestionRecord record : records) {
            for (Map<String, Object> question : record.getQuestions() == null
                    ? List.<Map<String, Object>>of()
                    : record.getQuestions()) {
                Object value = question.get("question");
                if (value == null || value.toString().isBlank()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(value);
            }
        }
        return text.length() == 0 ? null : text.toString();
    }

    static String requestId(String prefix, String suffix) {
        String head = prefix + "eval-live-";
        int suffixLength = Math.min(24, 64 - head.length());
        if (suffixLength < 8) {
            throw new IllegalArgumentException("CUTE_EVAL_RUN_PREFIX is too long");
        }
        return head + suffix.substring(0, suffixLength);
    }

    static AgentExecutionCommand executionCommand(String requestId,
                                                  String sessionId,
                                                  String query,
                                                  String workspaceRoot) {
        return AgentExecutionCommand.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .userId("eval-user")
                .query(query)
                .agentType(AgentType.REACT.getValue())
                .isStream(Boolean.TRUE)
                .workspaceRoot(workspaceRoot)
                .build();
    }

    private static ExecutionRunDetail awaitTerminalRun(ExecutionLedgerQueryService query,
                                                       String requestId) throws InterruptedException {
        long deadline = System.nanoTime() + 180_000_000_000L;
        ExecutionRunDetail detail = null;
        while (System.nanoTime() < deadline) {
            detail = query.queryRunDetail(requestId);
            Integer status = detail == null || detail.getRun() == null
                    ? null
                    : detail.getRun().getStatus();
            if (status != null && status != ExecutionLedgerConstants.STATUS_RUNNING) {
                return detail;
            }
            Thread.sleep(100L);
        }
        return detail;
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
