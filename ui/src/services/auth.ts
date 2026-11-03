import api from "./index";
import type { AxiosRequestConfig } from "axios";
import { refreshAccessToken, type AuthSessionResponse } from "./authTransport";
import {
  clearAuthSession,
  getAuthState,
  setAuthSession,
  setAuthUser,
  setAuthInitializing,
  type AuthUser,
} from "@/stores/auth";

export interface LoginPayload {
  loginName: string;
  password: string;
}

export interface RegisterPayload extends LoginPayload {
  nickname?: string;
}

export interface ChangePasswordPayload {
  oldPassword: string;
  newPassword: string;
}

const AUTH_BASE = "/api/auth";
const AUTH_ENDPOINTS = {
  register: `${AUTH_BASE}/register`,
  login: `${AUTH_BASE}/login`,
  logout: `${AUTH_BASE}/logout`,
  logoutAll: `${AUTH_BASE}/logout-all`,
  me: `${AUTH_BASE}/me`,
  password: `${AUTH_BASE}/password`,
} as const;

const post = <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
  api.post<T>(url, data, config) as unknown as Promise<T>;

const endSession = async (url: string) => {
  try {
    await post<void>(url);
  } finally {
    clearAuthSession();
  }
};

export const authApi = {
  register: (payload: RegisterPayload) =>
    post<AuthSessionResponse>(AUTH_ENDPOINTS.register, payload),

  login: async (payload: LoginPayload) => {
    const session = await post<AuthSessionResponse>(AUTH_ENDPOINTS.login, payload);
    if (!session?.accessToken) {
      throw new Error("登录响应缺少访问令牌");
    }
    setAuthSession(session.accessToken, session.user ?? null);
    return session;
  },

  refresh: async () => {
    const accessToken = await refreshAccessToken();
    return {
      accessToken,
      user: getAuthState().user,
    };
  },

  logout: () => endSession(AUTH_ENDPOINTS.logout),

  logoutAll: () => endSession(AUTH_ENDPOINTS.logoutAll),

  me: () =>
    api.get<AuthUser>(AUTH_ENDPOINTS.me) as unknown as Promise<AuthUser>,

  password: (payload: ChangePasswordPayload) =>
    api.put<void>(AUTH_ENDPOINTS.password, payload) as unknown as Promise<void>,
};

export async function loadCurrentUser() {
  const user = await authApi.me();
  setAuthUser(user);
  return user;
}

let restoreRequest: Promise<void> | null = null;

export function restoreAuthSession() {
  if (restoreRequest) {
    return restoreRequest;
  }

  setAuthInitializing();
  restoreRequest = (async () => {
    try {
      await authApi.refresh();
      if (!getAuthState().user) {
        await loadCurrentUser().catch(() => undefined);
      }
    } catch {
      clearAuthSession();
    }
  })().finally(() => {
    restoreRequest = null;
  });

  return restoreRequest;
}
