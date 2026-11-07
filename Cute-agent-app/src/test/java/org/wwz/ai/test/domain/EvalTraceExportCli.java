package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.wwz.ai.Application;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerQueryService;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunView;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.ExecutionRunDetail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 测试/运维专用的 Execution Ledger 只读导出入口。
 *
 * <p>不启动 Web Server，不新增 HTTP 接口，也不写产品数据库。导出副本在落盘前统一脱敏，
 * 原始 Trace 不落盘。</p>
 */
public final class EvalTraceExportCli {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_EXPORT_RUNS = 500;
    private static final List<Integer> DEFAULT_STATUSES = List.of(
            ExecutionLedgerConstants.STATUS_SUCCESS,
            ExecutionLedgerConstants.STATUS_FAILED,
            ExecutionLedgerConstants.STATUS_TIMEOUT,
            ExecutionLedgerConstants.STATUS_STOPPED
    );
    private static final Map<String, Integer> STATUS_BY_NAME = Map.of(
            "SUCCESS", ExecutionLedgerConstants.STATUS_SUCCESS,
            "FAILED", ExecutionLedgerConstants.STATUS_FAILED,
            "TIMEOUT", ExecutionLedgerConstants.STATUS_TIMEOUT,
            "STOPPED", ExecutionLedgerConstants.STATUS_STOPPED
    );

    private EvalTraceExportCli() {
    }

    public static void main(String[] args) throws Exception {
        ExportRequest request = ExportRequest.parse(args);
        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(request.springProfile);
        try (ConfigurableApplicationContext context = application.run()) {
            ExecutionLedgerQueryService query =
                    context.getBean(ExecutionLedgerQueryService.class);
            List<ExecutionRunDetail> details = loadDetails(query, request);
            List<Path> traces = writeTraces(
                    details,
                    request.outDir,
                    environment(request),
                    request.statuses
            );
            JSONObject manifest = new JSONObject();
            manifest.put("schemaVersion", "1.0");
            manifest.put("source", request.sourceName());
            manifest.put("springProfile", request.springProfile);
            manifest.put("exportedTraces", traces.size());
            manifest.put("maxRuns", request.limit);
            manifest.put("statuses", statusNames(request.statuses));
            if (request.from != null) {
                manifest.put("from", request.from.toString());
                manifest.put("to", request.to.toString());
            }
            manifest.put("traces", traces.stream()
                    .map(path -> request.outDir.relativize(path).toString().replace('\\', '/'))
                    .toList());
            Files.writeString(
                    request.outDir.resolve("export-manifest.json"),
                    manifest.toJSONString(),
                    StandardCharsets.UTF_8
            );
            System.out.println("traces=" + traces.size());
            System.out.println("manifest=" + request.outDir.resolve("export-manifest.json"));
        }
    }

    static List<Path> writeTraces(List<ExecutionRunDetail> details,
                                  Path outDir,
                                  JSONObject environment) throws Exception {
        return writeTraces(details, outDir, environment, DEFAULT_STATUSES);
    }

    static List<Path> writeTraces(List<ExecutionRunDetail> details,
                                  Path outDir,
                                  JSONObject environment,
                                  List<Integer> statuses) throws Exception {
        Files.createDirectories(outDir);
        List<Path> paths = new ArrayList<>();
        for (ExecutionRunDetail detail : details) {
            if (!matchesStatus(detail, statuses)) {
                continue;
            }
            String requestId = detail.getRun().getRequestId();
            JSONObject rawTrace = EvalFixtureRunnerCli.toTrace(null, detail, environment);
            JSONObject sanitized = EvalTraceSanitizer.sanitize(rawTrace);
            Path traceDir = outDir.resolve(safeName(requestId));
            Files.createDirectories(traceDir);
            Path tracePath = traceDir.resolve("trace.json");
            Files.writeString(
                    tracePath,
                    JSON.toJSONString(
                            sanitized,
                            com.alibaba.fastjson.serializer.SerializerFeature.PrettyFormat,
                            com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue
                    ),
                    StandardCharsets.UTF_8
            );
            paths.add(tracePath);
        }
        return paths;
    }

