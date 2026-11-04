import {
  Pagination,
  PaginationContent,
  PaginationItem,
  PaginationLink,
  PaginationNext,
  PaginationPrevious,
} from "@/components/ui/pagination";
import { Spinner } from "@/components/ui/spinner";
import { EmptyState } from "@/components/ui/empty-state";
import { ArrowLeft, Sparkles } from "lucide-react";
import { Link } from "react-router-dom";

import FeaturedConversationCard from "@/components/FeaturedConversationCard";
import { ROUTES } from "@/router/routes";
import type { FeaturedConversationPage } from "@/services/featuredConversation";

type FeaturedConversationsViewProps = {
  embedded?: boolean;
  page: FeaturedConversationPage;
  loading: boolean;
  pageNo: number;
  pageSize: number;
  onPageChange: (pageNo: number) => void;
  onSelectCard?: (featuredId: string) => void;
};

const PUBLIC_CASES_LABEL = "公开案例库";
const FEATURED_CONVERSATIONS_TITLE = "精品对话";
const FEATURED_CONVERSATIONS_DESCRIPTION =
  "浏览管理员公开发布的高质量会话回放，直接查看完整过程与结论。";
const BACK_TO_HOME_LABEL = "返回首页";

export function FeaturedConversationsView(
  props: FeaturedConversationsViewProps
) {
  const hasCards = props.page.list.length > 0;
  const totalFeaturedCount = props.page.total || props.page.list.length;
  const totalPages = Math.ceil(props.page.total / props.pageSize);

  return (
    <div className="h-full w-full overflow-y-auto bg-[var(--chat-surface-soft)] text-[var(--chat-text)]">
      <div className="mx-auto w-full max-w-[1180px] px-5 pb-20 pt-10 sm:px-8 sm:pt-16">
        <div className="mb-10 flex items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="mb-3 inline-flex items-center gap-1.5 rounded-full bg-[var(--chat-surface-soft)] px-2.5 py-1 text-ui-sm text-[var(--chat-text-muted)]">
              <Sparkles className="h-3.5 w-3.5" />
              {PUBLIC_CASES_LABEL}
            </div>
            <h1 className="text-display-lg font-semibold tracking-[-0.02em] text-[var(--chat-text)] sm:text-display-2xl">
              {FEATURED_CONVERSATIONS_TITLE}
            </h1>
            <p className="mt-2 max-w-[48ch] text-ui-base leading-7 text-[var(--chat-text-muted)]">
              {FEATURED_CONVERSATIONS_DESCRIPTION}
            </p>
          </div>

          {!props.embedded ? (
            <Link
              to={ROUTES.HOME}
              target="_blank"
              rel="noreferrer"
              className="inline-flex h-9 shrink-0 items-center gap-2 rounded-full bg-[var(--chat-surface-soft)] px-3.5 text-ui-caption font-medium text-[var(--chat-text-soft)] transition-colors hover:text-[var(--chat-text)]"
            >
              <ArrowLeft className="h-4 w-4" />
              <span>{BACK_TO_HOME_LABEL}</span>
            </Link>
          ) : null}
        </div>

        {hasCards && !props.loading ? (
          <div className="mb-5 flex items-center justify-between gap-3 text-ui-sm text-[var(--chat-text-muted)]">
            <span className="tabular-nums">共 {totalFeaturedCount} 个案例</span>
            <span>点击卡片查看完整回放</span>
          </div>
        ) : null}

        {props.loading ? (
          <div className="flex min-h-[360px] items-center justify-center rounded-2xl bg-[var(--chat-surface)]">
            <Spinner className="size-8" />
          </div>
        ) : hasCards ? (
          <>
            <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
              {props.page.list.map((card) => (
                <FeaturedConversationCard
                  key={card.featuredId}
                  card={card}
                  variant="grid"
                  onSelect={props.onSelectCard}
                />
              ))}
            </div>

            {props.page.total > props.pageSize ? (
              <div className="mt-10 flex justify-center">
                <Pagination>
                  <PaginationContent>
                    {props.pageNo > 1 ? (
                      <PaginationItem>
                        <PaginationPrevious
                          href="#"
                          onClick={(event) => {
                            event.preventDefault();
                            props.onPageChange(props.pageNo - 1);
                          }}
                        />
                      </PaginationItem>
                    ) : null}
                    {Array.from({ length: totalPages }, (_, index) => index + 1).map((pageNo) => (
                      <PaginationItem key={pageNo}>
                        <PaginationLink
                          href="#"
                          isActive={pageNo === props.pageNo}
                          onClick={(event) => {
                            event.preventDefault();
                            props.onPageChange(pageNo);
                          }}
                        >
                          {pageNo}
                        </PaginationLink>
                      </PaginationItem>
                    ))}
                    {props.pageNo < totalPages ? (
                      <PaginationItem>
                        <PaginationNext
                          href="#"
                          onClick={(event) => {
                            event.preventDefault();
                            props.onPageChange(props.pageNo + 1);
                          }}
                        />
                      </PaginationItem>
                    ) : null}
                  </PaginationContent>
                </Pagination>
              </div>
            ) : null}
          </>
        ) : (
          <div className="rounded-2xl bg-[var(--chat-surface)]/70 py-20">
            <EmptyState title="暂无已发布的精品对话" />
          </div>
        )}
      </div>
    </div>
  );
}
