import { describe, expect, it } from "vitest";

import {
  mergeWorkspaceFiles,
  readWorkspaceFileEvent,
} from "./workspaceFileEvents";

function fileEvent(workspaceOnly = false) {
  return {
    messageType: "file",
    messageId: "file-1",
    resultMap: {
      messageType: "file",
      workspaceOnly,
      rootSessionId: "root-1",
      resultMap: {
        messageType: "file",
        fileInfo: [
          {
            fileName: "data/result.csv",
            relativePath: "data/result.csv",
            ossUrl: "https://files.example/result.csv",
            domainUrl: "https://preview.example/result.csv",
            fileSize: 12,
          },
        ],
      },
    },
  } as unknown as MESSAGE.EventData;
}

describe("workspace file events", () => {
  it("reads main-agent file metadata", () => {
    const event = readWorkspaceFileEvent(fileEvent());

    expect(event?.workspaceOnly).toBe(false);
    expect(event?.rootSessionId).toBe("root-1");
    expect(event?.files).toHaveLength(1);
    expect(event?.files[0].relativePath).toBe("data/result.csv");
  });

  it("marks relayed child file events as workspace-only", () => {
    expect(readWorkspaceFileEvent(fileEvent(true))?.workspaceOnly).toBe(true);
  });

  it("upserts repeated writes by relative path", () => {
    const first = readWorkspaceFileEvent(fileEvent())!.files;
    const updated = {
      ...first[0],
      size: 24,
      downloadUrl: "https://files.example/result-v2.csv",
    };

    const merged = mergeWorkspaceFiles(first, [updated]);

    expect(merged).toHaveLength(1);
    expect(merged[0].size).toBe(24);
    expect(merged[0].downloadUrl).toBe(
      "https://files.example/result-v2.csv"
    );
  });
});
