package org.wwz.ai.domain.agent.adapter.port.cli;

/**
 * 宿主 CLI 执行端口。
 * <p>domain 只表达「解析命令、执行命令」；进程创建、环境变量与输出收集由 infrastructure 承接。</p>
 */
public interface CliExecutionPort {

    /**
     * 执行一次 CLI 调用并同步等待结果。
     *
     * <p>失败契约：本方法不抛异常也不返回 null，所有失败都通过 {@link CliResult} 表达——
     * {@code invocation} 为 null 或 {@code tool} 空白返回 {@code ok=false, exitCode=1}；
     * 可执行文件找不到或拒绝执行返回 {@code ok=false, exitCode=127}；
     * 超时返回 {@code timedOut=true, exitCode=-1}（子进程会被强杀）。</p>
     *
     * <p>阻塞语义：阻塞调用线程直到子进程结束或 {@code timeoutMs} 到期
     * （{@code <=0} 时取 60s）。当前实现无共享可变状态，可并发调用。</p>
     *
     * @param invocation 调用描述，允许为 null（按失败结果返回而不是抛异常）
     * @return 非 null 的执行结果
     */
    CliResult exec(CliInvocation invocation);

    /**
     * 预检命令是否可解析为可执行文件。
     *
     * <p>入参为 null/空白、或传入的是「命令 + 参数」整串时返回 false：实现只做 trim，
     * 然后按单个命令名或路径解析，不做 shell 解析。结果仅供参考——PATH/环境可能在
     * 本方法与 {@link #exec(CliInvocation)} 之间变化，{@code exec} 仍会重新解析并
     * 安全失败（{@code ok=false}、exitCode=127），不能用本方法替代 exec 的错误处理。</p>
     */
    boolean isResolvable(String tool);
}
