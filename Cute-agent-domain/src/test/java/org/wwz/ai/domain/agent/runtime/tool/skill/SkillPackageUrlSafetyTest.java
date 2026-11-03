package org.wwz.ai.domain.agent.runtime.tool.skill;

import org.junit.Assert;
import org.junit.Test;

/**
 * 在线导入必须在发起网络请求前拒绝内网 / 非 http(s) 地址。
 */
public class SkillPackageUrlSafetyTest {

    private final SkillPackageService service = new SkillPackageService(null, null);

    @Test
    public void rejectsLoopbackUrlBeforeNetwork() {
        assertRejected("http://127.0.0.1:1/skill.zip");
        assertRejected("http://localhost:8080/skill.zip");
        assertRejected("http://[::1]/skill.zip");
    }

    @Test
    public void rejectsMetadataAndPrivateAddresses() {
        assertRejected("http://169.254.169.254/latest/meta-data");
        assertRejected("http://10.0.0.5/skill.zip");
        assertRejected("http://0177.0.0.1/skill.zip");
        assertRejected("http://0x7f.0.0.1/skill.zip");
    }

    @Test
    public void rejectsNonHttpSchemes() {
        assertRejected("file:///etc/passwd");
        assertRejected("ftp://8.8.8.8/skill.zip");
    }

    private void assertRejected(String url) {
        try {
            service.installFromUrl(url, false);
            Assert.fail("should reject: " + url);
        } catch (SkillLoadException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
    }
}
