import { describe, expect, it } from "vitest";
import {
  canReviewTurn,
  findReviewTurn,
  groupReviewTurns,
  mergeReviewRuns,
  sameRequestIds,
} from "./runSelection";
import type { ConversationRunSummary } from "@/services/agentConversation";

function run(
  requestId: string,
  status: string,
  queryPreview = "",
  values: Partial<ConversationRunSummary> = {}
): ConversationRunSummary {
  return {
    requestId,
    status,
    queryPreview,
    hasReplay: true,
    ...values,
  };
}

describe("task review run selection", () => {
  it("groups continuation runs into the same user request", () => {
    const turns = groupReviewTurns([
      run("req-1", "SUCCESS", "第一个请求", { toolCallCount: 2 }),
      run("resume_req-1", "SUCCESS", "", { toolCallCount: 1 }),
      run("req-2", "FAILED", "第二个请求", { toolCallCount: 4 }),
      run("resume_req-2a", "SUCCESS"),
      run("resume_req-2b", "TIMEOUT"),
    ]);

    expect(turns).toHaveLength(2);
    expect(turns[0].requestIds).toEqual(["req-1", "resume_req-1"]);
    expect(turns[0].queryPreview).toBe("第一个请求");
    expect(turns[0].toolCallCount).toBe(3);
    expect(turns[1].requestIds).toEqual([
      "req-2",
      "resume_req-2a",
      "resume_req-2b",
    ]);
    expect(turns[1].toolCallCount).toBe(4);
  });

  it("finds a turn by any of its run ids", () => {
    const turns = groupReviewTurns([
      run("req-1", "SUCCESS", "第一个请求"),
      run("resume_req-1", "SUCCESS"),
    ]);

    expect(findReviewTurn(turns, ["resume_req-1"])).toBe(turns[0]);
    expect(sameRequestIds(turns[0].requestIds, ["resume_req-1", "req-1"])).toBe(
      true
    );
  });

  it("blocks a turn while any run is still active", () => {
    const [turn] = groupReviewTurns([
      run("req-1", "SUCCESS", "任务"),
      run("resume_req-1", "WAITING_INPUT"),
    ]);
    expect(canReviewTurn(turn)).toBe(false);
  });

  it("merges paginated runs without duplicates", () => {
    expect(
      mergeReviewRuns(
        [run("req-1", "SUCCESS"), run("req-2", "SUCCESS")],
        [run("req-2", "FAILED"), run("req-3", "SUCCESS")]
      ).map((item) => [item.requestId, item.status])
    ).toEqual([
      ["req-1", "SUCCESS"],
      ["req-2", "FAILED"],
      ["req-3", "SUCCESS"],
    ]);
  });
});
