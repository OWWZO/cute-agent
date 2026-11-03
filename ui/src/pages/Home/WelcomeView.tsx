import { cn } from "@/lib/utils";
import { motion } from "motion/react";
import { useEffect, useMemo, useState } from "react";
import {
  ArrowUpRight,
  AtSign,
  BarChart3,
  Briefcase,
  Building2,
  FileSearch,
  FileText,
  Link2,
  MapPin,
  Megaphone,
  Search,
  Smartphone,
  UserRound,
  UsersRound,
  type LucideIcon,
} from "lucide-react";

import amazonIcon from "@/assets/brands/amazon.png";
import googleIcon from "@/assets/brands/google.svg";
import instagramIcon from "@/assets/brands/instagram.svg";
import linkedinIcon from "@/assets/brands/linkedin.svg";
import metaAdsIcon from "@/assets/brands/meta-ads.svg";
import openaiIcon from "@/assets/brands/openai.svg";
import redditIcon from "@/assets/brands/reddit.svg";
import tiktokIcon from "@/assets/brands/tiktok.svg";
import xIcon from "@/assets/brands/x.svg";
import youtubeIcon from "@/assets/brands/youtube.svg";

import FeaturedConversationCard from "@/components/FeaturedConversationCard";
import GeneralInput from "@/components/GeneralInput";
import { AnimatedOrb } from "@/components/chat/AnimatedOrb";
import { KeyboardTypewriter } from "@/components/ai-elements/keyboard-typewriter";
import type { FeaturedConversationCard as FeaturedConversationCardModel } from "@/services/featuredConversation";
import { DURATION, DURATION_QUICK, SPRING_GENTLE, SPRING_SOFT, useMotionConfig } from "@/lib/motion";
import {
  suggestedQuestionsByProductType,
  type SuggestedQuestionIcon,
  type SuggestedQuestion,
} from "@/utils/constants";
import type { PromptSelectionRequest } from "@/utils/suggestedPrompts";

const SUGGESTION_ICON_MAP: Partial<Record<SuggestedQuestionIcon, LucideIcon>> = {
  creator: UsersRound,
  megaphone: Megaphone,
  search: Search,
  research: FileSearch,
  chart: BarChart3,
  seo: FileSearch,
  link: Link2,
  mention: AtSign,
  company: Building2,
  news: FileText,
  map: MapPin,
  person: UserRound,
  industry: Building2,
  briefcase: Briefcase,
  app: Smartphone,
};

/**
 * 平台品牌图标 —— 官方矢量资产，不属于界面语义色（DESIGN.md §11 豁免清单）。
 * 这些图标自带品牌色，所以卡片不能再套彩色方块底，否则两层颜色互相打架。
 */
const SUGGESTION_BRAND_MAP: Partial<Record<SuggestedQuestionIcon, string>> = {
  youtube: youtubeIcon,
  instagram: instagramIcon,
  tiktok: tiktokIcon,
  x: xIcon,
  reddit: redditIcon,
  amazon: amazonIcon,
  google: googleIcon,
  meta: metaAdsIcon,
  openai: openaiIcon,
  linkedin: linkedinIcon,
};

const HERO_TYPEWRITER_TEXTS = [
  "Let's build",
  "Let's create",
  "Hello! How can I help?",
  "Let's analyze",
  "Let's research",
  "Welcome back!",
  "Awaiting your instructions",
];

