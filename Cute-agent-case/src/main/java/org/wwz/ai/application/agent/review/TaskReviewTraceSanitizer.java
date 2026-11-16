package org.wwz.ai.application.agent.review;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单次任务复盘 Trace 脱敏器。
 *
 * <p>只处理导出副本，不修改 Execution Ledger。Artifact 不复制文件名、存储 key 或下载链接，
 * 只保留可关联的类型、大小、hash 和相对引用。</p>
 */
public final class TaskReviewTraceSanitizer {

    private static final String SANITIZER_VERSION = "1.0";
    private static final String SECRET_MARKER = "[REDACTED_SECRET]";

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|authorization|cookie|password|passwd|secret|"
                    + "access[_-]?token|refresh[_-]?token|id[_-]?token)\\s*[:=]\\s*)"
                    + "(?!\\[REDACTED_)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"
    );
    private static final Pattern API_KEY = Pattern.compile(
            "\\b(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16})\\b"
    );
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"
    );
    private static final Pattern CN_MOBILE = Pattern.compile(
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)"
    );
    private static final Pattern CN_ID_CARD = Pattern.compile(
            "(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])"
                    + "(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?!\\d)"
    );
    private static final Pattern INTERNAL_URL = Pattern.compile(
            "(?i)https?://(?:localhost|127\\.0\\.0\\.1|\\[::1\\]|"
                    + "10(?:\\.\\d{1,3}){3}|192\\.168(?:\\.\\d{1,3}){2}|"
                    + "172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2}|"
                    + "[A-Za-z0-9.-]+\\.(?:local|internal)|host\\.docker\\.internal)"
                    + "(?::\\d+)?(?:/[^\\s\"']*)?"
    );
    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
            "(?i)([?&](?:token|access_token|api_key|key|signature|x-amz-signature)=)"
                    + "(?!\\[REDACTED)[^&#\\s\"']+"
    );
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])[A-Z]:\\\\(?:[^\\\\\\s\"']+\\\\)*[^\\\\\\s\"']*"
    );
    private static final Pattern WINDOWS_FORWARD_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])[A-Z]:/(?:[^/\\s\"']+/)*[^/\\s\"']*"
    );
    private static final Pattern UNC_PATH = Pattern.compile(
            "\\\\\\\\[^\\\\\\s\"']+\\\\[^\\s\"']+"
    );
    private static final Pattern UNIX_PATH = Pattern.compile(
            "(?<![A-Za-z0-9])/(?:Users|home|var|tmp|opt|srv|mnt|root|private|workspace|d)/"
                    + "[^\\s\"'<>]+"
    );
    private static final Pattern CN_ADDRESS = Pattern.compile(
            "(?:北京市|上海市|天津市|重庆市|[\\u4e00-\\u9fa5]{2,8}省)"
                    + "[\\u4e00-\\u9fa5A-Za-z0-9\\-号栋室路街道巷弄]{4,60}"
    );

    private static final List<Pattern> SCAN_PATTERNS = List.of(
            SECRET_ASSIGNMENT,
            BEARER_TOKEN,
            JWT,
            API_KEY,
            EMAIL,
            CN_MOBILE,
            CN_ID_CARD,
            INTERNAL_URL,
            SENSITIVE_QUERY,
            WINDOWS_PATH,
            WINDOWS_FORWARD_PATH,
            UNC_PATH,
            UNIX_PATH,
            CN_ADDRESS
    );

    private TaskReviewTraceSanitizer() {
    }

    public static JSONObject sanitize(JSONObject trace) {
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
        JSONObject source = trace;
        Context context = new Context();
        JSONObject sanitized = sanitizeObject(source, context);

        JSONArray projectedArtifacts = new JSONArray();
        JSONArray artifacts = source.getJSONArray("artifacts");
        if (artifacts != null) {
            for (int i = 0; i < artifacts.size(); i++) {
                JSONObject artifact = artifacts.getJSONObject(i);
                projectedArtifacts.add(projectArtifact(artifact, i));
            }
        }
        sanitized.put("artifacts", projectedArtifacts);

        JSONObject report = new JSONObject();
        report.put("version", SANITIZER_VERSION);
        report.put("redactedValues", context.redactedValues);
        report.put("projectedArtifacts", projectedArtifacts.size());
        report.put("scannedAt", LocalDateTime.now().toString());
        sanitized.put("sanitization", report);

        Set<String> findings = new LinkedHashSet<>();
        collectSensitiveValues(sanitized, findings);
        if (!findings.isEmpty()) {
            throw new IllegalStateException(
                    "trace sanitizer left sensitive patterns: " + String.join(",", findings)
            );
        }
        return sanitized;
    }

    public static List<String> scanSensitive(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        List<String> findings = new ArrayList<>();
        String[] names = {
                "secret_assignment",
                "bearer_token",
                "jwt",
                "api_key",
                "email",
                "cn_mobile",
                "cn_id_card",
                "internal_url",
                "sensitive_query",
                "windows_path",
                "windows_forward_path",
                "unc_path",
                "unix_path",
                "cn_address"
        };
        for (int i = 0; i < SCAN_PATTERNS.size(); i++) {
            if (SCAN_PATTERNS.get(i).matcher(value).find()) {
                findings.add(names[i]);
            }
        }
        return findings;
    }

    private static void collectSensitiveValues(Object value, Set<String> findings) {
        if (value instanceof JSONObject object) {
            for (Object item : object.values()) {
                collectSensitiveValues(item, findings);
            }
            return;
        }
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                collectSensitiveValues(item, findings);
            }
            return;
        }
        if (value instanceof String text) {
            findings.addAll(scanSensitive(text));
        }
    }

    private static JSONObject sanitizeObject(JSONObject source, Context context) {
        JSONObject target = new JSONObject();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isSensitiveKey(key)) {
                target.put(key, redactValue(value, context));
            } else {
                target.put(key, sanitizeValue(value, context));
            }
        }
        return target;
    }

    private static Object sanitizeValue(Object value, Context context) {
        if (value instanceof JSONObject object) {
            return sanitizeObject(object, context);
        }
        if (value instanceof JSONArray array) {
            JSONArray result = new JSONArray();
            for (Object item : array) {
                result.add(sanitizeValue(item, context));
            }
            return result;
        }
        if (value instanceof String text) {
            return sanitizeText(text, context);
        }
        return value;
    }

    private static Object redactValue(Object value, Context context) {
        context.redactedValues++;
        return SECRET_MARKER;
    }

    private static String sanitizeText(String value, Context context) {
        String result = value;
        result = replace(result, SECRET_ASSIGNMENT, "$1" + SECRET_MARKER, context);
        result = replace(result, BEARER_TOKEN, "Bearer " + SECRET_MARKER, context);
        result = replace(result, JWT, "[REDACTED_JWT]", context);
        result = replace(result, API_KEY, "[REDACTED_API_KEY]", context);
        result = replace(result, EMAIL, "[REDACTED_EMAIL]", context);
        result = replace(result, CN_MOBILE, "[REDACTED_PHONE]", context);
        result = replace(result, CN_ID_CARD, "[REDACTED_ID_CARD]", context);
        result = replace(result, INTERNAL_URL, "[REDACTED_INTERNAL_URL]", context);
        result = replace(result, SENSITIVE_QUERY, "$1[REDACTED]", context);
        result = replace(result, WINDOWS_PATH, "[REDACTED_PATH]", context);
        result = replace(result, WINDOWS_FORWARD_PATH, "[REDACTED_PATH]", context);
        result = replace(result, UNC_PATH, "[REDACTED_PATH]", context);
        result = replace(result, UNIX_PATH, "[REDACTED_PATH]", context);
        result = replace(result, CN_ADDRESS, "[REDACTED_ADDRESS]", context);
        return result;
    }

    private static String replace(String value,
                                  Pattern pattern,
                                  String replacement,
                                  Context context) {
        Matcher matcher = pattern.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String groupOne = matcher.groupCount() > 0 && matcher.group(1) != null
                    ? matcher.group(1)
                    : "";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(
                    replacement.replace("$1", groupOne)
            ));
            context.redactedValues++;
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static JSONObject projectArtifact(JSONObject artifact, int index) {
        JSONObject projected = new JSONObject();
        projected.put("artifactId", artifact.get("id"));
        projected.put("toolInvocationId", artifact.get("toolInvocationId"));
        projected.put("toolCallId", artifact.get("toolCallId"));
        projected.put("artifactRole", artifact.get("artifactRole"));
        projected.put("visibility", artifact.get("visibility"));
        projected.put("sourceType", artifact.get("sourceType"));
        projected.put("mimeType", artifact.get("mimeType"));
        projected.put("fileSize", artifact.get("fileSize"));
        projected.put("fileHash", artifact.get("fileHash"));

        Object id = artifact.get("id");
        String ref = id == null ? "artifact-" + (index + 1) : "artifact-" + id;
        projected.put("relativeRef", "artifacts/" + ref + extension(artifact.getString("mimeType")));
        return projected;
    }

    private static String extension(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            case "application/json" -> ".json";
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            default -> "";
        };
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replace("_", "").toLowerCase(Locale.ROOT);
        return normalized.equals("password")
                || normalized.equals("passwd")
                || normalized.equals("secret")
                || normalized.equals("authorization")
                || normalized.equals("cookie")
                || normalized.equals("apikey")
                || normalized.equals("accesstoken")
                || normalized.equals("refreshtoken")
                || normalized.equals("idtoken")
                || normalized.equals("address")
                || normalized.equals("homeaddress")
                || normalized.equals("downloadurl")
                || normalized.equals("previewurl")
                || normalized.equals("storagekey")
                || normalized.equals("localpath")
                || normalized.equals("filepath");
    }

    private static final class Context {
        private int redactedValues;
    }
}
