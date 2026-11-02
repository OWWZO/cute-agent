export type UploadStatus = "pending" | "uploading" | "success" | "error";

export type UploadAttachmentState = {
  id: string;
  file: File;
  status: UploadStatus;
  error?: string;
  uploadedFile?: CHAT.TFile;
};

// 上传状态更新均返回新 queue，未知 id 原样返回，避免异步回调误创建幽灵附件。
function updateUpload(
  queue: Record<string, UploadAttachmentState>,
  id: string,
  patch: Partial<UploadAttachmentState>
): Record<string, UploadAttachmentState> {
  if (!Object.hasOwn(queue, id)) {
    return queue;
  }

  return {
    ...queue,
    [id]: {
      ...queue[id],
      ...patch,
    },
  };
}

export function markUploadSuccess(
  queue: Record<string, UploadAttachmentState>,
  id: string,
  uploadedFile: CHAT.TFile
): Record<string, UploadAttachmentState> {
  return updateUpload(queue, id, {
    status: "success",
    error: undefined,
    uploadedFile,
  });
}

export function markUploadUploading(
  queue: Record<string, UploadAttachmentState>,
  id: string
): Record<string, UploadAttachmentState> {
  return updateUpload(queue, id, {
    status: "uploading",
    error: undefined,
  });
}

export function markUploadError(
  queue: Record<string, UploadAttachmentState>,
  id: string,
  error: string
): Record<string, UploadAttachmentState> {
  return updateUpload(queue, id, {
    status: "error",
    error,
  });
}
