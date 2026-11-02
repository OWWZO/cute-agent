#!/usr/bin/env node
/**
 * Apple-grade 门禁：禁止语义 token 之外的硬编码颜色与散写动效。
 *
 * 规则（对应 ui/CLAUDE.md「当前常驻目标」底线 ① 与 ②）：
 *   - Tailwind 调色板直写：`text-slate-500`、`bg-rose-50`、`border-amber-300` …
 *   - 裸 hex：`#fff`、`#1783ff`
 *   - 散写动效：`duration-[160ms]`、`ease-[cubic-bezier(...)]`、
 *     `transition={{ duration: 0.18 }}` —— 时长/曲线必须来自 `src/lib/motion.ts`
 *     或 global.css 的 `--duration-*` / `--ease-*`
 *
 * 例外（显式白名单，新增条目必须写清理由）：
 *   - 品牌色映射表（如欢迎页推荐问题的平台品牌色）
 *   - 测试文件与 global.css 本身（token 定义处）
 *
 * 用法：node scripts/check-design-tokens.mjs [--fix-hint]
 * 退出码 1 表示存在违规，可直接接 CI / pre-commit。
 */
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

const ROOT = path.resolve(import.meta.dirname, "..", "src");
const EXT = new Set([".ts", ".tsx", ".css"]);

/** 调色板类名前缀 + 色名 + 数字档位。 */
const PALETTE_RE =
  /(?:text|bg|border|from|to|via|ring|fill|stroke|divide|placeholder|shadow|outline|decoration|accent|caret)-(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b/g;

/** 裸 hex；允许 3/4/6/8 位。 */
const HEX_RE = /#[0-9a-fA-F]{3,8}\b/g;

/**
 * 动效散写：时长/曲线必须来自 `src/lib/motion.ts` 或 global.css 的 --duration-* / --ease-*。
 *
 * 只禁「自定义任意值」，不禁 Tailwind 内置档位（duration-150 等与 DURATION 语义一致）：
 *   - `duration-[160ms]` / `duration-[0.3s]`：绕过 token 的硬编码时长
 *   - `ease-[cubic-bezier(...)]`：绕过 --ease-* 的硬编码曲线
 *   - `transition={{ duration: 0.18 }}`：组件内联非预设值
 */
const MOTION_HARDCODED = [
  {
    re: /\bduration-\[\s*\d+(?:\.\d+)?m?s\s*\]/g,
    hint: "duration-[160ms] → duration-[var(--duration-base)]",
  },
  {
    re: /\bease-\[\s*cubic-bezier\([^\]]*\)\s*\]/g,
    hint: "ease-[cubic-bezier(...)] → ease-[var(--ease-out)] 或 motion.ts 预设",
  },
  {
    re: /\btransition=\{\{\s*duration:\s*\d/g,
    hint: "transition={{ duration: 0.18 }} → 从 @/lib/motion 引用 DURATION_QUICK / SPRING_*",
  },
];

/**
 * 排版硬编码：字号必须走 `text-ui-*` 刻度或 `--content-font-size`。
 *
 * 允许的任意值：Display 轨标题（≥20px），它们不属于界面字号刻度（见 DESIGN.md §1.1）。
 * 禁止的：
 *   - 界面字号区间的任意值（10–19px）→ 用 text-ui-xs/sm/caption/base/lg
 *   - `tracking-tight`（-0.025em）→ 与规范的 -0.02em 冲突，必须显式写
 */
const TYPO_HARDCODED = [
  {
    // 10–19px 的任意字号值；20px 以上属于 Display 轨，放行
    re: /\btext-\[1[0-9](?:\.\d+)?px\]/g,
    hint: "任意字号 → text-ui-xs(10) / text-ui-sm(12) / text-ui-caption(13) / text-ui-base(14) / text-ui-lg(16) / text-ui-xl(18)；15px 用 text-[length:var(--content-font-size)]",
  },
  {
    // 标题字号：≥20px 的任意值应走 display 刻度
    // 例外：图标尺寸（如 LoadingSpinner 的 text-[32px]）用 text- 控制 svg 大小，
    // 这类行通常不含 h1/h2/h3 且带 size-*/h-*/w-* 上下文，这里不强行区分，
    // 改为在 ALLOW 中按文件+行登记。
    re: /\btext-\[(?:[2-9][0-9]|[1-9][0-9]{2})px\]/g,
    hint: "标题任意字号 → text-display-sm(22) / -md(32) / -lg(36) / -xl(40) / -2xl(42) / -3xl(44) / -4xl(48)",
  },
  {
    re: /\btracking-tight\b/g,
    hint: "tracking-tight(-0.025em) → tracking-[-0.02em]（规范统一值）",
  },
];

/**
 * 豁免路径：这些目录的用色**不是 UI 皮肤**，不受 token 规则约束。
 * 每一条都要能说出「为什么它不该跟着主题走」。
 */
const SKIP_PATH = [
  // GitHub 官方 markdown 主题，整份是第三方色板
  "assets/styles/github-markdown.css",
  // OpenUI 组件自带的渲染主题与地图底图
  "lib/openui/",
  // 数据可视化：图表色板、地图底图属于「数据编码」，不是界面语义
  "components/DataChat/",
  // 3D 模型查看器的场景底色
  "components/genui/GenUiModel3D.tsx",
  // 注入到宿主页面的选择器脚本（跑在别人的 DOM 里，不能依赖本应用 token）
  "lib/webElementPicker/",
  // 发往 LLM 的提示词上下文
  "lib/promptContexts/",
];

/**
 * 白名单：按「文件 + 该行必须匹配的片段」精确豁免，避免整文件豁免。
 * 品牌色是设计资产的一部分，不是随手硬编码。
 */
const ALLOW = [
  {
    file: "components/ActionPanel/Loading.tsx",
    reason: "text-[32px] 控制的是 spinner 图标的字号（svg 继承 currentFontSize），不是文字排版",
    match: /LoadingSpinner|text-\[32px\]/,
  },
  {
    file: "pages/Home/WelcomeView.tsx",
    reason: "推荐问题图标使用各平台官方品牌色（YouTube 红、TikTok 黑等），属于品牌资产",
    match: /SUGGESTION_ICON_CLASS|(?:text|bg)-\[#[0-9a-fA-F]{6}\]/,
  },
  {
    file: "components/chat/AnimatedOrb.tsx",
    reason: "品牌球体的渐变色序列，本身就是视觉标识而非界面语义色",
    match: /#[0-9a-fA-F]{6}/,
  },
  {
    file: "components/GeneralInput/ContextRing.tsx",
    reason: "上下文用量环用 iOS 系统色（红/橙/绿）表达阈值，属于语义化状态色",
    match: /#[0-9a-fA-F]{6}/,
  },
  {
    file: "pages/Auth/liquid-glass/LiquidGlassAuth.tsx",
    reason: "液态玻璃登录页的深蓝品牌背景渐变，是该页独有的视觉资产",
    match: /#[0-9a-fA-F]{6}/,
  },
  {
    file: "components/Dialogue/TurnNavigatorMarks.tsx",
    reason: "轮次导航标记沿用聊天主色，深色模式下需固定亮度",
    match: /#[0-9a-fA-F]{6}/,
  },
  {
    file: "components/ActionPanel/FilePreviewSwitch.tsx",
    reason: "预览容器底色，与 PDF/Office 渲染器画布保持一致的固定白场",
    match: /#[0-9a-fA-F]{6}/,
  },
  {
    file: "components/ActionPanel/FileRenderer.tsx",
    reason: "同上：预览画布固定底色",
    match: /#[0-9a-fA-F]{6}/,
  },
  {
    file: "utils/constants.ts",
    reason: "推荐问题的品牌主色常量",
    match: /#[0-9a-fA-F]{6}/,
  },
  {
    file: "pages/WorkspaceImageGeneration/utils.ts",
    reason: "图像生成结果的色值解析，处理的是用户数据不是界面色",
    match: /#[0-9a-fA-F]{6}/,
  },
  {
    file: "components/ActionPanel/codeViewer/utils.ts",
    reason: "代码高亮主题色映射",
    match: /#[0-9a-fA-F]{6}/,
  },
];

const walk = async (dir) => {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...(await walk(full)));
    } else if (EXT.has(path.extname(entry.name))) {
      out.push(full);
    }
  }
  return out;
};

