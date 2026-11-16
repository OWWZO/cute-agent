package org.wwz.ai.test.domain;

import com.alibaba.fastjson.JSONObject;
import org.wwz.ai.application.agent.review.TaskReviewTraceSanitizer;

import java.util.List;

/**
 * 兼容旧评测 CLI 的测试入口，实际脱敏规则统一由生产复盘导出器维护。
 */
final class EvalTraceSanitizer {

    private EvalTraceSanitizer() {
    }

    static JSONObject sanitize(JSONObject trace) {
        return TaskReviewTraceSanitizer.sanitize(trace);
    }

    static List<String> scanSensitive(String value) {
        return TaskReviewTraceSanitizer.scanSensitive(value);
    }
}
