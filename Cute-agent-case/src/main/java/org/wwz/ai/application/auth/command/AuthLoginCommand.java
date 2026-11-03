package org.wwz.ai.application.auth.command;

/**
 * 登录命令。
 * <p>身份字段的当前优先级为：{@code loginName != null} 时只使用 {@code loginName}，
 * 否则才回退到 {@code account}。空字符串或空白字符串也属于非 null，因此会覆盖
 * {@code account}，随后按无效登录处理，而不会再次回退。</p>
 *
 * @param loginName 首选登录名；可空，非 null 时优先于 {@code account}
 * @param account   兼容旧调用方的备用登录名；仅当 {@code loginName == null} 时使用
 * @param password  登录密码
 */
public record AuthLoginCommand(String loginName, String account, String password) {

    @Override
    public String toString() {
        return "AuthLoginCommand[loginName=" + loginName
                + ", account=" + account
                + ", password=<redacted>]";
    }
}
