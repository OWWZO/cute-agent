package org.wwz.ai.domain.agent.runtime.tool.common.planmode;

/**
 * Todo / Plan Mode 工具名常量。
 * TodoWrite 是唯一的会话 Todo 写入工具。
 */
public final class TaskToolNames {

    public static final String TASK_STOP = "TaskStop";
    public static final String TASK_OUTPUT = "TaskOutput";
    public static final String SEND_MESSAGE = "SendMessage";
    public static final String AGENT_LIST = "AgentList";
    public static final String TODO_WRITE = "TodoWrite";
    public static final String ENTER_PLAN_MODE = "EnterPlanMode";
    public static final String EXIT_PLAN_MODE = "ExitPlanMode";

    /** SSE 消息类型：会话任务列表快照 */
    public static final String SESSION_TASKS_EVENT = "session_tasks";

    private TaskToolNames() {
    }
}
