package org.wwz.ai.application.auth;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public class JwtSecretProviderTest {

    @Test
    public void newSecretFileIsOwnerOnly() throws Exception {
        Path directory = Files.createTempDirectory("jwt-secret");
        Path secretFile = directory.resolve("auth.jwt.secret");
        JwtSecretProvider provider = new JwtSecretProvider("", secretFile);

        String secret = provider.resolve();

        Assert.assertTrue(secret.length() >= 40);
        Assert.assertTrue(Files.isRegularFile(secretFile));
        if (Files.getFileStore(secretFile).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> expected = PosixFilePermissions.fromString("rw-------");
            Assert.assertEquals(expected, Files.getPosixFilePermissions(secretFile));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(secretFile, AclFileAttributeView.class);
        Assume.assumeTrue("当前文件系统不支持 ACL 权限断言", acl != null);
        Assert.assertFalse(acl.getAcl().isEmpty());
        String owner = acl.getOwner().getName();
        for (AclEntry entry : acl.getAcl()) {
            Assert.assertEquals(owner, entry.principal().getName());
        }
    }
}
