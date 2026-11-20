export type TimelineDiagnosticType =
  | "duplicate_event"
  | "illegal_transition"
  | "item_id_churn"
  | "unknown_stage"
  | "replay_live_diff";

export type TimelineDiagnostic = {
  id: number;
  at: string;
  type: TimelineDiagnosticType;
  eventId?: string;
  itemId?: string;
  runId?: string;
  from?: string;
  to?: string;
  detail?: string;
};

export type TimelineDiagnosticsSnapshot = {
  events: TimelineDiagnostic[];
  counters: Record<TimelineDiagnosticType, number>;
};

const MAX_EVENTS = 120;
const enabled = Boolean(import.meta.env?.DEV);
const listeners = new Set<() => void>();
let sequence = 0;
let events: TimelineDiagnostic[] = [];
let snapshot: TimelineDiagnosticsSnapshot = {
  events,
  counters: {
    duplicate_event: 0,
    illegal_transition: 0,
    item_id_churn: 0,
    unknown_stage: 0,
    replay_live_diff: 0,
  },
};

function notify() {
  for (const listener of listeners) {
    listener();
  }
}

export function recordTimelineDiagnostic(
  type: TimelineDiagnosticType,
  detail: Omit<TimelineDiagnostic, "id" | "at" | "type"> = {}
): void {
  if (!enabled) {
    return;
  }
  const counters = {
    ...snapshot.counters,
    [type]: snapshot.counters[type] + 1,
  };
  const nextEvent: TimelineDiagnostic = {
    id: ++sequence,
    at: new Date().toISOString(),
    type,
    ...detail,
  };
  events = [...events, nextEvent].slice(-MAX_EVENTS);
  snapshot = { events, counters };
  notify();
}

export function getTimelineDiagnosticsSnapshot(): TimelineDiagnosticsSnapshot {
  return snapshot;
}

export function subscribeTimelineDiagnostics(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function resetTimelineDiagnostics(): void {
  sequence = 0;
  events = [];
  snapshot = {
    events,
    counters: {
      duplicate_event: 0,
      illegal_transition: 0,
      item_id_churn: 0,
      unknown_stage: 0,
      replay_live_diff: 0,
    },
  };
  notify();
}
