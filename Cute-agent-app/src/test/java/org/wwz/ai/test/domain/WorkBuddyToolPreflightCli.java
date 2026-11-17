package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.wwz.ai.Application;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ToolInvocationFinishRecord;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;
import org.wwz.ai.domain.agent.runtime.enums.AgentType;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.service.execute.support.PrepareAgentContextAssembly;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exercises the production Agent tool assembly without invoking an LLM.
 *
 * <p>The report is written before a non-zero exit so callers can distinguish
 * missing tools from tools that are present but fail on the evaluation
 * workspace.</p>
 */
public final class WorkBuddyToolPreflightCli {

    private static final List<String> REQUIRED_TOOLS = List.of(
            "workspace_write",
            "workspace_read",
            "workspace_edit",
            "workspace_grep",
            "workspace_glob",
            "workspace_list",
            "code_execution"
    );

    private WorkBuddyToolPreflightCli() {
    }

    public static void main(String[] args) throws Exception {
        Path workspace = null;
        Path output = null;
        String springProfile = "eval";
        for (int i = 0; i < args.length; i++) {
            if ("--workspace".equals(args[i]) && i + 1 < args.length) {
                workspace = Path.of(args[++i]);
            } else if ("--out".equals(args[i]) && i + 1 < args.length) {
                output = Path.of(args[++i]);
            } else if ("--spring-profile".equals(args[i]) && i + 1 < args.length) {
                springProfile = args[++i];
            }
        }
        if (workspace == null || output == null) {
            System.err.println(
                    "usage: WorkBuddyToolPreflightCli --workspace <dir> "
                            + "--out <report.json> [--spring-profile eval]");
            System.exit(2);
        }
        if ("dev".equalsIgnoreCase(springProfile)
                || "prod".equalsIgnoreCase(springProfile)) {
            throw new IllegalArgumentException(
                    "tool preflight refuses dev/prod profiles");
        }

        workspace = workspace.toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        output = output.toAbsolutePath().normalize();
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        JSONObject report = new JSONObject();
        report.put("schemaVersion", "1.0");
        report.put("workspaceRoot", workspace.toString());
        report.put("springProfile", springProfile);
        JSONArray checks = new JSONArray();
        report.put("checks", checks);

        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(springProfile);
        try (ConfigurableApplicationContext applicationContext = application.run()) {
            PrepareAgentContextAssembly assembly =
                    applicationContext.getBean(PrepareAgentContextAssembly.class);
            String suffix = UUID.randomUUID().toString().replace("-", "");
            String sessionId = "workbuddy-tool-preflight-" + suffix;
            String requestId = "preflight-" + suffix;
            AgentExecutionCommand request = AgentExecutionCommand.builder()
                    .requestId(requestId)
                    .sessionId(sessionId)
                    .userId("eval-user")
                    .query("tool chain preflight")
                    .agentType(AgentType.REACT.getValue())
                    .isStream(Boolean.FALSE)
                    .workspaceRoot(workspace.toString())
                    .build();

            PrepareAgentContextAssembly.Assembled assembled = assembly.assemble(
                    request,
                    new NoopPrinter(),
                    ExecutionLedgerConstants.ENTRY_AGENT_REACT,
                    PrepareAgentContextAssembly.ToolScope.SINGLE);
            AgentContext context = assembled.context();
            ToolCollection tools = context.getToolCollection();
            List<String> names = new ArrayList<>(tools.getToolMap().keySet());
            names.sort(String::compareTo);
            report.put("sessionId", sessionId);
            report.put("requestId", requestId);
            report.put("availableTools", names);

            invoke(checks, tools, context, "workspace_write", Map.of(
                    "path", "preflight.txt",
                    "content", "v1\n"));
            readCheck(checks, "write_landed", workspace.resolve("preflight.txt"), "v1\n");
            artifactCheck(checks, context, "preflight-workspace_write", "preflight.txt");

            invoke(checks, tools, context, "workspace_read", Map.of(
                    "path", "preflight.txt"));
            invoke(checks, tools, context, "workspace_edit", Map.of(
                    "path", "preflight.txt",
                    "old_string", "v1",
                    "new_string", "v2",
                    "replace_all", false));
            readCheck(checks, "edit_landed", workspace.resolve("preflight.txt"), "v2\n");

            invoke(checks, tools, context, "workspace_read", Map.of(
                    "path", "preflight.txt"));
            invoke(checks, tools, context, "workspace_grep", Map.of(
                    "path", "preflight.txt",
                    "pattern", "v2",
                    "regex", false));
            invoke(checks, tools, context, "workspace_glob", Map.of(
                    "path", ".",
                    "pattern", "preflight*.txt"));
            invoke(checks, tools, context, "workspace_list", Map.of(
                    "path", ".",
                    "max_depth", 1));
            invoke(checks, tools, context, "code_execution", Map.of(
                    "source", sessionWriteProbeSource(),
                    "timeoutSeconds", 120,
                    "importTier", "extended"));
            invoke(checks, tools, context, "code_execution", Map.of(
                    "source", pytestProbeSource(),
                    "timeoutSeconds", 120,
                    "importTier", "extended"));
            readCheck(checks, "code_execution_session_state",
                    workspace.resolve("session_state.txt"),
                    "code-execution-session-ok\n");
            checkFile(checks, "code_execution_test_file",
                    workspace.resolve("test_preflight_target.py"));
            checkAbsent(checks, "code_execution_transient_not_synced",
                    workspace.resolve("__last_source__.py"));
            ledgerCheck(checks, context);

            if (tools.getToolMap().containsKey("bash")) {
                invoke(checks, tools, context, "bash", Map.of(
                        "command", "python -c \"print('BASH_OK')\"",
                        "timeout_sec", 120));
            } else {
                JSONObject skipped = new JSONObject(true);
                skipped.put("name", "bash");
                skipped.put("tool", "bash");
                skipped.put("status", "unavailable");
                skipped.put("required", Boolean.FALSE);
                checks.add(skipped);
            }

            boolean requiredToolsPresent = REQUIRED_TOOLS.stream().allMatch(names::contains);
            boolean requiredPassed = requiredToolsPresent
                    && checks.stream()
                    .map(item -> (JSONObject) item)
                    .filter(item -> item.getBooleanValue("required"))
                    .allMatch(item -> "ok".equals(item.getString("status")));
            report.put("requiredTools", REQUIRED_TOOLS);
            report.put("requiredPassed", requiredPassed);
            Files.writeString(
                    output,
                    report.toJSONString(),
                    StandardCharsets.UTF_8);
            ExecutionLedgerRunSupport.finishRun(
                    context,
                    ExecutionLedgerConstants.STATUS_SUCCESS,
                    "tool preflight complete",
                    null,
                    null);
        }
        System.out.println("tool preflight report: " + output);
        JSONObject saved = JSON.parseObject(Files.readString(output, StandardCharsets.UTF_8));
        if (!saved.getBooleanValue("requiredPassed")) {
            System.exit(1);
        }
    }

