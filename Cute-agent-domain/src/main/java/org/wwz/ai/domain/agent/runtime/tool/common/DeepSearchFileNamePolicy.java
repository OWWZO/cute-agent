package org.wwz.ai.domain.agent.runtime.tool.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.util.StringUtil;

/**
 * 报告产物命名规则：文件名以 reportFileName 为基准，不从完整任务文本派生。
 */
@Slf4j
final class DeepSearchFileNamePolicy {

    static final int MAX_REPORT_FILE_NAME_LENGTH = 20;
    private static final String DEFAULT_REPORT_FILE_NAME = "深度搜索报告.md";

    private DeepSearchFileNamePolicy() {
    }

    static String buildSearchResultFileName(String reportFileName) {
        // 与报告名走同一套清洗：null/空白回退默认名，路径分隔符统一替换为 _。
        String normalized = sanitizeReportFileName(reportFileName);
        if (StringUtils.isBlank(normalized)) {
            normalized = sanitizeReportFileName(DEFAULT_REPORT_FILE_NAME);
        }
        int extensionStart = normalized.lastIndexOf('.');
        String baseName = extensionStart > 0 ? normalized.substring(0, extensionStart) : normalized;
        return baseName + "_search_result.txt";
    }

    static String resolveReportFileName(String requestedFileName) {
        return resolveReportFileName(requestedFileName, DEFAULT_REPORT_FILE_NAME);
    }

    static String resolveReportFileName(String requestedFileName, String fallbackFileName) {
        String fileName = sanitizeReportFileName(requestedFileName);
        String invalidReason = validateReportFileName(requestedFileName);
        if (invalidReason == null) {
            return fileName;
        }
        log.warn("报告文件名不合法，使用回退名: requested={}, reason={}", requestedFileName, invalidReason);
        String fallback = sanitizeReportFileName(fallbackFileName);
        return validateReportFileName(fallback) == null ? fallback : DEFAULT_REPORT_FILE_NAME;
    }

    static String validateReportFileName(String requestedFileName) {
        String fileName = sanitizeReportFileName(requestedFileName);
        if (StringUtils.isBlank(fileName)) {
            return "reportFileName不能为空。";
        }
        if (fileName.codePointCount(0, fileName.length()) > MAX_REPORT_FILE_NAME_LENGTH) {
            return "reportFileName不能超过20个字符（含扩展名）。";
        }
        return null;
    }

    private static String sanitizeReportFileName(String requestedFileName) {
        String fileName = StringUtils.trimToEmpty(requestedFileName)
                .replace('/', '_')
                .replace('\\', '_');
        fileName = StringUtil.removeSpecialChars(fileName);
        if (StringUtils.isBlank(fileName)) {
            return "";
        }
        return fileName.toLowerCase().endsWith(".md") ? fileName : fileName + ".md";
    }
}
