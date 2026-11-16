package org.wwz.ai.domain.agent.ledger;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionFile;
import org.wwz.ai.domain.agent.ledger.model.ArtifactRecordCommand;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunFinishRecord;
import org.wwz.ai.domain.agent.ledger.model.DialogueRunStartRecord;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.runtime.command.AgentExecutionCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行账本运行态辅助方法。
 * 统一收口 run 的启动、输入产物登记和结束写回，避免多条执行链路重复实现。
 */
@Slf4j
public final class ExecutionLedgerRunSupport {

    private ExecutionLedgerRunSupport() {
    }

    /**
     * 账本 run 的稳定 requestId：优先使用激活时捕获的 runUid，避免旧续跑路径改写
     * context.requestId 后写到另一个 run。
     */
    public static String ledgerRequestId(AgentContext agentContext) {
        return agentContext == null || agentContext.getAgentRunState() == null
                ? null
                : StringUtils.defaultIfBlank(
                        agentContext.getAgentRunState().getRunUid(), agentContext.getRequestId());
    }

    /**
     * 创建 run 并登记本次请求携带的输入文件。
     */
    public static void initializeRun(AgentExecutionRecorder recorder,
                                     AgentContext agentContext,
                                     AgentExecutionCommand request,
                                     String entryAgent) {
        if (recorder == null || agentContext == null || request == null) {
            return;
        }
        Long runId = recorder.createRun(DialogueRunStartRecord.builder()
                .runUid(request.getRequestId())
                .requestId(request.getRequestId())
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .entryAgent(entryAgent)
                .queryText(StringUtils.defaultIfBlank(request.getLedgerQueryText(), request.getQuery()))
                .build());
        agentContext.activateLedgerRun(runId, request.getRequestId());
        recordInputArtifacts(recorder, request.getSessionFiles(), runId, request.getRequestId());
    }

    /**
     * 结束当前 run。
     * 成功态写入 finalSummaryText，非成功态写入 errorCode / errorMsg。
     */
    public static void finishRun(AgentContext agentContext,
                                 int status,
                                 String finalSummaryText,
                                 String errorCode,
                                 String errorMsg) {
        if (agentContext != null) {
            // 先打内存终态，避免后台子 Agent 在 ledger 写回前误关父 SSE。
            agentContext.markTurnClosed();
        }
        if (!hasActiveRun(agentContext)) {
            return;
        }
        // 用激活 run 时捕获的 runUid 定位，而不是当前 context.requestId：
        // 子 Agent 旧续跑路径会改写 requestId，改后查不到 run 会静默丢终态、把 run 永久留在 RUNNING。
        String runUid = agentContext.getAgentRunState().getRunUid();
        DialogueRunFinishRecord record = DialogueRunFinishRecord.builder()
                .runId(agentContext.getAgentRunState().getRunId())
                .requestId(StringUtils.defaultIfBlank(runUid, agentContext.getRequestId()))
                .status(status)
                .finalSummaryText(finalSummaryText)
                .errorCode(errorCode)
                .errorMsg(errorMsg)
                .build();
        try {
            agentContext.getExecutionRecorder().finishRun(record);
        } catch (RuntimeException e) {
            log.warn("finish run ledger failed, retrying without timeline, requestId={}",
                    StringUtils.defaultIfBlank(runUid, agentContext.getRequestId()), e);
            compensateAfterRollback(agentContext.getExecutionRecorder(), record);
        }
    }