    private static void invoke(JSONArray checks,
                               ToolCollection tools,
                               AgentContext context,
                               String toolName,
                               Map<String, Object> input) {
        JSONObject row = new JSONObject(true);
        row.put("name", "invoke_" + toolName);
        row.put("tool", toolName);
        row.put("required", REQUIRED_TOOLS.contains(toolName));
        if (tools.getTool(toolName) == null) {
            row.put("status", "unavailable");
            row.put("detail", "tool is not registered");
            checks.add(row);
            return;
        }
        ToolArtifactSource source = ToolArtifactSource.builder()
                .sessionId(context.getSessionId())
                .requestId(context.getRequestId())
                .toolCallId("preflight-" + toolName)
                .toolName(toolName)
                .build();
        context.bindCurrentToolArtifactSource(source);
        try {
            Object raw = tools.executeResolved(toolName, input, context);
            boolean failed = raw == null;
            Object detail = raw;
            if (raw instanceof ToolResultPayload payload) {
                failed = Boolean.TRUE.equals(payload.getFailed());
                detail = payload.getLlmData() != null
                        ? payload.getLlmData()
                        : payload.getToolResult();
            }
            row.put("status", failed ? "error" : "ok");
            String serialized = detail == null ? "" : JSON.toJSONString(detail);
            if (serialized.length() > 4000) {
                serialized = serialized.substring(0, 4000);
            }
            row.put("detail", serialized);
        } catch (RuntimeException exc) {
            row.put("status", "error");
            row.put("detail", exc.getClass().getSimpleName() + ": " + exc.getMessage());
        } finally {
            context.clearCurrentToolArtifactSource();
        }
        checks.add(row);
    }

    private static void readCheck(JSONArray checks,
                                  String name,
                                  Path path,
                                  String expected) {
        JSONObject row = new JSONObject(true);
        row.put("name", name);
        row.put("tool", "host_filesystem");
        row.put("required", Boolean.TRUE);
        try {
            String actual = Files.readString(path, StandardCharsets.UTF_8);
            row.put("status", expected.equals(actual) ? "ok" : "error");
            row.put("detail", actual);
        } catch (Exception exc) {
            row.put("status", "error");
            row.put("detail", exc.getClass().getSimpleName() + ": " + exc.getMessage());
        }
        checks.add(row);
    }

    private static void artifactCheck(JSONArray checks,
                                      AgentContext context,
                                      String toolCallId,
                                      String relativePath) {
        JSONObject row = new JSONObject(true);
        row.put("name", "artifact_registered");
        row.put("tool", "file_artifact");
        row.put("required", Boolean.TRUE);
        boolean registered = context.getArtifactBindingsByToolCallId(toolCallId)
                .stream()
                .anyMatch(binding -> binding != null
                        && binding.getFile() != null
                        && relativePath.equals(binding.getFile().getRelativePath()));
        row.put("status", registered ? "ok" : "error");
        row.put("detail", registered
                ? relativePath
                : "workspace file was not registered as an artifact");
        checks.add(row);
    }

