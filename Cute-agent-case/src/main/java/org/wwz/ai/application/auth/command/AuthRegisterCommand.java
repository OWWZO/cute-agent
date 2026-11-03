package org.wwz.ai.application.auth.command;

public record AuthRegisterCommand(String loginName, String password, String nickname) {

    @Override
    public String toString() {
        return "AuthRegisterCommand[loginName=" + loginName
                + ", password=<redacted>"
                + ", nickname=" + nickname + "]";
    }
}
