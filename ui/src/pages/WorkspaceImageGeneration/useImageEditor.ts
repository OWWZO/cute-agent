import { useCallback, useEffect, useRef, useState } from "react";

import type { EditorImageItem } from "./types";
import {
  buildMaskedComposite,
  createLocalId,
  hasCanvasDrawing,
  loadImageElement,
  resolveImageNaturalSize,
} from "./utils";

export function revokeEditorImageObjectUrls(
  images: Array<Pick<EditorImageItem, "objectUrl">>
) {
  images.forEach((item) => URL.revokeObjectURL(item.objectUrl));
}

function applyBrushStyle(
  context: CanvasRenderingContext2D,
  toolMode: "brush" | "eraser",
  brushSize: number
): void {
  context.globalCompositeOperation =
    toolMode === "eraser" ? "destination-out" : "source-over";
  context.fillStyle = "rgba(239, 68, 68, 0.55)";
  context.strokeStyle = "rgba(239, 68, 68, 0.55)";
  context.lineWidth = brushSize;
}

async function createEditorImageItem(file: File): Promise<EditorImageItem> {
  const objectUrl = URL.createObjectURL(file);
  try {
    const size = await resolveImageNaturalSize(objectUrl);
    return {
      id: createLocalId("img"),
      file,
      objectUrl,
      naturalWidth: size.width,
      naturalHeight: size.height,
      maskDataUrl: null,
    };
  } catch (error) {
    console.error("[useImageEditor] 图片尺寸读取失败，使用占位尺寸", error);
    return {
      id: createLocalId("img"),
      file,
      objectUrl,
      naturalWidth: 0,
      naturalHeight: 0,
      maskDataUrl: null,
    };
  }
}

export async function loadEditorImageItems(
  files: File[],
  createItem: (file: File) => Promise<EditorImageItem> = createEditorImageItem
): Promise<EditorImageItem[]> {
  const results = await Promise.allSettled(
    files.map((file) => Promise.resolve().then(() => createItem(file)))
  );
  const items: EditorImageItem[] = [];
  results.forEach((result) => {
    if (result.status === "fulfilled") {
      items.push(result.value);
    } else {
      console.error("[useImageEditor] 图片加载失败", result.reason);
    }
  });
  return items;
}

export async function resolveLoadedEditorImages(
  files: File[],
  isMounted: () => boolean,
  createItem: (file: File) => Promise<EditorImageItem> = createEditorImageItem,
  revoke: (items: EditorImageItem[]) => void = revokeEditorImageObjectUrls
): Promise<EditorImageItem[] | null> {
  const items = await loadEditorImageItems(files, createItem);
  if (!isMounted()) {
    revoke(items);
    return null;
  }
  return items;
}

/**
 * 图片编辑器相关状态和画布副作用集中在这里，避免页面继续堆叠 DOM/Canvas 细节。
 */
