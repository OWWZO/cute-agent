import { FC, useMemo } from "react";
import AttachmentList from "@/components/AttachmentList";
import { Message, MessageContent } from "@/components/ai-elements/message";
import { OpenUiMarkdown } from "@/components/openui/OpenUiMarkdown";
import { collectChatArtifactFiles } from "@/utils/markdownArtifacts";
import { MessageToolbar } from "./MessageToolbar";
import {
  pickFeaturedDeliveryFiles,
  resolveTaskSummaryText,
  shouldShowWorkspaceFilesEntry,
} from "./contentHelpers";

export const ConclusionSection: FC<{
  chat: CHAT.ChatItem;
  changeFile?: CHAT.OpenFileHandler;
  sessionArtifactFiles?: CHAT.TFile[];
  onOpenWorkspaceFiles?: () => void;
  onReview?: () => void;
}> = ({ chat, changeFile, sessionArtifactFiles, onOpenWorkspaceFiles, onReview }) => {
  const summaryText = resolveTaskSummaryText(chat.conclusion);
  const summary = summaryText || "任务已完成";
  const summaryStreaming =
    !!chat.loading && chat.conclusion?.messageType === "agent_stream";
  const attachmentFiles = useMemo(
    () =>
      pickFeaturedDeliveryFiles(
        chat.conclusion,
        sessionArtifactFiles
      ),
    [chat.conclusion, sessionArtifactFiles]
  );
  const showWorkspaceFilesEntry = useMemo(
    () =>
      Boolean(onOpenWorkspaceFiles) &&
      shouldShowWorkspaceFilesEntry(
        attachmentFiles,
        chat.conclusion,
        sessionArtifactFiles
      ),
    [attachmentFiles, chat.conclusion, onOpenWorkspaceFiles, sessionArtifactFiles]
  );
  const artifactFiles = useMemo(() => {
    const localFiles = collectChatArtifactFiles(chat);
    if (!sessionArtifactFiles?.length) {
      return localFiles;
    }

    const files = new Map<string, CHAT.TFile>();
    for (const file of sessionArtifactFiles) {
      files.set(file.resourceKey || file.url || file.downloadUrl || file.name, file);
    }
    for (const file of localFiles) {
      files.set(file.resourceKey || file.url || file.downloadUrl || file.name, file);
    }
    return [...files.values()];
  }, [chat, sessionArtifactFiles]);

  return (
    <div className="mt-5">
      <Message from="assistant" className="min-w-0 w-full">
        <MessageContent>
          <OpenUiMarkdown
            content={summary}
            streaming={summaryStreaming}
            artifactFiles={artifactFiles}
            className="chat-markdown conclusion-markdown kimi-md"
            sessionId={chat.sessionId}
            messageId={chat.requestId}
          />
        </MessageContent>
        {!summaryStreaming && (summaryText || onReview) ? (
          <MessageToolbar
            response={summaryText}
            alwaysVisible
            onReview={onReview}
          />
        ) : null}
      </Message>
      {/* 终答仍在流式输出时先不露出工作区入口：$$$ 点名边流边解析，
          入口会随解析结果反复显隐。 */}
      <AttachmentList
        files={attachmentFiles}
        preview={true}
        review={(file) => changeFile?.(file, chat)}
        showWorkspaceFilesEntry={showWorkspaceFilesEntry && !summaryStreaming}
        onOpenWorkspaceFiles={onOpenWorkspaceFiles}
      />
    </div>
  );
};

ConclusionSection.displayName = "ConclusionSection";

export default ConclusionSection;
