package org.wwz.ai.application.agent.authorization;

/**
 * 会话不可交互异常。
 * <p>
 * 用于除 {@code conversation} 与 {@code subagent} 外的只读或未知会话类型：
 * owner 仍可读取 history / stream，但相关会话不得提交新 query 或执行续跑。
 * 服务端强制校验，不依赖前端隐藏 composer。
 */
public class SessionNotInteractiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SessionNotInteractiveException(String message) {
        super(message);
    }

    public SessionNotInteractiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
