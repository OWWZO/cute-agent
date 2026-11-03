package org.wwz.ai.domain.agent.ledger.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Session run keyset cursor.
 *
 * <p>The encoded value is opaque to callers, but always carries the session,
 * create time and primary key needed by the ordered run query.</p>
 *
 * <p>字段不可变，只能通过 {@link #from}、{@link #validated}、{@link #decode} 构造，
 * 保证不变量（非空 sessionId/createTime、{@code id > 0}）在对象生命周期内始终成立。</p>
 */
@Getter
@EqualsAndHashCode
@ToString
public class RunCursor {

    private static final int VERSION = 1;
    private static final String INVALID_MESSAGE = "非法 cursor";

    private final String sessionId;

    private final LocalDateTime createTime;

    private final Long id;

    private RunCursor(String sessionId, LocalDateTime createTime, Long id) {
        this.sessionId = sessionId;
        this.createTime = createTime;
        this.id = id;
    }

    public static RunCursor from(DialogueRunView run) {
        if (run == null) {
            throw new IllegalArgumentException("run cursor source cannot be null");
        }
        return validated(run.getSessionId(), run.getCreateTime(), run.getId());
    }

    public static RunCursor validated(String sessionId, LocalDateTime createTime, Long id) {
        if (sessionId == null || sessionId.isBlank() || createTime == null || id == null || id <= 0) {
            throw new IllegalArgumentException("run cursor fields are invalid");
        }
        return new RunCursor(sessionId, createTime, id);
    }

    public String encode() {
        RunCursor cursor = validated(sessionId, createTime, id);
        return KeysetCursorCodec.encode(VERSION, output -> {
            output.writeUTF(cursor.sessionId);
            output.writeUTF(cursor.createTime.toString());
            output.writeLong(cursor.id);
        });
    }

    public static RunCursor decode(String encoded) {
        return KeysetCursorCodec.decode(encoded, VERSION, INVALID_MESSAGE, input -> {
            String sessionId = input.readUTF();
            LocalDateTime createTime = LocalDateTime.parse(input.readUTF());
            long id = input.readLong();
            return validated(sessionId, createTime, id);
        });
    }

    /**
     * cursor 是否属于指定 session。
     * <p>编码值未签名，{@code sessionId} 完全由调用方控制；任何消费 cursor 的查询路径
     * 都必须先调用本方法，否则会把另一个会话的 keyset 应用到当前会话，造成跳页/重复页。</p>
     */
    public boolean matchesSession(String sessionId) {
        return this.sessionId.equals(sessionId);
    }
}
