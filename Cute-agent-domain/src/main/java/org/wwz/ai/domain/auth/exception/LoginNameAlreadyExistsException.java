package org.wwz.ai.domain.auth.exception;

/** Raised when account persistence detects a duplicate login name. */
public class LoginNameAlreadyExistsException extends RuntimeException {

    public LoginNameAlreadyExistsException() {
        super("Login name already exists");
    }

    /**
     * @param loginName 触发唯一约束的登录名
     * @param cause     底层持久化异常，用于保留根因栈
     */
    public LoginNameAlreadyExistsException(String loginName, Throwable cause) {
        super("Login name already exists: " + loginName, cause);
    }
}
