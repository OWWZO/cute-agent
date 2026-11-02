import { type RefObject, useEffect, useRef } from "react";

type AddFiles = (files: File[] | FileList) => void;

type UsePromptInputDropOptions = {
  add: AddFiles;
  formRef: RefObject<HTMLFormElement | null>;
  globalDrop?: boolean;
};

/**
 * 表单与全局拖拽共用同一组处理函数，只有监听目标不同，避免两处行为分叉。
 */
function createDropHandlers(add: AddFiles) {
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
      add(event.dataTransfer.files);
    }
  };
  return {
    onDragOver,
    onDrop,
  };
}

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
    if (globalDrop) {
      return;
    }
    const form = formRef.current;
    if (!form) {
      return;
    }

    const { onDragOver, onDrop } = createDropHandlers((files) =>
      addRef.current(files)
    );
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

    const { onDragOver, onDrop } = createDropHandlers((files) =>
      addRef.current(files)
    );
    document.addEventListener("dragover", onDragOver);
    document.addEventListener("drop", onDrop);
    return () => {
      document.removeEventListener("dragover", onDragOver);
      document.removeEventListener("drop", onDrop);
    };
  }, [globalDrop]);
}
