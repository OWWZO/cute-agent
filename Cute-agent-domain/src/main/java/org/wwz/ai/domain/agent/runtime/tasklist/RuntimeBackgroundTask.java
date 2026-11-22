package org.wwz.ai.domain.agent.runtime.tasklist;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.wwz.ai.domain.agent.runtime.cancel.RunCancellation;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台运行任务。
 * 与 SessionTaskItem（Todo 列表）分离。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeBackgroundTask {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_FAILED = "failed";
    /**
     * 执行体随进程消亡、永远等不到终态回调的孤儿（进程重启 / OOM / 滚动发布）。
     * <p>
     * 与 {@link #STATUS_FAILED} 区分：failed 是任务自己跑失败了，lost 是「没人知道结果」。
     * 对齐参照仓库把 lost 做成一等终态的做法。
     */
    public static final String STATUS_LOST = "lost";

    public static final String TYPE_LOCAL_AGENT = "local_agent";
    public static final String TYPE_LOCAL_SHELL = "local_shell";
    public static final String TYPE_GENERIC = "generic";

    public static final String PHASE_RUNNING = "running";
    public static final String PHASE_WAITING_BACKGROUND = "waiting_background";

    public static final String EXECUTION_MODE_INLINE = "inline";
    public static final String EXECUTION_MODE_POOL = "pool";

    private String id;
    private String type;
    /**
     * 以下字段在对象发布进注册表后仍会被后台线程改写（complete/fail/stop、远端快照合并），
     * 而 API/UI 线程通过普通 getter 读取，因此必须是 volatile 建立可见性。
     */
    private volatile String status;
    private String description;
    private String command;
    private long startedAtMs;
    private volatile Long endedAtMs;

    /** 子 Agent id（local_agent） */
    private volatile String agentId;
    private volatile String agentType;
    /** 子 Agent 对应的真实子会话 ID（{@code sub_{agentId}}）；前端据此打开只读详情 */
    private volatile String childSessionId;
    private String prompt;

    /** 收到最终回传通知的直接父会话。 */
    private String parentSessionId;
    private String parentRequestId;
    private String parentToolUseId;
    private String parentAgentId;
    /** 创建这条后台任务的当前 Agent，用于隔离同一树内的任务。 */
    private String ownerAgentId;
    private String rootSessionId;
    private Integer delegationDepth;
    /** running / waiting_background；任务终态仍由 status 表达。 */
    private String phase;
    /** inline / pool。 */
    private String executionMode;
    /** 后台结果触发的新回合次数，用于限制静默收尾轮。 */
    private Integer closingTurnCount;

    /** 终态输出 / 错误 */
    private volatile String output;
    private volatile String errorMsg;
    private volatile Integer totalToolUseCount;
    private volatile Long totalDurationMs;

    /** 任务级取消令牌（TaskStop / 父 run cancel） */
    @ToString.Exclude
    @JSONField(serialize = false)
    private volatile RunCancellation cancellation;

    /** 后台执行 Future，供 interrupt */
    @ToString.Exclude
    @JSONField(serialize = false)
    private volatile Future<?> future;

    /** 进程内通知权：完成路径入队去重（同一个任务不重复 offer）。 */
    @Builder.Default
    @ToString.Exclude
    @JSONField(serialize = false)
    private AtomicBoolean notificationClaimed = new AtomicBoolean(false);

    /** 跨重启通知权：true 表示通知已成功随工作记忆持久化，补投时跳过。 */
    @Builder.Default
    private Boolean notified = Boolean.FALSE;
}
