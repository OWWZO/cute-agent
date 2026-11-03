package org.wwz.ai.domain.agent.adapter.port.cli;

import lombok.Builder;
import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Value
@Builder
public class CliInvocation {
    String tool;
    List<String> args;
    String cwd;
    Map<String, String> env;
    @Builder.Default
    List<String> unsetEnv = List.of();
    String stdin;
    long timeoutMs;
    String capture;
    int maxOutputChars;

    /**
     * 显式全参构造用于防御性复制集合，保证 {@code @Value} 声明的不可变性对内容同样成立：
     * 调用方在构建后继续修改原始 List/Map 不会影响已发出的 CLI 调用。
     */
    public CliInvocation(String tool,
                         List<String> args,
                         String cwd,
                         Map<String, String> env,
                         List<String> unsetEnv,
                         String stdin,
                         long timeoutMs,
                         String capture,
                         int maxOutputChars) {
        this.tool = tool;
        this.args = args == null ? null : Collections.unmodifiableList(new ArrayList<>(args));
        this.cwd = cwd;
        this.env = env == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        this.unsetEnv = unsetEnv == null ? null : Collections.unmodifiableList(new ArrayList<>(unsetEnv));
        this.stdin = stdin;
        this.timeoutMs = timeoutMs;
        this.capture = capture;
        this.maxOutputChars = maxOutputChars;
    }
}
