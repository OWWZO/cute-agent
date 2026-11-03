package org.wwz.ai.test.domain.image;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.wwz.ai.domain.agent.image.model.ImageGenerationExecutionResult;
import org.wwz.ai.domain.agent.image.model.WorkspaceImageFile;
import org.wwz.ai.domain.agent.image.service.impl.ImageGenerationBatchPersistenceServiceImpl;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.model.ArtifactRecordCommand;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ImageGenerationToolOutput;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolOutputPersistCommand;
import org.wwz.ai.domain.agent.ledger.tooloutput.ToolOutputWriter;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 工作台生图批次持久化：fileRefs 与 artifact 必须覆盖同一批有效文件，且全无效时快速失败。
 */
public class ImageGenerationBatchPersistenceServiceImplTest {

    @Mock
    private ToolOutputWriter toolOutputWriter;
    @Mock
    private AgentExecutionRecorder agentExecutionRecorder;
    @InjectMocks
    private ImageGenerationBatchPersistenceServiceImpl service;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void blankNamedFileIsExcludedFromBothFileRefsAndArtifacts() {
        ImageGenerationExecutionResult result = ImageGenerationExecutionResult.builder()
                .prompt("画一只猫")
                .files(Arrays.asList(
                        WorkspaceImageFile.builder().fileName("cat.png").downloadUrl("https://f/cat.png").build(),
                        null,
                        WorkspaceImageFile.builder().fileName("  ").downloadUrl("https://f/blank.png").build()))
                .build();

        service.persistWorkspaceBatch("req-1", result);

        ArgumentCaptor<ToolOutputPersistCommand> commandCaptor =
                ArgumentCaptor.forClass(ToolOutputPersistCommand.class);
        verify(toolOutputWriter).writeOrThrow(commandCaptor.capture());
        ImageGenerationToolOutput output =
                (ImageGenerationToolOutput) commandCaptor.getValue().getStructuredOutput();
        Assert.assertEquals(1, output.getFileRefs().size());
        Assert.assertEquals("cat.png", output.getFileRefs().get(0).getFileName());

        ArgumentCaptor<List<ArtifactRecordCommand>> artifactCaptor = ArgumentCaptor.forClass(List.class);
        verify(agentExecutionRecorder).recordArtifactsOrThrow(artifactCaptor.capture());
        Assert.assertEquals(1, artifactCaptor.getValue().size());
        Assert.assertEquals("cat.png", artifactCaptor.getValue().get(0).getFileName());
    }

    @Test
    public void batchWithoutAnyValidFileNameFailsInsteadOfPersistingEmptySuccess() {
        ImageGenerationExecutionResult result = ImageGenerationExecutionResult.builder()
                .prompt("画一只猫")
                .files(Arrays.asList(null, WorkspaceImageFile.builder().fileName(" ").build()))
                .build();

        try {
            service.persistWorkspaceBatch("req-2", result);
            Assert.fail("没有有效文件名时必须失败");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("有效文件名"));
        }

        verify(toolOutputWriter, never()).writeOrThrow(any());
        verify(agentExecutionRecorder, never()).recordArtifactsOrThrow(any());
    }
}
