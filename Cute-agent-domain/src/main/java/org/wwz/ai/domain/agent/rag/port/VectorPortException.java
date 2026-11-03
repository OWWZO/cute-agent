package org.wwz.ai.domain.agent.rag.port;

/**
 * 向量端口（检索 / 索引管理）存储端失败时抛出的领域异常。
 * <p>
 * 端口不再声明 {@code throws Exception}：连接失败、超时、存储端拒绝等真实故障
 * 统一以此 unchecked 异常表达，调用方可据此把「向量链路失败」与其他业务异常区分开，
 * 单独决定重试或降级；参数类编程错误仍以 {@link IllegalArgumentException} 表达。
 * </p>
 */
public class VectorPortException extends RuntimeException {

    public VectorPortException(String message, Throwable cause) {
        super(message, cause);
    }

    public VectorPortException(String message) {
        super(message);
    }
}
