package org.wwz.ai.application.auth.command;

public record AuthChangePasswordCommand(String userId,
                                        String currentSessionId,
                                        String oldPassword,
                                        String newPassword) {

    @Override
    public String toString() {
        return "AuthChangePasswordCommand[userId=" + userId
                + ", currentSessionId=" + currentSessionId
                + ", oldPassword=<redacted>"
                + ", newPassword=<redacted>]";
    }
}
