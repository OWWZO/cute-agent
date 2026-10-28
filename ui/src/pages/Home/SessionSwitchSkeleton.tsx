import { Skeleton } from "@/components/ui/skeleton";

/**
 * 切换会话时的消息区占位。
 *
 * bootstrap 返回前，若不渲染骨架屏，消息区会停留在上一个会话的内容，
 * 用户点击后看不到任何反馈，数据到了才整体替换 —— 观感上像「点了没反应」。
 * 这里用摘要壳同构的骨架：一条用户提问 + 一段结论 + 若干过程条。
 */
export function SessionSwitchSkeleton() {
  return (
    <div
      role="status"
      aria-label="正在加载会话"
      aria-busy="true"
      className="mx-auto flex h-full w-full max-w-[860px] flex-col gap-6 overflow-hidden px-1 pb-6"
    >
      {[0, 1].map((turn) => (
        <div key={turn} className="flex flex-col gap-4">
          {/* 用户提问：右侧气泡，宽度对齐真实 Message 的 max-w-[78%] */}
          <div className="mt-5 flex w-full justify-end">
            <Skeleton className="h-9 w-[78%] rounded-2xl" />
          </div>
          {/* 结论：多行文本 */}
          <div className="flex w-full max-w-[94%] flex-col gap-2">
            <Skeleton className="h-4 w-full" />
            <Skeleton className="h-4 w-11/12" />
            <Skeleton className="h-4 w-3/5" />
          </div>
          {/* 过程区：工具卡占位 */}
          <div className="flex w-full flex-col gap-2">
            <Skeleton className="h-11 w-full rounded-lg" />
            {turn === 0 ? <Skeleton className="h-11 w-full rounded-lg" /> : null}
          </div>
        </div>
      ))}
    </div>
  );
}

export default SessionSwitchSkeleton;
