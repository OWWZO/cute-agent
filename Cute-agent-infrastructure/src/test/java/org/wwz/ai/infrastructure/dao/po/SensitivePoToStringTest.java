package org.wwz.ai.infrastructure.dao.po;

import org.junit.Test;
import org.wwz.ai.infrastructure.dao.cute.po.BackgroundTaskPO;
import org.wwz.ai.infrastructure.dao.cute.po.PlanApprovalPO;
import org.wwz.ai.infrastructure.dao.cute.po.UserQuestionPO;
import org.wwz.ai.infrastructure.dao.po.catalog.AiClientApiPO;
import org.wwz.ai.infrastructure.dao.po.catalog.AiClientToolMcpPO;
import org.wwz.ai.infrastructure.dataquery.elasticsearch.ElasticsearchProperties;
import org.wwz.ai.infrastructure.dataquery.jdbc.JdbcConnectionConfig;
import org.wwz.ai.infrastructure.dataquery.jdbc.JdbcDataSourceProperties;
import org.wwz.ai.infrastructure.dataquery.jdbc.connection.ConnectionWrapper;
import org.wwz.ai.infrastructure.dataquery.vector.QdrantProperties;

import java.util.Properties;

import static org.junit.Assert.assertFalse;

public class SensitivePoToStringTest {

    @Test
    public void accountAndCredentialPersistenceObjectsHideSecrets() {
        UserAccountPO account = UserAccountPO.builder()
                .tokenDigest("token-digest-sensitive")
                .lastIp("203.0.113.9")
                .lastUserAgent("secret-agent")
                .passwordHash("password-hash-sensitive")
                .build();
        assertHidden(account.toString(),
                "token-digest-sensitive", "203.0.113.9",
                "secret-agent", "password-hash-sensitive");

        AiClientApi api = AiClientApi.builder().apiKey("api-key-sensitive").build();
        assertHidden(api.toString(), "api-key-sensitive");

        AiClientApiPO catalogApi = AiClientApiPO.builder().apiKey("catalog-key-sensitive").build();
        assertHidden(catalogApi.toString(), "catalog-key-sensitive");
    }

    @Test
    public void runtimePayloadPersistenceObjectsHideRawText() {
        BackgroundTaskPO task = new BackgroundTaskPO();
        task.setCommand("command-sensitive");
        task.setPrompt("prompt-sensitive");
        task.setOutput("output-sensitive");
        task.setErrorMsg("error-sensitive");
        assertHidden(task.toString(), "command-sensitive", "prompt-sensitive",
                "output-sensitive", "error-sensitive");

        PlanApprovalPO approval = new PlanApprovalPO();
        approval.setPlanContent("plan-sensitive");
        approval.setDecisionJson("decision-sensitive");
        approval.setResumeContextJson("resume-sensitive");
        assertHidden(approval.toString(), "plan-sensitive", "decision-sensitive", "resume-sensitive");

        UserQuestionPO question = new UserQuestionPO();
        question.setQuestionsJson("questions-sensitive");
        question.setAnswersJson("answers-sensitive");
        question.setResumeContextJson("question-resume-sensitive");
        assertHidden(question.toString(), "questions-sensitive", "answers-sensitive",
                "question-resume-sensitive");

        SubAgentDefinitionPO subAgent = SubAgentDefinitionPO.builder()
                .systemPrompt("system-prompt-sensitive")
                .allowedToolsJson("allowed-sensitive")
                .disallowedToolsJson("disallowed-sensitive")
                .deferredToolsJson("deferred-sensitive")
                .build();
        assertHidden(subAgent.toString(), "system-prompt-sensitive", "allowed-sensitive",
                "disallowed-sensitive", "deferred-sensitive");
    }

    @Test
    public void toolOutputPersistenceObjectsHideLargePayloads() {
        ToolOutputDataAnalysisPO analysis = new ToolOutputDataAnalysisPO();
        analysis.setSummary("analysis-summary-sensitive");
        analysis.setContent("analysis-content-sensitive");
        assertHidden(analysis.toString(), "analysis-summary-sensitive", "analysis-content-sensitive");

        ToolOutputImageGenerationPO image = new ToolOutputImageGenerationPO();
        image.setPrompt("image-prompt-sensitive");
        image.setSummary("image-summary-sensitive");
        assertHidden(image.toString(), "image-prompt-sensitive", "image-summary-sensitive");
    }

    @Test
    public void transportAndConnectionPropertiesHideCredentials() {
        AiClientToolMcpPO mcp = AiClientToolMcpPO.builder()
                .transportConfig("{\"Authorization\":\"Bearer mcp-secret\"}")
                .build();
        assertHidden(mcp.toString(), "mcp-secret");

        ElasticsearchProperties elasticsearch = new ElasticsearchProperties();
        elasticsearch.setPassword("es-password-sensitive");
        elasticsearch.setApiKey("es-api-key-sensitive");
        assertHidden(elasticsearch.toString(), "es-password-sensitive", "es-api-key-sensitive");

        QdrantProperties qdrant = new QdrantProperties();
        qdrant.setApiKey("qdrant-api-key-sensitive");
        assertHidden(qdrant.toString(), "qdrant-api-key-sensitive");

        JdbcDataSourceProperties dataSource = new JdbcDataSourceProperties();
        dataSource.setPassword("jdbc-property-password-sensitive");
        assertHidden(dataSource.toString(), "jdbc-property-password-sensitive");

        Properties extConfig = new Properties();
        extConfig.setProperty("password", "jdbc-ext-password-sensitive");
        JdbcConnectionConfig connection = new JdbcConnectionConfig();
        connection.setPassword("jdbc-password-sensitive");
        connection.setExtConfig(extConfig);
        assertHidden(connection.toString(), "jdbc-password-sensitive",
                "jdbc-ext-password-sensitive");

        ConnectionWrapper wrapper = new ConnectionWrapper();
        wrapper.setJdbcConnectionConfig(connection);
        assertHidden(wrapper.toString(), "jdbc-password-sensitive", "jdbc-ext-password-sensitive");
    }

    private static void assertHidden(String text, String... sensitiveValues) {
        for (String sensitiveValue : sensitiveValues) {
            assertFalse("toString 泄露敏感值: " + sensitiveValue, text.contains(sensitiveValue));
        }
    }
}
