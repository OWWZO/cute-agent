package org.wwz.ai.infrastructure;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class AutoServiceDependencyScopeTest {

    @Test
    public void autoServiceIsCompileOnly() throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(Paths.get("pom.xml").toFile());
        NodeList dependencies = document.getElementsByTagName("dependency");
        Element autoService = null;
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            if ("auto-service".equals(childText(dependency, "artifactId"))) {
                autoService = dependency;
                break;
            }
        }

        assertNotNull("pom.xml 中应声明 auto-service", autoService);
        assertEquals("auto-service 只应在编译期生效", "provided", childText(autoService, "scope"));
    }

    private static String childText(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element.getTextContent().trim();
            }
        }
        return "";
    }
}