    private static List<ExecutionRunDetail> loadDetails(ExecutionLedgerQueryService query,
                                                         ExportRequest request) {
        if (request.requestId != null) {
            ExecutionRunDetail detail = query.queryRunDetail(request.requestId);
            return detail == null ? List.of() : List.of(detail);
        }
        List<DialogueRunView> runs = new ArrayList<>();
        if (request.sessionId != null) {
            int offset = 0;
            while (runs.size() < request.limit) {
                int pageSize = Math.min(PAGE_SIZE, request.limit - runs.size());
                List<DialogueRunView> page = query.querySessionRuns(
                        request.sessionId,
                        offset,
                        pageSize
                );
                if (page == null || page.isEmpty()) {
                    break;
                }
                page.stream()
                        .filter(run -> matchesStatus(run, request.statuses))
                        .limit(request.limit - runs.size())
                        .forEach(runs::add);
                offset += page.size();
                if (page.size() < pageSize) {
                    break;
                }
            }
        } else {
            LocalDateTime afterCreateTime = null;
            Long afterId = null;
            while (runs.size() < request.limit) {
                int pageSize = Math.min(PAGE_SIZE, request.limit - runs.size());
                List<DialogueRunView> page = query.queryRunsByTimeRange(
                        request.from,
                        request.to,
                        request.statuses,
                        afterCreateTime,
                        afterId,
                        pageSize
                );
                if (page == null || page.isEmpty()) {
                    break;
                }
                runs.addAll(page);
                DialogueRunView last = page.get(page.size() - 1);
                if (last.getCreateTime() == null || last.getId() == null) {
                    break;
                }
                afterCreateTime = last.getCreateTime();
                afterId = last.getId();
                if (page.size() < pageSize) {
                    break;
                }
            }
        }
        List<DialogueRunView> selected = runs.stream()
                .filter(run -> matchesStatus(run, request.statuses))
                .limit(request.limit)
                .toList();
        List<ExecutionRunDetail> details = new ArrayList<>(selected.size());
        for (int start = 0; start < selected.size(); start += PAGE_SIZE) {
            int end = Math.min(start + PAGE_SIZE, selected.size());
            details.addAll(query.queryRunDetails(selected.subList(start, end)));
        }
        return details;
    }

    private static boolean matchesStatus(ExecutionRunDetail detail, List<Integer> statuses) {
        return detail != null
                && detail.getRun() != null
                && matchesStatus(detail.getRun(), statuses);
    }

    private static boolean matchesStatus(DialogueRunView run, List<Integer> statuses) {
        Integer status = run == null ? null : run.getStatus();
        return status != null
                && status != ExecutionLedgerConstants.STATUS_RUNNING
                && status != ExecutionLedgerConstants.STATUS_WAITING_INPUT
                && (statuses == null || statuses.isEmpty() || statuses.contains(status));
    }

    private static JSONObject environment(ExportRequest request) {
        JSONObject environment = new JSONObject();
        environment.put("profile", "ledger-export");
        environment.put("mode", "read-only");
        environment.put("springProfile", request.springProfile);
        environment.put("exportSource", request.sourceName());
        environment.put("statuses", statusNames(request.statuses));
        return environment;
    }

    private static List<String> statusNames(List<Integer> statuses) {
        return statuses.stream()
                .map(status -> switch (status) {
                    case ExecutionLedgerConstants.STATUS_SUCCESS -> "SUCCESS";
                    case ExecutionLedgerConstants.STATUS_FAILED -> "FAILED";
                    case ExecutionLedgerConstants.STATUS_TIMEOUT -> "TIMEOUT";
                    case ExecutionLedgerConstants.STATUS_STOPPED -> "STOPPED";
                    default -> "UNKNOWN";
                })
                .toList();
    }

    private static List<Integer> parseStatuses(String value) {
        Set<Integer> statuses = new LinkedHashSet<>();
        for (String raw : value.split(",")) {
            String name = raw.trim().toUpperCase(Locale.ROOT);
            Integer status = STATUS_BY_NAME.get(name);
            if (status == null) {
                throw new IllegalArgumentException(
                        "--status accepts SUCCESS,FAILED,TIMEOUT,STOPPED: " + name
                );
            }
            statuses.add(status);
        }
        if (statuses.isEmpty()) {
            throw new IllegalArgumentException("--status must not be empty");
        }
        return List.copyOf(statuses);
    }

