import api from "./index";

export type BrowserRelayStatus = {
  connected: boolean;
  tabUrl?: string;
  tabTitle?: string;
};

/**
 * 浏览器扩展配对信息。
 *
 * token、code 和 bskPairingUrl 均包含配对凭据，禁止记录日志或持久化。
 * expiresAt 是 Unix 毫秒时间戳；使用前应与 Date.now() 比较以拦截过期配对。
 */
export type BrowserPairing = {
  /** 传给浏览器扩展的配对令牌，属于敏感凭据。 */
  token: string;
  /** 供用户输入或写入配对链接的短码，属于敏感凭据。 */
  code: string;
  relayUrl: string;
  /** 配对过期时间，Unix 毫秒时间戳。 */
  expiresAt: number;
  /** bsk 扩展的配对链接（url#token）；包含敏感凭据，网关未启用时为空。 */
  bskPairingUrl?: string | null;
};

const BROWSER_RELAY_BASE = "/api/agent/browser";

export const browserRelayApi = {
  status: () => api.get<BrowserRelayStatus>(`${BROWSER_RELAY_BASE}/status`) as unknown as Promise<BrowserRelayStatus>,
  pairing: () => api.post<BrowserPairing>(`${BROWSER_RELAY_BASE}/pairing`) as unknown as Promise<BrowserPairing>,
  disconnect: () => api.post<void>(`${BROWSER_RELAY_BASE}/disconnect`) as unknown as Promise<void>,
};
