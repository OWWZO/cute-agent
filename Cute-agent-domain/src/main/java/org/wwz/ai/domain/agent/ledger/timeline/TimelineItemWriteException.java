package org.wwz.ai.domain.agent.ledger.timeline;

/**
 * 时间线目录写入失败。Ledger 事务必须回滚，由调用方决定补写或降级。
 */
public class TimelineItemWriteException extends RuntimeException {

    public TimelineItemWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
