import { type RefObject, useEffect, useRef } from "react";

type UsePromptInputDropOptions = {
  add: (files: File[] | FileList) => void;
  formRef: RefObject<HTMLFormElement | null>;
  globalDrop?: boolean;
};

/**
 * 文件拖拽监听和附件状态拆开，主文件只保留表单结构。
 */
export function usePromptInputDrop(options: UsePromptInputDropOptions) {
  const { add, formRef, globalDrop } = options;
  // add 常来自内联回调；用 ref 读取最新值，避免每次渲染重绑拖拽监听。
  const addRef = useRef(add);
  useEffect(() => {
    addRef.current = add;
  }, [add]);

  useEffect(() => {
    const form = formRef.current;
    if (!form) {
      return;
    }
    if (globalDrop) {
      return;
    }

    const onDragOver = (event: DragEvent) => {
      if (event.dataTransfer?.types?.includes("Files")) {
        event.preventDefault();
      }
    };
    const onDrop = (event: DragEvent) => {
      if (event.dataTransfer?.types?.includes("Files")) {
        event.preventDefault();
      }
      if (event.dataTransfer?.files && event.dataTransfer.files.length > 0) {
        addRef.current(event.dataTransfer.files);
      }
    };

    form.addEventListener("dragover", onDragOver);
    form.addEventListener("drop", onDrop);
    return () => {
      form.removeEventListener("dragover", onDragOver);
      form.removeEventListener("drop", onDrop);
    };
  }, [formRef, globalDrop]);

  useEffect(() => {
    if (!globalDrop) {
      return;
    }

    const onDragOver = (event: DragEvent) => {
      if (event.dataTransfer?.types?.includes("Files")) {
        event.preventDefault();
      }
    };
    const onDrop = (event: DragEvent) => {
      if (event.dataTransfer?.types?.includes("Files")) {
        event.preventDefault();
      }
      if (event.dataTransfer?.files && event.dataTransfer.files.length > 0) {
        addRef.current(event.dataTransfer.files);
      }
    };

    document.addEventListener("dragover", onDragOver);
    document.addEventListener("drop", onDrop);
    return () => {
      document.removeEventListener("dragover", onDragOver);
      document.removeEventListener("drop", onDrop);
    };
  }, [globalDrop]);
}
