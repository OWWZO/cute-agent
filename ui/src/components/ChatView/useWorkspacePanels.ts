import { useCallback, useEffect, useRef, useState } from "react";
import type { GroupImperativeHandle } from "react-resizable-panels";
import {
  WORKSPACE_RESIZE_END_EVENT,
  WORKSPACE_RESIZE_START_EVENT,
} from "@/utils/workspaceResize";

const DEFAULT_LEFT_WIDTH = 50;
export const MIN_LEFT_WIDTH = 24;
export const MAX_LEFT_WIDTH = 56;

/** 沉浸模式：对话区窄列约 24%，工作区主导约 76%（对齐 ClawsGO） */
export const IMMERSIVE_LEFT_WIDTH = 24;

export const clampWorkspaceLeftWidth = (width: number) =>
  Math.max(MIN_LEFT_WIDTH, Math.min(MAX_LEFT_WIDTH, width));

/**
 * 把百分比常量转成 `Panel` 的尺寸 prop。
 *
 * `react-resizable-panels` 把裸数字解释为**像素**，只有字符串无单位或带 `%`
 * 才是百分比；直接传 `24` 会把对话区压成 24px，工作区吃掉整屏。
 */
export const panelPercent = (percent: number) => `${percent}%`;

export const resolveWorkspacePanelLayout = (
  leftWidth: number,
  isRightCollapsed: boolean
) => isRightCollapsed
  ? { chat: 100, workspace: 0 }
  : { chat: leftWidth, workspace: 100 - leftWidth };

/**
 * `Panel` 的 `defaultSize` 必须是**常量**，不能绑定拖拽中变化的宽度 state。
 *
 * 库会把 `defaultSize` 的变化当成「重新初始化布局」：拖拽中宽度 state 一变，
 * 新 defaultSize 立刻把布局重置回原值，于是分隔条只能挪动一两像素就被拽回 ——
 * 表现为「线拖不动」。宽度回写一律走 `groupRef.setLayout()`。
 */
export const CHAT_PANEL_DEFAULT_SIZE = panelPercent(DEFAULT_LEFT_WIDTH);

/** `Group` 的 layout：panel id → 百分比。 */
type WorkspaceLayout = Record<string, number> | undefined;

/**
 * 对话区 / 工作区的布局状态。
 *
 * 拖拽与尺寸约束交给 `react-resizable-panels` 的 `Group` / `Panel` / `Separator`，
 * 这里只保留「谁是左栏、折叠、沉浸模式」这些业务状态，并把库算出的宽度
 * 镜像回本地 —— 这样切换折叠 / 沉浸模式时才知道要改回多少。
 */
