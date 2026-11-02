/**
 * run 状态归一化：账本 / 实时帧给出的 status 是裸字符串，这里收敛到
 * {@link CHAT.RunStatus}，避免调用方各自比较字面量导致漏判。
 */
const RUN_STATUS_BY_NAME: Record<string, CHAT.RunStatus> = {
  RUNNING: "RUNNING",
  SUCCESS: "SUCCESS",
  FAILED: "FAILED",
  STOPPED: "STOPPED",
  TIMEOUT: "TIMEOUT",
  WAITING_INPUT: "WAITING_INPUT",
  UNKNOWN: "UNKNOWN",
};

/**
 * 空值沿用历史语义视为 RUNNING（账本缺 status 即视作仍在跑）；
 * 未枚举的取值统一归入 UNKNOWN，保持「非运行、非等待、非终态」的旧比较结果。
 */
export function normalizeRunStatus(status?: string | null): CHAT.RunStatus {
  const normalized = String(status || "").trim().toUpperCase();
  return RUN_STATUS_BY_NAME[normalized] ?? (normalized ? "UNKNOWN" : "RUNNING");
}
