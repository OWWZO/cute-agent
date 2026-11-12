package org.wwz.ai.application.auth.result;

/**
 * 登录或刷新成功后返回的访问令牌结果。
 *
 * @param expiresIn 访问令牌剩余有效期，单位秒
 */
public record AuthTokenResult(String tokenType,
                              String accessToken,
                              long expiresIn,
                              AuthAccountResult user) {

    @Override
    public String toString() {
        return "AuthTokenResult[tokenType=" + tokenType
                + ", accessToken=<redacted>"
                + ", expiresIn=" + expiresIn
                + ", user=" + user + "]";
    }
}
