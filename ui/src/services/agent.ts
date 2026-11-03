import api from "./index";

const WEB_API_PREFIX = "/web/api";
const DATA_API_PREFIX = "/data";

export const agentApi = {
  loginIn: () => api.get(`${WEB_API_PREFIX}/login`),
  getWhiteList: () => api.get(`${WEB_API_PREFIX}/getWhiteList`),
  apply: (data: string) => api.get(`${WEB_API_PREFIX}/cute/apply`, { email: data }),
  allModels: () => api.get(`${DATA_API_PREFIX}/allModels`),
  previewData: (modelCode: string) =>
    api.get(`${DATA_API_PREFIX}/previewData?modelCode=${modelCode}`),
};
