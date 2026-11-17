import { beforeEach, describe, expect, it, vi } from "vitest";

const getMock = vi.hoisted(() => vi.fn());
const postMock = vi.hoisted(() => vi.fn());

vi.mock("./index", () => ({default: {get: getMock, post: postMock},}));

import {
  AgentSessionQuerySubmitError,
  conversationHistoryApi,
} from "./agentConversation";

describe("conversationHistoryApi", () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
    getMock.mockResolvedValue({});
    postMock.mockResolvedValue({});
  });

  it("requests a cursor page from the current session endpoint", async () => {
    await conversationHistoryApi.getSessionDetail("session-001", {
      limit: 20,
      after: "cursor-001",
    });

    expect(getMock).toHaveBeenCalledWith(
      "/api/agent/conversation/sessions/session-001",
      {
        limit: 20,
        after: "cursor-001",
      }
    );
  });

  it("requests recent sessions in pages of ten and forwards the cursor", async () => {
    await conversationHistoryApi.listSessions({
      limit: 10,
      after: "session-cursor-001",
    });

    expect(getMock).toHaveBeenCalledWith(
      "/api/agent/conversation/sessions",
      {
        limit: 10,
        after: "session-cursor-001",
      }
    );
  });

  it("defaults the recent session page size to ten", async () => {
    await conversationHistoryApi.listSessions();

    expect(getMock).toHaveBeenCalledWith(
      "/api/agent/conversation/sessions",
      { limit: 10 }
    );
  });

  it("requests one run replay by requestId", async () => {
    await conversationHistoryApi.getRunReplay("request-001");

    expect(getMock).toHaveBeenCalledWith(
      "/api/agent/conversation/runs/request-001/replay"
    );
  });

  it("requests the encoded session file manifest", async () => {
    const files = [
      {
        displayName: "report.md",
        relativePath: "reports/report.md",
        resourceKey: "workspace/report.md",
      },
    ];
    getMock.mockResolvedValueOnce(files);

    await expect(
      conversationHistoryApi.getSessionFiles("session/with spaces")
    ).resolves.toEqual(files);
    expect(getMock).toHaveBeenCalledWith(
      "/api/agent/conversation/sessions/session%2Fwith%20spaces/files"
    );
  });

  it("子会话 query 带新协议头和客户端 requestId", async () => {
    postMock.mockResolvedValueOnce({
      accepted: true,
      sessionId: "sub-1",
      requestId: "req-1",
      mode: "new_run",
    });

    await conversationHistoryApi.submitSessionQuery("sub-1", {
      query: "继续",
      requestId: "req-1",
    });

    expect(postMock).toHaveBeenCalledWith(
      "/api/agent/session/sub-1/query",
      { query: "继续", requestId: "req-1" },
      {
        headers: {
          "X-Agent-Ingress-Version": "1",
        },
      }
    );
  });

  it("子会话 409 保留 activeRequestId 供旧后端兜底", async () => {
    postMock.mockRejectedValueOnce({
      response: {
        status: 409,
        data: {
          info: "已有任务在进行中",
          data: {
            accepted: false,
            activeRequestId: "active-1",
            activeSessionId: "sub-1",
          },
        },
      },
    });

    await expect(
      conversationHistoryApi.submitSessionQuery("sub-1", {
        query: "继续",
        requestId: "req-1",
      })
    ).rejects.toMatchObject({
      name: "AgentSessionQuerySubmitError",
      status: 409,
      concurrent: true,
      activeRequestId: "active-1",
      activeSessionId: "sub-1",
    } satisfies Partial<AgentSessionQuerySubmitError>);
  });
});
