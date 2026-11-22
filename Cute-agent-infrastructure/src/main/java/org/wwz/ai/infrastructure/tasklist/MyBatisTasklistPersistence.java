package org.wwz.ai.infrastructure.tasklist;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.domain.agent.runtime.tasklist.RuntimeBackgroundTask;
import org.wwz.ai.domain.agent.runtime.tasklist.SessionTaskItem;
import org.wwz.ai.domain.agent.runtime.tasklist.TasklistPersistencePort;
import org.wwz.ai.infrastructure.dao.cute.IBackgroundTaskDao;
import org.wwz.ai.infrastructure.dao.cute.ISessionTodoDao;
import org.wwz.ai.infrastructure.dao.cute.po.BackgroundTaskPO;
import org.wwz.ai.infrastructure.dao.cute.po.SessionTodoPO;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Todo / 后台任务 MyBatis 持久化实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyBatisTasklistPersistence implements TasklistPersistencePort {

    private final ISessionTodoDao sessionTodoDao;
    private final IBackgroundTaskDao backgroundTaskDao;

    @Override
    public List<SessionTaskItem> loadTodos(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return List.of();
        }
        List<SessionTodoPO> rows = sessionTodoDao.selectBySessionId(sessionId.trim());
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<SessionTaskItem> items = new ArrayList<>();
        for (SessionTodoPO row : rows) {
            SessionTaskItem item = toTodoItem(row);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    @Override
    public void replaceTodos(String sessionId, List<SessionTaskItem> items) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        String sid = sessionId.trim();
        sessionTodoDao.softDeleteAllBySessionId(sid);
        if (items == null || items.isEmpty()) {
            return;
        }
        for (SessionTaskItem item : items) {
            if (item == null || StringUtils.isBlank(item.getId())) {
                continue;
            }
            sessionTodoDao.upsert(toTodoPo(sid, item));
        }
    }

    @Override
    public List<RuntimeBackgroundTask> loadBackgroundTasks(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return List.of();
        }
        List<BackgroundTaskPO> rows = backgroundTaskDao.selectBySessionId(sessionId.trim());
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<RuntimeBackgroundTask> tasks = new ArrayList<>();
        for (BackgroundTaskPO row : rows) {
            RuntimeBackgroundTask task = toBackgroundTask(row);
            if (task != null) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    @Override
    public Optional<RuntimeBackgroundTask> findBackgroundTask(String sessionId, String taskId) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(taskId)) {
            return Optional.empty();
        }
        BackgroundTaskPO row = backgroundTaskDao.selectBySessionAndTaskId(sessionId.trim(), taskId.trim());
        return Optional.ofNullable(toBackgroundTask(row));
    }

    @Override
    public Optional<RuntimeBackgroundTask> findActiveBackgroundTaskByChildSessionId(String childSessionId) {
        if (StringUtils.isBlank(childSessionId)) {
            return Optional.empty();
        }
        BackgroundTaskPO row = backgroundTaskDao.selectActiveByChildSessionId(childSessionId.trim());
        return Optional.ofNullable(toBackgroundTask(row));
    }

    @Override
    public List<RuntimeBackgroundTask> findRunningBackgroundTasksByOwner(String rootSessionId,
                                                                         String ownerAgentId) {
        if (StringUtils.isBlank(rootSessionId) || StringUtils.isBlank(ownerAgentId)) {
            return List.of();
        }
        List<BackgroundTaskPO> rows = backgroundTaskDao.selectRunningByOwnerAgentId(
                rootSessionId.trim(), ownerAgentId.trim());
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<RuntimeBackgroundTask> tasks = new ArrayList<>(rows.size());
        for (BackgroundTaskPO row : rows) {
            RuntimeBackgroundTask task = toBackgroundTask(row);
            if (task != null) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    @Override
    public void upsertBackgroundTask(String sessionId, RuntimeBackgroundTask task) {
        if (StringUtils.isBlank(sessionId) || task == null || StringUtils.isBlank(task.getId())) {
            return;
        }
        try {
            backgroundTaskDao.upsert(toBackgroundPo(sessionId.trim(), task));
        } catch (Exception e) {
            log.error("upsert background task failed sessionId={} taskId={}: {}",
                    sessionId, task.getId(), e.getMessage(), e);
            throw e;
        }
    }

    private static SessionTaskItem toTodoItem(SessionTodoPO row) {
        if (row == null) {
            return null;
        }
        return SessionTaskItem.builder()
                .id(row.getTaskId())
                .subject(row.getSubject())
                .description(row.getDescription())
                .activeForm(row.getActiveForm())
                .status(row.getStatus())
                .build();
    }

    private static SessionTodoPO toTodoPo(String sessionId, SessionTaskItem item) {
        SessionTodoPO po = new SessionTodoPO();
        po.setSessionId(sessionId);
        po.setTaskId(item.getId());
        po.setSubject(item.getSubject());
        po.setDescription(item.getDescription());
        po.setActiveForm(item.getActiveForm());
        po.setStatus(item.getStatus());
        int seq = 0;
        try {
            seq = Integer.parseInt(item.getId());
        } catch (NumberFormatException ignored) {
            seq = 0;
        }
        po.setSeqNo(seq);
        return po;
    }

    /**
     * PO → 领域任务。包外复用（启动对账器扫出的孤儿行也要走同一套映射），故对包外可见。
     */
    public static RuntimeBackgroundTask toBackgroundTask(BackgroundTaskPO row) {
        if (row == null) {
            return null;
        }
        return RuntimeBackgroundTask.builder()
                .id(row.getTaskId())
                .type(row.getType())
                .status(row.getStatus())
                .description(row.getDescription())
                .command(row.getCommand())
                .agentId(row.getAgentId())
                .agentType(row.getAgentType())
                .childSessionId(row.getChildSessionId())
                .parentSessionId(row.getParentSessionId())
                .parentRequestId(row.getParentRequestId())
                .parentToolUseId(row.getParentToolUseId())
                .parentAgentId(row.getParentAgentId())
                .ownerAgentId(row.getOwnerAgentId())
                .rootSessionId(row.getRootSessionId())
                .delegationDepth(row.getDelegationDepth())
                .phase(row.getPhase())
                .executionMode(row.getExecutionMode())
                .closingTurnCount(row.getClosingTurnCount())
                .prompt(row.getPrompt())
                .output(row.getOutput())
                .errorMsg(row.getErrorMsg())
                .totalToolUseCount(row.getTotalToolUseCount())
                .totalDurationMs(row.getTotalDurationMs())
                .startedAtMs(row.getStartedAtMs() == null ? 0L : row.getStartedAtMs())
                .endedAtMs(row.getEndedAtMs())
                .notified(Boolean.TRUE.equals(row.getNotified()))
                .build();
    }

    private static BackgroundTaskPO toBackgroundPo(String sessionId, RuntimeBackgroundTask task) {
        BackgroundTaskPO po = new BackgroundTaskPO();
        po.setSessionId(sessionId);
        po.setTaskId(task.getId());
        po.setType(task.getType());
        po.setStatus(task.getStatus());
        po.setDescription(task.getDescription());
        po.setCommand(task.getCommand());
        po.setAgentId(task.getAgentId());
        po.setAgentType(task.getAgentType());
        po.setChildSessionId(task.getChildSessionId());
        po.setParentSessionId(task.getParentSessionId());
        po.setParentRequestId(task.getParentRequestId());
        po.setParentToolUseId(task.getParentToolUseId());
        po.setParentAgentId(task.getParentAgentId());
        po.setOwnerAgentId(task.getOwnerAgentId());
        po.setRootSessionId(task.getRootSessionId());
        po.setDelegationDepth(task.getDelegationDepth());
        po.setPhase(task.getPhase());
        po.setExecutionMode(task.getExecutionMode());
        po.setClosingTurnCount(task.getClosingTurnCount());
        po.setPrompt(task.getPrompt());
        po.setOutput(task.getOutput());
        po.setErrorMsg(task.getErrorMsg());
        po.setTotalToolUseCount(task.getTotalToolUseCount());
        po.setTotalDurationMs(task.getTotalDurationMs());
        po.setStartedAtMs(task.getStartedAtMs());
        po.setEndedAtMs(task.getEndedAtMs());
        po.setNotified(Boolean.TRUE.equals(task.getNotified()));
        return po;
    }

}
