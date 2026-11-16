export interface OpenUiDisplay {
  text: string;
  streaming: boolean;
  frozen: boolean;
}

export interface SourceRepair {
  source: string;
  program: string;
}

/** 修复只对它对应的源码有效；文本一换就当帧失效，不能等 effect 再清。 */
export function selectRepairForSource(
  source: string,
  repair: SourceRepair | null
): string | null {
  return repair?.source === source ? repair.program : null;
}

/**
 * 决定程序块当前该交给渲染器的是什么。
 *
 * 最终校验失败时，不能把错误的最终文本直接交给 Renderer，也不能切回源码展示。
 * 保留最后一帧已渲染程序并继续使用流式解析模式，等待修复结果通过验收后再切换。
 */
export function resolveOpenUiDisplay({
  program,
  repaired,
  streaming,
  invalid,
  lastRendered
}: {
  program: string;
  repaired: string | null;
  streaming: boolean;
  invalid: boolean;
  lastRendered: string | null;
}): OpenUiDisplay | null {
  if (!invalid) {
    return {
      text: repaired ?? program,
      streaming,
      frozen: false
    };
  }
  if (!lastRendered?.trim()) {
    return null;
  }
  return {
    text: lastRendered,
    streaming: true,
    frozen: true
  };
}
