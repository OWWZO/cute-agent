/**
 * 代码围栏的高亮策略。
 *
 * 抽成独立模块（而非放在组件文件里）有两个原因：
 * 1. 组件文件只导出组件，避免 fast-refresh 失效；
 * 2. 策略是纯函数，可以被直接断言 —— 组件内的 effect 在 SSR 下不执行，
 *    用渲染结果验证不到「流式时是否真的跳过了高亮」。
 */

/**
 * 这段代码是否应该交给 shiki 高亮。
 *
 * 流式期间一律不高亮，对齐 ZCode 的
 * `enableSyntaxHighlighting={!renderStreaming}`：流式时 `code` 每帧都在变，
 * 异步 tokenize 会和消息流更新叠加，既浪费 CPU 又容易产生抖动。
 * 消息落定后 `streaming` 转 false，再一次性高亮。
 *
 * 返回 `false` 时调用方**必须把 `code` 当纯文本转义渲染（JSX 文本节点）**，
 * 绝不能作为 HTML 注入 —— 本函数只是策略判定，不负责转义。
 * `false` 同时覆盖「流式期间稍后高亮」和「上游已是 Shiki HTML、永不二次注入」
 * 两种语义；需要区分它们的调用方应自行判断 `streaming`。
 */
export function shouldTokenizeCode(code: string, streaming: boolean): boolean {
  if (streaming) return false;
  if (!code.trim()) return false;
  // 源码若已是 Shiki HTML，说明上游串台；只当纯文本展示，绝不二次注入。
  if (/^\s*<pre\b[^>]*\bshiki\b/i.test(code)) return false;
  return true;
}
