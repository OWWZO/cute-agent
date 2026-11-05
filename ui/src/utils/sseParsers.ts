import { z } from "zod";
import { timelineEnvelopeToLegacy } from "@/utils/timelineItems";

/**
 * 前端 SSE 协议边界校验。
 *
 * <p>信封与 eventData 会保留未知字段供后续业务投影消费；dataChatEventSchema
 * 按 eventType 严格解析，未声明字段不会进入 dataChat 投影。</p>
 */
const answerEnvelopeSchema = z.object({
  status: z.string(),
  packageType: z.string(),
  finished: z.boolean(),
  errorMsg: z.string().nullable().optional().transform((value) => value ?? ""),
  resultMap: z.object({ eventData: z.unknown().optional() }).passthrough().optional().default({}),
}).passthrough();

const timelineEnvelopeSchema = z.object({
  eventType: z.string(),
  payload: z.object({
    eventData: z.unknown().optional(),
  }).passthrough().nullish(),
}).passthrough();

const eventDataSchema = z.object({
  messageOrder: z.number(),
  messageType: z.string(),
  messageId: z.string(),
  taskId: z.string(),
  taskOrder: z.number(),
  resultMap: z.object({ messageType: z.string().optional() }).passthrough(),
  /** 只校验数组外壳；ArtifactReference 各字段由业务消费层再次收窄。 */
  artifactRefs: z.array(z.object({}).passthrough()).optional(),
}).passthrough();

const dataChatEventSchema = z.discriminatedUnion("eventType", [
  z.object({
    eventType: z.literal("THINK"),
    data: z.string(),
  }),
  z.object({
    eventType: z.literal("CHART_DATA"),
    data: z.array(z.record(z.string(), z.unknown())),
  }),
  z.object({
    eventType: z.literal("ERROR"),
    data: z.string(),
  }),
  z.object({
    eventType: z.literal("READY"),
    data: z.unknown().optional(),
  }),
]);

/**
 * 解析结果的诚实类型：只承诺 `answerEnvelopeSchema` 校验过的字段，其余透传字段
 * 一律为 `unknown`，消费方必须先收窄再使用，避免把未校验字段当成 `MESSAGE.Answer`。
 */
export type AgentAnswerEnvelope = z.infer<typeof answerEnvelopeSchema>;

/**
 * 这里只校验 SSE 顶层协议骨架，内部 payload 继续交给业务解析层消费，
 * 避免前端跟后端富结构结果做过紧耦合。
 */
export function parseAgentAnswer(raw: unknown): AgentAnswerEnvelope {
  const candidate = raw && typeof raw === "object" && !Array.isArray(raw)
    ? timelineEnvelopeSchema.safeParse(raw)
    : null;
  const normalized = candidate?.success
    ? timelineEnvelopeToLegacy(candidate.data)
    : raw;
  return answerEnvelopeSchema.parse(normalized);
}

/**
 * SSE eventData 入口统一做一次骨架校验，确保 message/task 主键存在，
 * 后续 combineData 只处理结构合法的事件。
 */
export function parseEventData(raw: unknown): MESSAGE.EventData {
  return eventDataSchema.parse(raw) as unknown as MESSAGE.EventData;
}

export function parseDataChatEvent(raw: unknown): CHAT.DataChatEvent {
  return dataChatEventSchema.parse(raw);
}
