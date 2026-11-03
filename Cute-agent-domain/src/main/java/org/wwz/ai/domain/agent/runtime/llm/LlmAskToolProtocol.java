package org.wwz.ai.domain.agent.runtime.llm;

/**
 * 请求协议分支标识。
 * function_call：Spring AI 原生 tools[]；struct_parse：schema 写入 system + JSON 解析；
 * text：无 tools 的纯文本 ask。
 * <p>每个常量携带对外 wire 码，避免各调用点重复写字面量。</p>
 */
public enum LlmAskToolProtocol {
    FUNCTION_CALL("function_call"),
    STRUCT_PARSE("struct_parse"),
    TEXT("text");

    private final String code;

    LlmAskToolProtocol(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 按 wire 码解析；未知码（含 null）回退 {@link #FUNCTION_CALL}，与既有
     * {@code "struct_parse".equals(type)} 的判定保持一致（精确匹配，不做大小写归一）。
     */
    public static LlmAskToolProtocol fromCode(String code) {
        for (LlmAskToolProtocol protocol : values()) {
            if (protocol.code.equals(code)) {
                return protocol;
            }
        }
        return FUNCTION_CALL;
    }
}
