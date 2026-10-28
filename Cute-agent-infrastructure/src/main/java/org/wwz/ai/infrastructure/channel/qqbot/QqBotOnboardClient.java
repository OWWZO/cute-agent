package org.wwz.ai.infrastructure.channel.qqbot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 扫码绑定协议客户端。
 * <p>两个端点：{@code create_bind_task} 拿 task_id，{@code poll_bind_result} 轮询扫码结果。
 * 轮询循环由 case 层驱动，这里只做单次调用。</p>
 */
@Slf4j
public class QqBotOnboardClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    public QqBotOnboardClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 创建绑定任务。
     *
     * @param bindKey base64 编码的 256 位 AES key
     * @return task_id
     */
    public String createBindTask(String bindKey) {
        JSONObject body = new JSONObject();
        body.put("key", bindKey);
        JSONObject data = post(QqBotConstants.ONBOARD_CREATE_PATH, body);
        String taskId = data == null ? null : data.getString("task_id");
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("create_bind_task 响应缺少 task_id");
        }
        return taskId;
    }

    /** 轮询一次扫码结果。 */
    public PollResult pollBindResult(String taskId) {
        JSONObject body = new JSONObject();
        body.put("task_id", taskId);
        JSONObject data = post(QqBotConstants.ONBOARD_POLL_PATH, body);
        if (data == null) {
            return new PollResult(BindStatus.NONE, null, null, null);
        }
        BindStatus status = BindStatus.fromCode(data.getIntValue("status"));
        return new PollResult(
                status,
                data.getString("bot_appid"),
                data.getString("bot_encrypt_secret"),
                data.getString("user_openid"));
    }

    public String buildQrUrl(String taskId) {
        return QqBotConstants.QR_URL_TEMPLATE.replace("{taskId}", taskId);
    }

    private JSONObject post(String path, JSONObject body) {
        Request request = new Request.Builder()
                .url("https://" + QqBotConstants.PORTAL_HOST + path)
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("扫码接口失败 HTTP " + response.code());
            }
            JSONObject json = JSON.parseObject(text);
            if (json == null) {
                throw new IllegalStateException("扫码接口返回非 JSON");
            }
            int retcode = json.getIntValue("retcode");
            if (retcode != 0) {
                throw new IllegalStateException("扫码接口返回 retcode=" + retcode
                        + " msg=" + json.getString("msg"));
            }
            return json.getJSONObject("data");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("扫码接口网络异常", e);
        }
    }

    /** {@code poll_bind_result} 的状态码。 */
    public enum BindStatus {
        NONE(0),
        PENDING(1),
        COMPLETED(2),
        EXPIRED(3);

        private final int code;

        BindStatus(int code) {
            this.code = code;
        }

        static BindStatus fromCode(int code) {
            for (BindStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
            return NONE;
        }
    }

    /**
     * @param botAppId        扫码成功后返回的 AppID
     * @param encryptedSecret 用 bindKey 加密过的 AppSecret，需本地解密
     * @param userOpenid      扫码者 openid
     */
    public record PollResult(BindStatus status,
                             String botAppId,
                             String encryptedSecret,
                             String userOpenid) {
    }
}
