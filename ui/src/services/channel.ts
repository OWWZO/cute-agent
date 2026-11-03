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

const CHANNEL_BINDING_BASE = "/api/channel/binding";

export const channelApi = {
  mine: () =>
    api.get<ChannelBindingMineResp>(
      `${CHANNEL_BINDING_BASE}/mine`
    ) as unknown as Promise<ChannelBindingMineResp>,

  createQr: () =>
    api.post<QrCreateResp>(
      `${CHANNEL_BINDING_BASE}/qr`
    ) as unknown as Promise<QrCreateResp>,

  pollQr: (taskId: string) =>
    api.get<QrPollResp>(
      `${CHANNEL_BINDING_BASE}/qr/${encodeURIComponent(taskId)}`
    ) as unknown as Promise<QrPollResp>,

  retry: (bindingId: number) =>
    api.post<boolean>(
      `${CHANNEL_BINDING_BASE}/${bindingId}/retry`
    ) as unknown as Promise<boolean>,

  unbind: (bindingId: number) =>
    api.delete<UnbindResp>(
      `${CHANNEL_BINDING_BASE}/${bindingId}`
    ) as unknown as Promise<UnbindResp>,
};