export function useWorkspacePanels() {
  const [leftPanelWidth, setLeftPanelWidth] = useState(DEFAULT_LEFT_WIDTH);
  // 拖拽每一帧只更新 ref，松手时再提交一次 state，避免整个 ChatView 跟着重渲染。
  const leftPanelWidthRef = useRef(DEFAULT_LEFT_WIDTH);
  const [isLeftCollapsed, setIsLeftCollapsed] = useState(false);
  const [isRightCollapsed, setIsRightCollapsed] = useState(false);
  const [isFocusMode, setIsFocusMode] = useState(false);
  const preFocusLeftWidthRef = useRef(DEFAULT_LEFT_WIDTH);

  /**
   * 拖拽态挂载点（`Group` 的容器元素）。
   *
   * `react-resizable-panels` 不对外暴露拖拽态，所以靠往这个容器上写
   * `data-workspace-resizing` 来表达：图表用它跳过拖拽中的持续 resize，
   * `WORKSPACE_RESIZING_SELECTOR` 也指向它。
   */
  const workspaceRef = useRef<HTMLDivElement | null>(null);
  /**
   * `Group` 的命令式句柄。
   *
   * `defaultSize` 只在首次挂载时被读取（库内部用 `mutableState.layouts[id]` 缓存，
   * 有缓存就不再回看 defaultSize），所以折叠 / 沉浸模式切换必须走 `setLayout`，
   * 否则左栏宽度不会跟着变。
   */
  const groupRef = useRef<GroupImperativeHandle | null>(null);

  const handleLayoutChange = useCallback((layout: WorkspaceLayout) => {
    // 收起时会临时把布局写成 100/0；这个值不是用户拖出来的宽度，
    // 不能覆盖下次展开时要恢复的左侧宽度。
    if (workspaceRef.current?.getAttribute("data-workspace-open") === "false") {
      return;
    }
    const next = layout?.chat;
    if (typeof next === "number") {
      leftPanelWidthRef.current = clampWorkspaceLeftWidth(next);
    }
    // onLayoutChange 在拖拽的每一帧都会触发，这里只广播一次拖拽开始。
    // 拖拽结束由 Group 的 onLayoutChanged 统一收口。
    const workspace = workspaceRef.current;
    if (workspace?.getAttribute("data-workspace-resizing") !== "true") {
      workspace?.setAttribute("data-workspace-resizing", "true");
      document.dispatchEvent(new Event(WORKSPACE_RESIZE_START_EVENT));
    }
  }, []);

  /** 拖拽结束：提交最终宽度，清掉拖拽态标记，并让图表重新量一次容器。 */
  const notifyResizeEnd = useCallback(() => {
    const next = leftPanelWidthRef.current;
    setLeftPanelWidth((previous) => (previous === next ? previous : next));
    workspaceRef.current?.removeAttribute("data-workspace-resizing");
    document.dispatchEvent(new Event(WORKSPACE_RESIZE_END_EVENT));
  }, []);

  const toggleLeftPanel = useCallback(() => {
    const nextCollapsed = !isLeftCollapsed;
    setIsLeftCollapsed(nextCollapsed);
    if (!nextCollapsed) {
      leftPanelWidthRef.current = DEFAULT_LEFT_WIDTH;
      setLeftPanelWidth(DEFAULT_LEFT_WIDTH);
    }
  }, [isLeftCollapsed]);

  const toggleRightPanel = useCallback(() => {
    setIsRightCollapsed((previous) => !previous);
  }, []);

  /** 工作区收起时只改 Group 布局，不走 Panel.collapse()。 */
  const collapseRightPanel = useCallback(() => {
    setIsRightCollapsed(true);
  }, []);

  const toggleFocusMode = useCallback(() => {
    const next = !isFocusMode;
    setIsFocusMode(next);
    if (next) {
      preFocusLeftWidthRef.current = leftPanelWidth;
      setIsLeftCollapsed(false);
      setIsRightCollapsed(false);
      leftPanelWidthRef.current = IMMERSIVE_LEFT_WIDTH;
      setLeftPanelWidth(IMMERSIVE_LEFT_WIDTH);
    } else {
      const restored = preFocusLeftWidthRef.current || DEFAULT_LEFT_WIDTH;
      leftPanelWidthRef.current = restored;
      setLeftPanelWidth(restored);
    }
  }, [isFocusMode, leftPanelWidth]);

  const exitFocusMode = useCallback(() => {
    if (!isFocusMode) {
      return;
    }
    setIsFocusMode(false);
    const restored = preFocusLeftWidthRef.current || DEFAULT_LEFT_WIDTH;
    leftPanelWidthRef.current = restored;
    setLeftPanelWidth(restored);
  }, [isFocusMode]);

  // 记录程序化修改（折叠 / 沉浸）后的最新宽度，供模式切换副作用读取。
  useEffect(() => {
    leftPanelWidthRef.current = leftPanelWidth;
  }, [leftPanelWidth]);

  // 折叠 / 沉浸模式切换后：
  // 1. 把新宽度推给 Group（defaultSize 不会二次生效）；
  // 2. 让图表重新量一次容器。
  useEffect(() => {
    // 直接写 Group 布局，不依赖 Panel.collapse()：当前库版本里，
    // 收起时它会重新套用旧比例，所以收起态的可见宽度由 global.css 收口。
    groupRef.current?.setLayout(
      resolveWorkspacePanelLayout(
        leftPanelWidthRef.current,
        isRightCollapsed
      )
    );
    notifyResizeEnd();
  }, [isLeftCollapsed, isRightCollapsed, isFocusMode, notifyResizeEnd]);

  return {
    leftPanelWidth,
    isLeftCollapsed,
    isRightCollapsed,
    isFocusMode,
    setIsLeftCollapsed,
    setIsRightCollapsed,
    setIsFocusMode,
    toggleLeftPanel,
    toggleRightPanel,
    collapseRightPanel,
    toggleFocusMode,
    exitFocusMode,
    handleLayoutChange,
    workspaceRef,
    groupRef,
    notifyResizeEnd,
  };
}
