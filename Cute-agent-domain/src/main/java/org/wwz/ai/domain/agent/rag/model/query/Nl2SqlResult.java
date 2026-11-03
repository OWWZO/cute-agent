package org.wwz.ai.domain.agent.rag.model.query;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generated SQL alternatives and progress information returned by NL2SQL. */
@Data
public class Nl2SqlResult {

    private static final Logger log = LoggerFactory.getLogger(Nl2SqlResult.class);
    private Integer code;
    @JSONField(name = "request_id")
    private String requestId;
    @JSONField(name = "nl2sql_think")
    private String nl2sqlThink;
    private String status;
    private List<GeneratedQuery> data;
    @JSONField(name = "err_msg")
    private String errorMessage;
    private String rootQuery;
    private String erp;
    private String overwriteError;
    /** 上游耗时明细，键名由 NL2SQL 服务约定，值统一归一为毫秒数。 */
    @JSONField(name = "cost_time")
    private Map<String, Long> costTime;

    /**
     * 反序列化入口：把上游 cost_time 显式转成 {@code Map<String, Long>}。
     * 单个脏字段只记 warn 并按 0 处理，不能让整条 NL2SQL 结果解析失败。
     */
    @JSONField(name = "cost_time")
    public void setCostTime(Map<String, Object> rawCostTime) {
        if (rawCostTime == null) {
            this.costTime = null;
            return;
        }
        Map<String, Long> normalized = new LinkedHashMap<>(rawCostTime.size());
        for (Map.Entry<String, Object> entry : rawCostTime.entrySet()) {
            if (entry.getKey() == null) {
                log.warn("nl2sql cost_time 含空键，已忽略");
                continue;
            }
            normalized.put(entry.getKey(), toMillis(entry.getKey(), entry.getValue()));
        }
        this.costTime = normalized;
    }

    private static long toMillis(String key, Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            log.warn("nl2sql cost_time[{}] 不是合法的毫秒数：{}，按 0 处理", key, value);
            return 0L;
        }
    }

    @Data
    public static class GeneratedQuery {
        private String query;
        private String nl2sql;
    }
}
