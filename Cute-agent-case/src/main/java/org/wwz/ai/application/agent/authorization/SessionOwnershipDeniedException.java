package org.wwz.ai.application.agent.authorization;

/**
 * 会话归属校验失败异常。
 */
public class SessionOwnershipDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SessionOwnershipDeniedException(String message) {
        super(message);
    }

    public SessionOwnershipDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
