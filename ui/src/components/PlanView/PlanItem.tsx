import { formatSecondsToMinutes } from "@/utils";
import { useMemoizedFn, useSafeState } from "ahooks";
import { cn } from "@/lib/utils";
import { useEffect, useRef } from "react";
import { getStatusIcon } from "./config";

const PlanItem: CuteType.FC<{
  title?: React.ReactNode;
  status?: CHAT.PlanStatus;
}> = (props) => {
  const { title, status, className } = props;
  const [ runTime, setRunTime ] = useSafeState<number>();

  const hasTitle = Boolean(title);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const startedAtRef = useRef<number | null>(null);

  const clearTimer = useMemoizedFn(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  });

  // 用起始时间推导耗时，避免 setTimeout 累积误差；每秒只触发一次重渲染。
  const tick = useMemoizedFn(() => {
    if (startedAtRef.current === null) {
      return;
    }
    setRunTime(Math.floor((Date.now() - startedAtRef.current) / 1000));
    timerRef.current = setTimeout(tick, 1000);
  });

  useEffect(() => {
    clearTimer();
    if (status === 'in_progress' && hasTitle) {
      startedAtRef.current = Date.now();
      setRunTime(0);
      timerRef.current = setTimeout(tick, 1000);
    } else {
      startedAtRef.current = null;
      setRunTime(undefined);
    }
    return clearTimer;
  }, [status, hasTitle, clearTimer, tick, setRunTime]);

  return (
    <div
      className={cn(
        "mt-2 flex w-full items-center gap-3 rounded-xl bg-[var(--chat-surface-soft)]/80 px-3 py-2.5",
        className
      )}
    >
      {getStatusIcon(status)}
      <div className="min-w-0 flex-1">{title}</div>
      {typeof runTime === "number" && status === "in_progress" ? (
        <span className="shrink-0 text-ui-sm tabular-nums text-[var(--chat-text-muted)]">
          {formatSecondsToMinutes(runTime)}
          <span className="ml-2 text-[var(--chat-text-soft)]">执行中</span>
        </span>
      ) : null}
    </div>
  );
};

export default PlanItem;