import dayjs from "dayjs";
import { ArrowUpRight } from "lucide-react";
import { Link } from "react-router-dom";
import type { ReactNode } from "react";

import type { FeaturedConversationCard as FeaturedConversationCardModel } from "@/services/featuredConversation";
import { buildFeaturedConversationDetailPath } from "@/router/routes";

type FeaturedConversationCardProps = {
  card: FeaturedConversationCardModel;
  variant?: "grid" | "row";
  /**
   * 卡片所在页面的底色。
   *
   * `muted`（默认）：页面是浅灰底，白卡片靠阴影浮起，无需边框。
   * `plain`：页面是纯白底（如首页欢迎页），白卡片必须用细边框才看得出边界，
   *          因为 `--shadow-sm` 在纯白上几乎不可见。
   */
  surface?: "muted" | "plain";
  onSelect?: (featuredId: string) => void;
};

function formatDateTime(value?: string) {
  if (!value) {
    return "";
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format("YYYY-MM-DD") : value;
}

/**
 * 发布精品时摘要默认回填会话首句，标题也来自同一句，卡片上会出现两段相同文案。
 * 摘要只有带来标题之外的信息时才值得占用版面。
 */
function hasDistinctSummary(title: string, summary: string) {
  const normalize = (value: string) =>
    value.replace(/\s+/g, "").replace(/[。.!！?？…]+$/, "");
  const normalizedSummary = normalize(summary || "");
  return (
    normalizedSummary !== "" && normalizedSummary !== normalize(title || "")
  );
}

/**
 * 无封面时的占位底。
 *
 * 以前按 featuredId 哈希出紫/橙/绿/粉四套渐变，结果 14 张卡像调色板——
 * 占位底色是「缺省材质」，不是内容，不该抢标题的注意力。收敛成单一中性渐变。
 */
const PLACEHOLDER_GRADIENT =
  "bg-gradient-to-br from-[var(--chat-surface-soft)] to-[var(--chat-surface-muted)]";

function CardShell(props: {
  href: string;
  onSelect?: () => void;
  className: string;
  children: ReactNode;
}) {
  // 嵌入场景由调用方接管选择，独立页面则使用新窗口路由跳转。
  if (props.onSelect) {
    return (
      <button
        type="button"
        onClick={props.onSelect}
        className={props.className}
      >
        {props.children}
      </button>
    );
  }

  return (
    <Link
      to={props.href}
      target="_blank"
      rel="noreferrer"
      className={props.className}
    >
      {props.children}
    </Link>
  );
}

/**
 * 标签 + 发布日期：row / grid 两种布局共用同一套展示规则（最多 3 个标签、日期缺省不渲染），
 * 只有间距与对齐不同，用 `compact` 区分。
 */
function CardMeta(props: {
  tags: string[];
  publishedLabel: string;
  compact: boolean;
}) {
  return (
    <div
      className={
        props.compact
          ? "mt-2.5 flex flex-wrap items-center gap-2"
          : "mt-auto flex flex-wrap items-center gap-2 pt-1"
      }
    >
      {props.tags.slice(0, 3).map((tag) => (
        <span
          key={tag}
          className={
            props.compact
              ? "rounded-full bg-[var(--chat-surface-soft)] px-2 py-0.5 text-ui-sm text-[var(--chat-text-soft)]"
              : "rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-ui-sm text-[var(--chat-text-soft)]"
          }
        >
          {tag}
        </span>
      ))}
      {props.publishedLabel ? (
        <span
          className={
            props.compact
              ? "text-ui-sm text-[var(--chat-text-muted)]"
              : "ml-auto text-ui-sm tabular-nums text-[var(--chat-text-muted)]"
          }
        >
          {props.publishedLabel}
        </span>
      ) : null}
    </div>
  );
}

export default function FeaturedConversationCard(
  props: FeaturedConversationCardProps
) {
  const { card, variant = "grid", surface = "muted", onSelect } = props;
  const tags = Array.isArray(card.tags) ? card.tags : [];
  const publishedLabel = formatDateTime(card.publishedAt);
  const href = buildFeaturedConversationDetailPath(card.featuredId);
  const showSummary = hasDistinctSummary(card.title, card.summary);
  // 统一在渲染前准备标签、日期和跳转地址，grid/row 两种布局共享同一数据语义。
  const handleSelect = onSelect
    ? () => onSelect(card.featuredId)
    : undefined;

  if (variant === "row") {
    return (
      <CardShell
        href={href}
        onSelect={handleSelect}
        className="group flex w-full items-stretch gap-4 rounded-2xl bg-[var(--chat-surface)] p-3 text-left shadow-[var(--shadow-sm)] transition-[box-shadow,transform,background-color] duration-200 ease-out hover:bg-[var(--chat-surface-soft)]/50 hover:shadow-[var(--shadow-md)] hover:-translate-y-0.5 active:scale-[0.99] motion-reduce:translate-none motion-reduce:active:scale-100 sm:gap-5 sm:p-4"
      >
        <div
          className={`relative h-20 w-20 shrink-0 overflow-hidden rounded-xl sm:h-24 sm:w-28 ${PLACEHOLDER_GRADIENT}`}
        >
          {card.coverUrl ? (
            <img
              src={card.coverUrl}
              alt={card.title}
              className="h-full w-full object-cover transition duration-300 group-hover:scale-[1.04]"
            />
          ) : (
            <div className="flex h-full items-end p-3">
              <span className="text-ui-sm font-medium text-[var(--chat-text-soft)]">
                案例
              </span>
            </div>
          )}
        </div>

        <div className="min-w-0 flex-1 py-0.5">
          <div className="flex items-start justify-between gap-3">
            <h3 className="line-clamp-1 text-ui-lg font-semibold tracking-[-0.02em] text-[var(--chat-text)]">
              {card.title}
            </h3>
            <ArrowUpRight className="mt-0.5 h-4 w-4 shrink-0 text-[var(--chat-text-muted)] opacity-0 transition group-hover:opacity-100" />
          </div>
          {showSummary ? (
            <p className="mt-1.5 line-clamp-2 text-ui-caption leading-6 text-[var(--chat-text-muted)]">
              {card.summary}
            </p>
          ) : null}
          <CardMeta tags={tags} publishedLabel={publishedLabel} compact />
        </div>
      </CardShell>
    );
  }

  // 纯白页面上白卡片只能靠细边框立住；浅灰页面上白卡片靠阴影浮起，加边框反而显脏。
  const surfaceClass =
    surface === "plain"
      ? "border border-[var(--chat-border)]"
      : "shadow-[var(--shadow-sm)] hover:shadow-[var(--shadow-md)]";

  return (
    <CardShell
      href={href}
      onSelect={handleSelect}
      className={`group flex h-full w-full flex-col overflow-hidden rounded-2xl bg-[var(--chat-surface)] text-left transition-[box-shadow,transform] duration-200 ease-out hover:-translate-y-0.5 active:scale-[0.99] motion-reduce:translate-none motion-reduce:active:scale-100 ${surfaceClass}`}
    >
      <div className={`relative aspect-[16/5] overflow-hidden ${PLACEHOLDER_GRADIENT}`}>
        {card.coverUrl ? (
          <img
            src={card.coverUrl}
            alt={card.title}
            className="h-full w-full object-cover transition duration-300 group-hover:scale-[1.03]"
          />
        ) : null}
      </div>

      <div className="flex flex-1 flex-col gap-3 p-5">
        <div className="space-y-2">
          <div className="flex items-start justify-between gap-2">
            <h3 className="line-clamp-2 text-ui-lg font-semibold leading-snug tracking-[-0.02em] text-[var(--chat-text)]">
              {card.title}
            </h3>
            <ArrowUpRight className="mt-0.5 h-4 w-4 shrink-0 text-[var(--chat-text-muted)] opacity-0 transition group-hover:opacity-100" />
          </div>
          {showSummary ? (
            <p className="line-clamp-3 text-ui-caption leading-6 text-[var(--chat-text-muted)]">
              {card.summary}
            </p>
          ) : null}
        </div>

        <CardMeta tags={tags} publishedLabel={publishedLabel} compact={false} />
      </div>
    </CardShell>
  );
}