    /**
     * 目录失败会把当前 Ledger 事务标记为 rollback-only。若此时立即补写终态，
     * 新写入仍会加入同一个待回滚事务，既补不进去还可能等待当前事务持有的行锁。
     * 因此把补偿放到事务完成之后，并用独立事务写回 run/session。
     */
    private static void compensateAfterRollback(AgentExecutionRecorder recorder,
                                                DialogueRunFinishRecord record) {
        if (recorder == null || record == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    finishWithoutTimeline(recorder, record);
                }
            });
            return;
        }
        finishWithoutTimeline(recorder, record);
    }

    private static void finishWithoutTimeline(AgentExecutionRecorder recorder,
                                              DialogueRunFinishRecord record) {
        try {
            recorder.finishRunWithoutTimeline(record);
        } catch (RuntimeException fallbackError) {
            log.error("finish run fallback failed, requestId={}",
                    record.getRequestId(), fallbackError);
        }
    }

    /**
     * 子智能体独立 run：LLM / tool / artifact 全部挂到子会话自己的 run 上，
     * 不计入父 run 的 llm_call_count / tool_call_count。
     * <p>
     * 与 {@link #initializeRun} 的区别：不走 {@code AgentExecutionCommand}，
     * 只按已就绪的 AgentContext 落 run，因此复用者只需提供 recorder 与已构建好的子上下文。
     * recorder 为无状态账本写入服务，父子可安全共享同一实例。
     */
    public static void initializeSubAgentRun(AgentExecutionRecorder recorder,
                                             AgentContext agentContext,
                                             String entryAgent,
                                             String queryText) {
        if (recorder == null || agentContext == null) {
            return;
        }
        Long runId = recorder.createRun(DialogueRunStartRecord.builder()
                .runUid(agentContext.getRequestId())
                .requestId(agentContext.getRequestId())
                .sessionId(agentContext.getSessionId())
                .userId(agentContext.getUserId())
                .entryAgent(entryAgent)
                .queryText(queryText)
                .build());
        // runId 为空表示落库失败；此时不激活 run，子 Agent 的 LLM/tool 记账自然跳过
        // （LLM / ToolExecutionPipeline 均以 hasActiveLedgerRun 为闸门），不会误记到父 run。
        agentContext.activateLedgerRun(runId, agentContext.getRequestId());
    }

    private static void recordInputArtifacts(AgentExecutionRecorder recorder,
                                              List<AgentExecutionFile> sessionFiles,
                                             Long runId,
                                             String requestId) {
        List<ArtifactRecordCommand> inputArtifacts = buildInputArtifacts(sessionFiles, runId, requestId);
        if (!inputArtifacts.isEmpty()) {
            try {
                recorder.recordArtifacts(inputArtifacts);
            } catch (RuntimeException e) {
                log.warn("input artifact ledger write failed, requestId={}", requestId, e);
            }
        }
    }

    private static List<ArtifactRecordCommand> buildInputArtifacts(List<AgentExecutionFile> sessionFiles,
                                                                   Long runId,
                                                                   String requestId) {
        if (runId == null || sessionFiles == null || sessionFiles.isEmpty()) {
            return List.of();
        }
        List<ArtifactRecordCommand> records = new ArrayList<>(sessionFiles.size());
        for (AgentExecutionFile sessionFile : sessionFiles) {
            if (!hasValidFileName(sessionFile)) {
                continue;
            }
            records.add(ArtifactRecordCommand.builder()
                    .runId(runId)
                    .requestId(requestId)
                    .artifactRole(ExecutionLedgerConstants.ARTIFACT_ROLE_INPUT)
                    .visibility(ExecutionLedgerConstants.VISIBILITY_VISIBLE)
                    .sourceType(ExecutionLedgerConstants.SOURCE_TYPE_USER_UPLOAD)
                    .sourceName(StringUtils.defaultIfBlank(
                            sessionFile.getOriginFileName(), sessionFile.getFileName()))
                    .fileName(sessionFile.getFileName())
                    .storageKey(resolveStorageKey(sessionFile))
                    .downloadUrl(sessionFile.getOssUrl())
                    .previewUrl(sessionFile.getDomainUrl())
                    .mimeType(sessionFile.getMimeType())
                    .fileSize(sessionFile.getFileSize() == null ? null : sessionFile.getFileSize().longValue())
                    .metadataJson(buildInputMetadata(sessionFile))
                    .build());
        }
        return records;
    }

    private static boolean hasActiveRun(AgentContext agentContext) {
        return agentContext != null
                && agentContext.hasActiveLedgerRun()
                && agentContext.getAgentRunState() != null;
    }

    private static boolean hasValidFileName(AgentExecutionFile sessionFile) {
        return sessionFile != null && StringUtils.isNotBlank(sessionFile.getFileName());
    }

    private static String resolveStorageKey(AgentExecutionFile sessionFile) {
        if (StringUtils.isNotBlank(sessionFile.getResourceKey())) {
            return sessionFile.getResourceKey();
        }
        if (StringUtils.isNotBlank(sessionFile.getOriginOssUrl())) {
            return sessionFile.getOriginOssUrl();
        }
        if (StringUtils.isNotBlank(sessionFile.getOssUrl())) {
            return sessionFile.getOssUrl();
        }
        if (StringUtils.isNotBlank(sessionFile.getOriginDomainUrl())) {
            return sessionFile.getOriginDomainUrl();
        }
        if (StringUtils.isNotBlank(sessionFile.getDomainUrl())) {
            return sessionFile.getDomainUrl();
        }
        return sessionFile.getFileName();
    }

    private static String buildInputMetadata(AgentExecutionFile sessionFile) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(sessionFile.getFileDesc())) {
            metadata.put("fileDesc", sessionFile.getFileDesc());
        }
        if (StringUtils.isNotBlank(sessionFile.getFileType())) {
            metadata.put("fileType", sessionFile.getFileType());
        }
        if (StringUtils.isNotBlank(sessionFile.getOriginFileName())) {
            metadata.put("originFileName", sessionFile.getOriginFileName());
        }
        if (StringUtils.isNotBlank(sessionFile.getRelativePath())) {
            metadata.put("relativePath", sessionFile.getRelativePath());
        }
        if (StringUtils.isNotBlank(sessionFile.getOriginFileUrl())) {
            metadata.put("originFileUrl", sessionFile.getOriginFileUrl());
        }
        if (metadata.isEmpty()) {
            return null;
        }
        return JSON.toJSONString(metadata);
    }
}