export default function WelcomeView(props: {
  currentConversation: CHAT.ConversationHistory;
  product: CHAT.Product;
  videoModalOpen?: string;
  featuredCards: FeaturedConversationCardModel[];
  onSelectionChange: (selection: {
    product: CHAT.Product;
    deepThink: boolean;
  }) => void;
  inputDraft?: string;
  onInputDraftChange?: (draft: string) => void;
  initialFiles?: File[];
  onInitialFilesConsumed?: (sessionId: string) => void;
  suggestedPromptSelection?: PromptSelectionRequest | null;
  onSend: (inputInfo: CHAT.TInputInfo) => void;
  onSendQuestion: (query: SuggestedQuestion) => void;
  onOpenVideo: (url: string) => void;
  onCloseVideo: () => void;
  onOpenFeaturedConversations?: () => void;
  onOpenFeaturedDetail?: (featuredId: string) => void;
}) {
  const suggestedQuestions = useMemo(
    () => suggestedQuestionsByProductType[props.product.type] ?? [],
    [props.product.type]
  );
  const categories = useMemo(
    () => Array.from(new Set(suggestedQuestions.map((item) => item.category))),
    [suggestedQuestions]
  );
  const [selectedCategory, setSelectedCategory] = useState("");
  useEffect(() => {
    if (!categories.includes(selectedCategory)) {
      setSelectedCategory(categories[0] || "");
    }
  }, [categories, selectedCategory]);
  const activeCategory = categories.includes(selectedCategory)
    ? selectedCategory
    : categories[0] || "";
  const visibleSuggestedQuestions = suggestedQuestions.filter(
    (item) => item.category === activeCategory
  );
  const hasSuggestedQuestions = suggestedQuestions.length > 0;
  const hasFeaturedCards = props.featuredCards.length > 0;
  const { reduce } = useMotionConfig();
  const softTransition = reduce
    ? { duration: DURATION.reduced }
    : { ...SPRING_SOFT };

  return (
    <div className="h-full w-full overflow-y-auto px-6 md:px-12 lg:px-16">
      <div
        className={cn(
          "mx-auto flex min-h-full w-full max-w-[1280px] flex-col items-center py-8 lg:py-10",
          hasFeaturedCards ? "justify-start" : "justify-center"
        )}
      >
        <div
          className={cn(
            "flex w-full flex-col items-center",
            // 欢迎态主视觉整体下移，避免标题和输入区过于贴近顶部。
            hasFeaturedCards ? "pt-10 md:pt-12 lg:pt-16" : "pt-12 md:pt-16 lg:pt-20"
          )}
        >
          <div className="mb-8 text-center lg:mb-10">
            <div className="orb-intro mx-auto mb-5 flex justify-center">
              <AnimatedOrb size={88} />
            </div>
            <h1 className="text-blur-intro mb-3 font-sans text-display-md font-medium leading-[1.08] tracking-[-0.02em] text-[var(--chat-text)] md:text-display-2xl lg:text-display-4xl">
              <KeyboardTypewriter
                texts={HERO_TYPEWRITER_TEXTS}
                speed={80}
                eraseSpeed={45}
                holdMs={10000}
                pauseMs={550}
              />
            </h1>
          </div>

          <motion.div
            initial={false}
            animate={{
              opacity: hasSuggestedQuestions ? 1 : 0,
              y: reduce || hasSuggestedQuestions ? 0 : -8,
            }}
            // 切换分类时推荐问题整块「让位」：弹簧让收起/展开跟手且可中断。
            transition={softTransition}
            className={cn(
              "mx-auto w-full max-w-[960px] overflow-visible",
              hasSuggestedQuestions
                ? "mb-8 pointer-events-auto lg:mb-10"
                : "mb-0 max-h-0 pointer-events-none"
            )}
          >
            <div
              className="mb-4 flex w-full min-w-0 justify-center overflow-x-auto pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
              aria-label="推荐任务分类"
            >
              <div className="inline-flex shrink-0 items-center gap-0.5 rounded-full bg-black/[0.04] p-1">
                {categories.map((category) => {
                  const active = category === activeCategory;
                  return (
                    <button
                      key={category}
                      type="button"
                      aria-pressed={active}
                      className={cn(
                        "relative shrink-0 rounded-full px-4 py-1.5 text-ui-caption font-medium transition-colors duration-200 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-[var(--color-accent-soft)]",
                        active
                          ? "text-[var(--chat-text)]"
                          : "text-[var(--chat-text-soft)] hover:text-[var(--chat-text)]"
                      )}
                      onClick={() => setSelectedCategory(category)}
                    >
                      {active ? (
                        <motion.span
                          layoutId="welcome-suggestion-category-thumb"
                          className="absolute inset-0 rounded-full bg-[var(--chat-surface)] shadow-[var(--shadow-sm)]"
                          transition={softTransition}
                          aria-hidden="true"
                        />
                      ) : null}
                      <span className="relative z-[1]">{category}</span>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* 分类切换时列表整体淡入：key 变化即重挂载，仅动透明度。 */}
            <motion.div
              key={activeCategory}
              className="grid grid-cols-1 gap-2 md:grid-cols-2"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={
                reduce ? { duration: DURATION.reduced } : DURATION_QUICK
              }
            >
              {visibleSuggestedQuestions.map((item) => {
                const brandIcon = SUGGESTION_BRAND_MAP[item.icon];
                const Icon = SUGGESTION_ICON_MAP[item.icon] || Search;
                return (
                  <button
                    key={item.id}
                    type="button"
                    className="group flex min-h-[40px] w-full min-w-0 items-center gap-2.5 rounded-lg bg-[var(--chat-surface-soft)] px-3 py-2 text-left text-ui-caption font-medium leading-5 text-[var(--chat-text)] transition-[background-color,transform] duration-100 ease-out hover:bg-black/[0.06] active:scale-[0.98] motion-reduce:active:scale-100 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-[var(--color-accent-soft)]"
                    onClick={() => props.onSendQuestion(item)}
                  >
                    {brandIcon ? (
                      <img
                        src={brandIcon}
                        alt=""
                        aria-hidden="true"
                        className="size-[15px] shrink-0 object-contain"
                      />
                    ) : (
                      <Icon
                        className="size-[15px] shrink-0 text-[var(--chat-accent)]"
                        strokeWidth={1.8}
                        aria-hidden="true"
                      />
                    )}
                    <span className="min-w-0 flex-1 truncate">{item.label}</span>
                    <ArrowUpRight
                      className="size-3.5 shrink-0 text-[var(--chat-text-soft)] opacity-0 transition-opacity duration-100 ease-out group-hover:opacity-100 group-focus-visible:opacity-100"
                      aria-hidden="true"
                    />
                  </button>
                );
              })}
            </motion.div>
          </motion.div>

          <motion.div
            initial={
              reduce
                ? { opacity: 0 }
                : {
                  opacity: 0,
                  y: 12,
                  scale: 0.98,
                }
            }
            animate={{
              opacity: 1,
              y: 0,
              scale: 1,
            }}
            // 输入区带 scale，是欢迎页的主视觉落点：弹簧让「放大就位」有分量感。
            transition={
              reduce
                ? { duration: DURATION.reduced }
                : {
                  ...SPRING_GENTLE,
                  delay: 0.08,
                }
            }
            className="mb-8 w-full max-w-[920px] lg:mb-10"
          >
            <div className="w-full">
              <GeneralInput
                key={`welcome-input-${props.currentConversation.sessionId}`}
                sessionId={props.currentConversation.sessionId}
                placeholder={props.product.placeholder}
                showBtn={true}
                size="big"
                disabled={false}
                product={props.product}
                deepThink={props.currentConversation.deepThink}
                draftValue={props.inputDraft}
                onDraftChange={props.onInputDraftChange}
                initialFiles={props.initialFiles}
                onInitialFilesConsumed={() =>
                  props.onInitialFilesConsumed?.(props.currentConversation.sessionId)
                }
                promptSelectionRequest={props.suggestedPromptSelection}
                send={props.onSend}
                onSelectionChange={props.onSelectionChange}
              />
            </div>
          </motion.div>
        </div>

        {hasFeaturedCards ? (
          <motion.section
            initial={reduce ? { opacity: 0 } : {
              opacity: 0,
              y: 10
            }}
            animate={{
              opacity: 1,
              y: 0,
            }}
            // 精品区是入场位移，用弹簧收尾：落到位置时有一点轻微回弹，
            // 比 ease-out 的「匀速停住」更接近原生 sheet 的手感。
            // reduce 时只保留淡入，不做可感知位移。
            transition={
              reduce
                ? { duration: DURATION.reduced }
                : {
                  ...SPRING_GENTLE,
                  delay: 0.12,
                }
            }
            className="mx-auto mt-4 w-full max-w-[1180px] pb-20"
          >
            <div className="mb-5 flex items-end justify-between gap-4">
              <div>
                <h2 className="text-display-sm font-semibold tracking-[-0.02em] text-[var(--chat-text)]">
                  精品对话
                </h2>
                <p className="mt-1 text-ui-caption text-[var(--chat-text-muted)]">
                  精选公开案例，点击查看完整回放
                </p>
              </div>
              <button
                type="button"
                onClick={() => props.onOpenFeaturedConversations?.()}
                className="inline-flex h-9 items-center gap-1.5 rounded-full bg-[var(--chat-surface-soft)] px-3.5 text-ui-caption font-medium text-[var(--chat-text-soft)] transition-colors hover:text-[var(--chat-text)]"
              >
                <span>查看全部</span>
                <i className="font_family icon-xinjianjiantou text-ui-xs" />
              </button>
            </div>

            {/* 精品对话始终走公共只读路由，避免和访客自己的会话状态耦合。 */}
            <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
              {props.featuredCards.map((card) => (
                <FeaturedConversationCard
                  key={card.featuredId}
                  card={card}
                  variant="grid"
                  surface="plain"
                  onSelect={props.onOpenFeaturedDetail}
                />
              ))}
            </div>
          </motion.section>
        ) : null}
      </div>
    </div>
  );
}