export function useImageEditor() {
  const [images, setImages] = useState<EditorImageItem[]>([]);
  const [editingImageId, setEditingImageId] = useState<string | null>(null);
  const [brushSize, setBrushSize] = useState(32);
  const [toolMode, setToolMode] = useState<"brush" | "eraser">("brush");
  // 图片 onLoad 后尺寸才可用，用一个显式版本号唤醒画布同步 effect。
  const [editorLayoutVersion, setEditorLayoutVersion] = useState(0);

  const editorImageRef = useRef<HTMLImageElement>(null);
  const maskCanvasRef = useRef<HTMLCanvasElement>(null);
  const maskContextRef = useRef<CanvasRenderingContext2D | null>(null);
  const isDrawingRef = useRef(false);
  const lastPointRef = useRef<{ x: number; y: number } | null>(null);
  const imagesRef = useRef<EditorImageItem[]>([]);
  const mountedRef = useRef(true);

  const editingImage = images.find((item) => item.id === editingImageId) || null;

  useEffect(() => {
    imagesRef.current = images;
  }, [images]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      revokeEditorImageObjectUrls(imagesRef.current);
    };
  }, []);

  const addFiles = useCallback(async (fileList: FileList | File[]) => {
    const selectedFiles = Array.from(fileList).filter((file) => file.type.startsWith("image/"));
    if (!selectedFiles.length) {
      return;
    }

    try {
      const nextItems = await resolveLoadedEditorImages(
        selectedFiles,
        () => mountedRef.current
      );
      if (!nextItems) {
        return;
      }
      setImages((previous) => [...previous, ...nextItems]);
    } catch (error) {
      console.error("[useImageEditor] 添加图片失败", error);
    }
  }, []);

  useEffect(() => {
    const handlePaste = (event: ClipboardEvent) => {
      const clipboardItems = event.clipboardData?.items;
      if (!clipboardItems?.length) {
        return;
      }

      const pastedImages: File[] = [];
      Array.from(clipboardItems).forEach((item) => {
        if (item.type.startsWith("image/")) {
          const file = item.getAsFile();
          if (file) {
            pastedImages.push(file);
          }
        }
      });

      if (!pastedImages.length) {
        return;
      }

      event.preventDefault();
      void addFiles(pastedImages);
    };

    document.addEventListener("paste", handlePaste);
    return () => document.removeEventListener("paste", handlePaste);
  }, [addFiles]);

  useEffect(() => {
    if (!editingImage) {
      return;
    }

    let cancelled = false;

    // 让画布始终跟随图片当前显示尺寸，避免窗口缩放后蒙版错位。
    const syncCanvas = async () => {
      const imageElement = editorImageRef.current;
      const canvas = maskCanvasRef.current;
      if (!imageElement || !canvas || cancelled) {
        return;
      }

      const width = imageElement.clientWidth;
      const height = imageElement.clientHeight;
      if (!width || !height) {
        return;
      }

      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext("2d");
      if (!context) {
        return;
      }

      context.clearRect(0, 0, width, height);
      context.lineCap = "round";
      context.lineJoin = "round";
      maskContextRef.current = context;

      if (editingImage.maskDataUrl) {
        try {
          const maskImage = await loadImageElement(editingImage.maskDataUrl);
          if (!cancelled) {
            context.drawImage(maskImage, 0, 0, width, height);
          }
        } catch {
          // 旧蒙版加载失败时忽略，避免卡住后续编辑。
        }
      }
    };

    const handleResize = () => {
      void syncCanvas();
    };

    window.addEventListener("resize", handleResize);
    void syncCanvas();
    return () => {
      cancelled = true;
      window.removeEventListener("resize", handleResize);
    };
  }, [editingImage, editorLayoutVersion]);

  useEffect(() => {
    const canvas = maskCanvasRef.current;
    if (!canvas || !editingImage) {
      return;
    }

    const getPoint = (event: MouseEvent | TouchEvent) => {
      const rect = canvas.getBoundingClientRect();
      const source = "touches" in event ? event.touches[0] : event;
      return {
        x: source.clientX - rect.left,
        y: source.clientY - rect.top,
      };
    };

    const drawDot = (x: number, y: number) => {
      const context = maskContextRef.current;
      if (!context) {
        return;
      }
      applyBrushStyle(context, toolMode, brushSize);
      context.beginPath();
      context.arc(x, y, brushSize / 2, 0, Math.PI * 2);
      context.fill();
    };

    const drawSegment = (startX: number, startY: number, endX: number, endY: number) => {
      const context = maskContextRef.current;
      if (!context) {
        return;
      }
      applyBrushStyle(context, toolMode, brushSize);
      context.beginPath();
      context.moveTo(startX, startY);
      context.lineTo(endX, endY);
      context.stroke();
    };

    const handleStart = (event: MouseEvent | TouchEvent) => {
      event.preventDefault();
      isDrawingRef.current = true;
      const point = getPoint(event);
      lastPointRef.current = point;
      drawDot(point.x, point.y);
    };

    const handleMove = (event: MouseEvent | TouchEvent) => {
      if (!isDrawingRef.current || !lastPointRef.current) {
        return;
      }
      event.preventDefault();
      const point = getPoint(event);
      drawSegment(lastPointRef.current.x, lastPointRef.current.y, point.x, point.y);
      drawDot(point.x, point.y);
      lastPointRef.current = point;
    };

    const handleEnd = () => {
      isDrawingRef.current = false;
      lastPointRef.current = null;
    };

    canvas.addEventListener("mousedown", handleStart as EventListener);
    window.addEventListener("mousemove", handleMove as EventListener);
    window.addEventListener("mouseup", handleEnd);
    canvas.addEventListener("touchstart", handleStart as EventListener, { passive: false });
    window.addEventListener("touchmove", handleMove as EventListener, { passive: false });
    window.addEventListener("touchend", handleEnd);

    return () => {
      canvas.removeEventListener("mousedown", handleStart as EventListener);
      window.removeEventListener("mousemove", handleMove as EventListener);
      window.removeEventListener("mouseup", handleEnd);
      canvas.removeEventListener("touchstart", handleStart as EventListener);
      window.removeEventListener("touchmove", handleMove as EventListener);
      window.removeEventListener("touchend", handleEnd);
    };
  }, [brushSize, editingImage, toolMode]);

  const collectEffectiveImages = useCallback(() => {
    if (!editingImageId || !maskCanvasRef.current) {
      return images;
    }

    const currentImage = images.find((item) => item.id === editingImageId);
    if (!currentImage) {
      return images;
    }

    const sourceCanvas = maskCanvasRef.current;
    const naturalWidth =
      currentImage.naturalWidth || editorImageRef.current?.naturalWidth || sourceCanvas.width;
    const naturalHeight =
      currentImage.naturalHeight || editorImageRef.current?.naturalHeight || sourceCanvas.height;

    const outputCanvas = document.createElement("canvas");
    outputCanvas.width = naturalWidth;
    outputCanvas.height = naturalHeight;

    const outputContext = outputCanvas.getContext("2d");
    if (!outputContext) {
      return images;
    }

    outputContext.drawImage(sourceCanvas, 0, 0, naturalWidth, naturalHeight);
    const nextMaskDataUrl = hasCanvasDrawing(outputCanvas)
      ? outputCanvas.toDataURL("image/png")
      : null;

    const nextImages = images.map((item) =>
      item.id === editingImageId
        ? {
          ...item,
          naturalWidth,
          naturalHeight,
          maskDataUrl: nextMaskDataUrl,
        }
        : item
    );
    setImages(nextImages);
    return nextImages;
  }, [editingImageId, images]);

  const closeEditor = useCallback(() => {
    collectEffectiveImages();
    setEditingImageId(null);
  }, [collectEffectiveImages]);

  const openEditor = useCallback((imageId: string) => {
    collectEffectiveImages();
    setEditingImageId(imageId);
  }, [collectEffectiveImages]);

  const removeImage = useCallback((imageId: string) => {
    setImages((previous) => {
      const target = previous.find((item) => item.id === imageId);
      if (target) {
        URL.revokeObjectURL(target.objectUrl);
      }
      return previous.filter((item) => item.id !== imageId);
    });
    if (editingImageId === imageId) {
      setEditingImageId(null);
    }
  }, [editingImageId]);

  const clearCurrentMask = useCallback(() => {
    if (!maskCanvasRef.current) {
      return;
    }

    const context =
      maskContextRef.current ?? maskCanvasRef.current.getContext("2d");
    if (context) {
      context.clearRect(0, 0, maskCanvasRef.current.width, maskCanvasRef.current.height);
    }

    if (editingImageId) {
      setImages((previous) =>
        previous.map((item) =>
          item.id === editingImageId
            ? {
              ...item,
              maskDataUrl: null,
            }
            : item
        )
      );
    }
  }, [editingImageId]);

  const refreshEditorLayout = useCallback(() => {
    setEditorLayoutVersion((previous) => previous + 1);
  }, []);

  const buildMaskCompositeDataUrls = useCallback(async (
    effectiveImages: EditorImageItem[],
    sourceImageDataUrls: string[]
  ) => {
    // 各图蒙版合成互不依赖，并行处理；单张失败退化为「无蒙版」，不再中断整批。
    return Promise.all(
      effectiveImages.map(async (currentImage, index) => {
        if (!currentImage.maskDataUrl) {
          return "";
        }
        try {
          return await buildMaskedComposite({
            imageSrc: sourceImageDataUrls[index],
            maskDataUrl: currentImage.maskDataUrl,
            width: currentImage.naturalWidth,
            height: currentImage.naturalHeight,
          });
        } catch (error) {
          console.error("[useImageEditor] 蒙版合成失败", error);
          return "";
        }
      })
    );
  }, []);

  return {
    images,
    editingImage,
    editingImageId,
    brushSize,
    toolMode,
    editorImageRef,
    maskCanvasRef,
    addFiles,
    collectEffectiveImages,
    closeEditor,
    openEditor,
    removeImage,
    clearCurrentMask,
    refreshEditorLayout,
    buildMaskCompositeDataUrls,
    setBrushSize,
    setToolMode,
  };
}
