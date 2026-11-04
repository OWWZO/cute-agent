package org.wwz.ai.application.auth;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.lang.Nullable;

public class IAuthApplicationServiceContractTest {

    @Test
    public void verifyAccessTokenDeclaresNullableReturn() throws Exception {
        var method = IAuthApplicationService.class.getMethod("verifyAccessToken", String.class);

        Assert.assertTrue(method.isAnnotationPresent(Nullable.class));
    }
}