    private static String safeName(String value) {
        String safe = value == null ? "unknown-run" : value.replaceAll("[^A-Za-z0-9._-]", "-");
        return safe.isBlank() ? "unknown-run" : safe;
    }

    private static final class ExportRequest {

        private final String requestId;
        private final String sessionId;
        private final LocalDateTime from;
        private final LocalDateTime to;
        private final Path outDir;
        private final int limit;
        private final String springProfile;
        private final List<Integer> statuses;

        private ExportRequest(String requestId,
                              String sessionId,
                              LocalDateTime from,
                              LocalDateTime to,
                              Path outDir,
                              int limit,
                              String springProfile,
                              List<Integer> statuses) {
            this.requestId = requestId;
            this.sessionId = sessionId;
            this.from = from;
            this.to = to;
            this.outDir = outDir;
            this.limit = limit;
            this.springProfile = springProfile;
            this.statuses = statuses;
        }

        private static ExportRequest parse(String[] args) {
            String requestId = null;
            String sessionId = null;
            LocalDateTime from = null;
            LocalDateTime to = null;
            Path outDir = null;
            int limit = 50;
            String springProfile = "eval";
            List<Integer> statuses = DEFAULT_STATUSES;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--run-id", "--request-id" -> requestId = next(args, ++i, args[i - 1]);
                    case "--session-id" -> sessionId = next(args, ++i, args[i - 1]);
                    case "--from" -> from = LocalDateTime.parse(next(args, ++i, args[i - 1]));
                    case "--to" -> to = LocalDateTime.parse(next(args, ++i, args[i - 1]));
                    case "--out" -> outDir = Path.of(next(args, ++i, args[i - 1]));
                    case "--limit" -> limit = Integer.parseInt(next(args, ++i, args[i - 1]));
                    case "--spring-profile" -> springProfile = next(args, ++i, args[i - 1]);
                    case "--status" -> statuses = parseStatuses(next(args, ++i, args[i - 1]));
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            int selectors = (requestId == null ? 0 : 1)
                    + (sessionId == null ? 0 : 1)
                    + (from != null || to != null ? 1 : 0);
            if (selectors != 1 || outDir == null) {
                throw new IllegalArgumentException(usage());
            }
            if ((from == null) != (to == null) || (from != null && !from.isBefore(to))) {
                throw new IllegalArgumentException("--from/--to must be provided together and from < to");
            }
            if (limit < 1 || limit > MAX_EXPORT_RUNS) {
                throw new IllegalArgumentException("--limit must be between 1 and " + MAX_EXPORT_RUNS);
            }
            if ("dev".equalsIgnoreCase(springProfile) || "prod".equalsIgnoreCase(springProfile)) {
                throw new IllegalArgumentException("trace export refuses dev/prod Spring profiles");
            }
            return new ExportRequest(
                    requestId,
                    sessionId,
                    from,
                    to,
                    outDir.toAbsolutePath().normalize(),
                    limit,
                    springProfile,
                    statuses
            );
        }

        private String sourceName() {
            if (requestId != null) {
                return "request-id";
            }
            if (sessionId != null) {
                return "session-id";
            }
            return "time-range";
        }

        private static String next(String[] args, int index, String name) {
            if (index >= args.length || args[index].isBlank()) {
                throw new IllegalArgumentException("missing value for " + name);
            }
            return args[index];
        }

        private static String usage() {
            return "usage: EvalTraceExportCli "
                    + "(--run-id <requestId> | --session-id <sessionId> | "
                    + "--from <ISO_LOCAL_DATE_TIME> --to <ISO_LOCAL_DATE_TIME>) "
                    + "--out <dir> [--limit 1-500] "
                    + "[--status SUCCESS,FAILED,TIMEOUT,STOPPED] [--spring-profile eval]";
        }
    }
}
