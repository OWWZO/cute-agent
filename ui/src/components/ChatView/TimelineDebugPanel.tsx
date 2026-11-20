import { useSyncExternalStore } from "react";

import {
  getTimelineDiagnosticsSnapshot,
  subscribeTimelineDiagnostics,
} from "@/utils/timeline/timelineDiagnostics";

const enabled =
  Boolean(import.meta.env?.DEV)
  && (
    import.meta.env?.VITE_TIMELINE_DEBUG === "1"
    || (
      typeof window !== "undefined"
      && new URLSearchParams(window.location.search).get("timelineDebug") === "1"
    )
  );

export function TimelineDebugPanel() {
  const snapshot = useSyncExternalStore(
    subscribeTimelineDiagnostics,
    getTimelineDiagnosticsSnapshot,
    getTimelineDiagnosticsSnapshot
  );

  if (!enabled) {
    return null;
  }

  return (
    <aside className="fixed bottom-4 right-4 z-[80] max-h-[55vh] w-[min(560px,calc(100vw-2rem))] overflow-auto rounded-xl border border-amber-300 bg-zinc-950/95 p-3 font-mono text-[11px] text-zinc-100 shadow-2xl">
      <div className="mb-2 flex items-center justify-between gap-3">
        <strong className="text-amber-300">Timeline diagnostics</strong>
        <span className="text-zinc-400">
          {Object.entries(snapshot.counters)
            .map(([key, value]) => `${key}:${value}`)
            .join("  ")}
        </span>
      </div>
      {snapshot.events.length ? (
        <div className="space-y-1">
          {snapshot.events.slice().reverse().map((event) => (
            <div key={event.id} className="border-t border-zinc-800 pt-1">
              <span className="text-amber-300">{event.type}</span>
              {event.itemId ? <span> item={event.itemId}</span> : null}
              {event.eventId ? <span> event={event.eventId}</span> : null}
              {event.from || event.to ? (
                <span> status={event.from || "-"}→{event.to || "-"}</span>
              ) : null}
              {event.detail ? <div className="text-zinc-400">{event.detail}</div> : null}
            </div>
          ))}
        </div>
      ) : (
        <div className="text-zinc-500">
          暂无异常。用 `?timelineDebug=1` 或 `VITE_TIMELINE_DEBUG=1` 在开发环境常驻。
        </div>
      )}
    </aside>
  );
}
