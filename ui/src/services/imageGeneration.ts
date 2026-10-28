import api from "./index";
import type {
  ImageGenerationHistoryPage,
  ImageGenerationToolResponse,
} from "@/pages/WorkspaceImageGeneration/types";

const IMAGE_GENERATION_REQUEST_TIMEOUT_MS = 330_000;

type ToolRequest = {
  requestId: string;
  prompt: string;
  mode: "images" | "edits";
  model?: string;
  size: string;
  n: number;
  fileNames: string[];
  maskFileNames: string[];
  fileName?: string;
  fileDescription?: string;
};

export class ImageGenerationRequestError extends Error {
  rawResponse?: unknown;

  constructor(message: string, rawResponse?: unknown) {
    super(message);
    this.name = "ImageGenerationRequestError";
    this.rawResponse = rawResponse;
  }
}

export async function requestImageGenerationTool(
  payload: ToolRequest
): Promise<ImageGenerationToolResponse> {
  // 代理请求沿用统一 api 客户端和长超时，错误统一包装为可识别的领域异常。
  try {
    return (await api.post<ImageGenerationToolResponse>("/api/agent/image-generation/generate", {
      requestId: payload.requestId,
      prompt: payload.prompt,
      mode: payload.mode,
      model: payload.model,
      fileNames: payload.fileNames,
      maskFileNames: payload.maskFileNames,
      fileName: payload.fileName,
      fileDescription: payload.fileDescription,
      size: payload.size,
      n: payload.n,
    }, {
      timeout: IMAGE_GENERATION_REQUEST_TIMEOUT_MS,
    })) as unknown as ImageGenerationToolResponse;
  } catch (error) {
    throw new ImageGenerationRequestError(
      error instanceof Error ? error.message : "请求失败"
    );
  }
}

export async function requestImageGenerationHistory(params: {
  pageNo: number;
  pageSize: number;
}): Promise<ImageGenerationHistoryPage> {
  // 历史查询与生成请求使用同一异常类型，页面只需统一处理 message 展示。
  try {
    return (await api.get<ImageGenerationHistoryPage>("/api/agent/image-generation/history", params)) as unknown as ImageGenerationHistoryPage;
  } catch (error) {
    throw new ImageGenerationRequestError(
      error instanceof Error ? error.message : "历史查询失败"
    );
  }
}