    private static void ledgerCheck(JSONArray checks, AgentContext context) {
        JSONObject row = new JSONObject(true);
        row.put("name", "execution_ledger_round_trip");
        row.put("tool", "execution_ledger");
        row.put("required", Boolean.TRUE);
        try {
            LocalDateTime now = LocalDateTime.now();
            Long llmId = context.getExecutionRecorder().createLlmInvocation(
                    LlmInvocationStartRecord.builder()
                            .runId(context.getAgentRunState().getRunId())
                            .requestId(context.getRequestId())
                            .sessionId(context.getSessionId())
                            .invocationSeq(1)
                            .agentName("preflight")
                            .agentId("main")
                            .stepNo(1)
                            .callKind(ExecutionLedgerConstants.CALL_KIND_ASK_TOOL)
                            .streaming(Boolean.FALSE)
                            .modelName("preflight")
                            .startedAt(now)
                            .build());
            if (llmId == null) {
                row.put("status", "error");
                row.put("detail", "createLlmInvocation returned null");
                checks.add(row);
                return;
            }

            String toolCallId = "preflight-ledger-tool";
            Map<String, Long> toolIds = context.getExecutionRecorder().createToolInvocations(
                    ToolInvocationBatchStartRecord.builder()
                            .runId(context.getAgentRunState().getRunId())
                            .requestId(context.getRequestId())
                            .sessionId(context.getSessionId())
                            .llmInvocationId(llmId)
                            .agentName("preflight")
                            .stepNo(1)
                            .items(List.of(ToolInvocationBatchStartRecord.Item.builder()
                                    .toolCallId(toolCallId)
                                    .dispatchIndex(1)
                                    .toolName("preflight_probe")
                                    .toolProvider(ExecutionLedgerConstants.TOOL_PROVIDER_LOCAL)
                                    .inputJson("{}")
                                    .startedAt(now)
                                    .build()))
                            .build());
            Long toolId = toolIds.get(toolCallId);
            if (toolId == null) {
                row.put("status", "error");
                row.put("detail", "createToolInvocations returned no id");
                checks.add(row);
                return;
            }

            context.getExecutionRecorder().finishToolInvocation(
                    ToolInvocationFinishRecord.builder()
                            .toolInvocationId(toolId)
                            .runId(context.getAgentRunState().getRunId())
                            .requestId(context.getRequestId())
                            .sessionId(context.getSessionId())
                            .toolCallId(toolCallId)
                            .toolName("preflight_probe")
                            .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                            .llmObservation("preflight ok")
                            .toolResult("{\"ok\":true}")
                            .finishedAt(LocalDateTime.now())
                            .build());
            context.getExecutionRecorder().finishLlmInvocation(
                    LlmInvocationFinishRecord.builder()
                            .llmInvocationId(llmId)
                            .requestId(context.getRequestId())
                            .sessionId(context.getSessionId())
                            .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                            .responseText("preflight")
                            .toolCallCount(1)
                            .finishedAt(LocalDateTime.now())
                            .build());
            row.put("status", "ok");
            row.put("detail", "llmInvocationId=" + llmId + ", toolInvocationId=" + toolId);
        } catch (RuntimeException exc) {
            row.put("status", "error");
            row.put("detail", exc.getClass().getSimpleName() + ": " + exc.getMessage());
        }
        checks.add(row);
    }

    private static void checkFile(JSONArray checks, String name, Path path) {
        JSONObject row = new JSONObject(true);
        row.put("name", name);
        row.put("tool", "host_filesystem");
        row.put("required", Boolean.TRUE);
        row.put("status", Files.isRegularFile(path) ? "ok" : "error");
        row.put("detail", path.toString());
        checks.add(row);
    }

    private static void checkAbsent(JSONArray checks, String name, Path path) {
        JSONObject row = new JSONObject(true);
        row.put("name", name);
        row.put("tool", "host_filesystem");
        row.put("required", Boolean.TRUE);
        row.put("status", Files.exists(path) ? "error" : "ok");
        row.put("detail", path.toString());
        checks.add(row);
    }

    private static String sessionWriteProbeSource() {
        return """
                from pathlib import Path

                Path("session_state.txt").write_text(
                    "code-execution-session-ok\\n", encoding="utf-8"
                )
                print("SESSION_WRITE_OK")
                """;
    }

    private static String pytestProbeSource() {
        return """
                from pathlib import Path
                import subprocess
                import sys

                assert Path("preflight.txt").read_text(encoding="utf-8") == "v2\\n"
                assert Path("session_state.txt").read_text(encoding="utf-8") == (
                    "code-execution-session-ok\\n"
                )
                Path("preflight_target.py").write_text(
                    "VALUE = 41\\n", encoding="utf-8"
                )
                Path("test_preflight_target.py").write_text(
                    "from preflight_target import VALUE\\n\\n"
                    "def test_value():\\n"
                    "    assert VALUE == 41\\n",
                    encoding="utf-8"
                )
                completed = subprocess.run(
                    [sys.executable, "-m", "pytest", "-q", "test_preflight_target.py"],
                    cwd=".",
                    text=True,
                    capture_output=True,
                )
                print("PYTEST_RC=" + str(completed.returncode))
                print(completed.stdout)
                print(completed.stderr)
                if completed.returncode != 0:
                    raise RuntimeError("target pytest failed")
                """;
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
