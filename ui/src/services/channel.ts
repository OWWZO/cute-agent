import api from "./index";

/**
 * QQ 机器人绑定 API。
 *
 * 身份完全由后端从 JWT 解析，前端不传 userId。
 * 响应体永不包含 appSecret。
 */

export type ChannelBindingStatus = "PENDING" | "ACTIVE" | "ERROR" | "DISABLED";

export interface ChannelBindingView {
  bindingId: number;
  appId: string;
  botOpenid?: string | null;
  status: ChannelBindingStatus;
  lastError?: string | null;
  connectedAt?: string | null;
}

export interface ChannelBindingMineResp {
  binding: ChannelBindingView | null;
  globalBindingCount: number;
  maxBindings: number;
}

export interface QrCreateResp {
  taskId: string;
  qrUrl: string;
  expiresInSeconds: number;
}

export interface QrPollResp {
  status: "PENDING" | "COMPLETED" | "EXPIRED";
  bindingId?: number | null;
  error?: string | null;
}

export interface UnbindResp {
  unbound: boolean;
  notice: string;
}

export const channelApi = {
  mine: () =>
    api.get<ChannelBindingMineResp>(
      "/api/channel/binding/mine"
    ) as unknown as Promise<ChannelBindingMineResp>,

  createQr: () =>
    api.post<QrCreateResp>(
      "/api/channel/binding/qr"
    ) as unknown as Promise<QrCreateResp>,

  pollQr: (taskId: string) =>
    api.get<QrPollResp>(
      `/api/channel/binding/qr/${encodeURIComponent(taskId)}`
    ) as unknown as Promise<QrPollResp>,

  retry: (bindingId: number) =>
    api.post<boolean>(
      `/api/channel/binding/${bindingId}/retry`
    ) as unknown as Promise<boolean>,

  unbind: (bindingId: number) =>
    api.delete<UnbindResp>(
      `/api/channel/binding/${bindingId}`
    ) as unknown as Promise<UnbindResp>,
};
