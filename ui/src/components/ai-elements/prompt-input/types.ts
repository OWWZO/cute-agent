import type { FileUIPart } from "ai";

/**
 * 附件视图项。
 *
 * `file` 是浏览器端的唯一事实来源，`url`/`mediaType`/`filename` 都由它派生；
 * 序列化或重新水合后 `file` 会缺失，此时只能依赖已落地的 `url`。
 */
export type PromptInputAttachmentItem = FileUIPart & {
  id: string;
  file?: File;
};

/**
 * 附件校验失败原因（唯一事实来源，用户可读文案由消费方按 code 渲染）：
 * - `accept`：全部文件类型都不匹配；
 * - `max_file_size`：全部文件都超过大小上限；
 * - `max_files`：超过数量上限，超出部分未加入；
 * - `partial`：部分文件因类型或大小被忽略，其余文件已加入。
 */
export type PromptInputAttachmentErrorCode =
  | "max_files"
  | "max_file_size"
  | "accept"
  | "partial";

export type PromptInputAttachmentError = {
  code: PromptInputAttachmentErrorCode;
};