const rel = (p) => path.relative(ROOT, p).replaceAll("\\", "/");

const isSkipped = (file) => SKIP_PATH.some((prefix) => file.startsWith(prefix));

const isAllowed = (file, line) =>
  ALLOW.some((rule) => rule.file === file && rule.match.test(line));

const main = async () => {
  const files = await walk(ROOT);
  const violations = [];
  let scanned = 0;

  for (const file of files) {
    const name = rel(file);
    if (name.endsWith(".test.ts") || name.endsWith(".test.tsx")) continue;
    if (name === "global.css") continue; // token 定义处
    if (isSkipped(name)) continue;

    scanned++;
    const lines = (await readFile(file, "utf8")).split("\n");
    lines.forEach((line, i) => {
      if (isAllowed(name, line)) return;
      // 注释里常引用第三方库源码片段（如 `background-color:#0000`），
      // 那是文档证据，不是本项目的样式声明。
      const code = line.trim();
      if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
        return;
      }
      const collect = (re, hint = "") => {
        for (const hit of line.matchAll(re)) {
          violations.push({ file: name, line: i + 1, text: hit[0], hint });
        }
      };
      collect(PALETTE_RE);
      collect(HEX_RE);
      for (const rule of MOTION_HARDCODED) collect(rule.re, rule.hint);
      for (const rule of TYPO_HARDCODED) collect(rule.re, rule.hint);
    });
  }

  if (violations.length === 0) {
    console.log(`✓ design tokens: 0 violations (${scanned} files scanned)`);
    return;
  }

  console.error(`✗ design tokens: ${violations.length} violation(s)\n`);
  for (const v of violations) {
    console.error(
      `  ${v.file}:${v.line}  ${v.text}${v.hint ? `\n      → ${v.hint}` : ""}`
    );
  }
  console.error(
    "\n修复：改用语义 token（如 var(--chat-text-soft) / text-foreground-subtle），" +
      "\n或在 scripts/check-design-tokens.mjs 的 ALLOW 中登记品牌色例外并写明理由。"
  );
  process.exit(1);
};

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
