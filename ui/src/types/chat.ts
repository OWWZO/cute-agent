declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace CHAT {
    /**
     * 单轮 run 的生命周期状态，与后端执行账本的终态一一对应。
     *
     * `UNKNOWN` 是前端兜底：后端若出现未枚举的新状态，归入这里以保持
     * 「非运行、非等待、非终态」的既有比较语义，同时避免把任意字符串当成契约。
     */
    export type RunStatus =
      | "RUNNING"
      | "SUCCESS"
      | "FAILED"
      | "STOPPED"
      | "TIMEOUT"
      | "WAITING_INPUT"
      | "UNKNOWN";

    export type ChatItem = CuteType.Merge<
      Pick<MESSAGE.Question, "sessionId" | "query" | "requestId">,
      {
        files: TFile[];
        generatedFiles?: TFile[];
        plan?: MESSAGE.Plan;
        forceStop: boolean;
        tip?: string;
        multiAgent: MESSAGE.MultiAgent;
        /**
         * `multiAgent.tasks` 的内容签名，由 `combineData` 每次合并后刷新。
         *
         * 任务事实是**原地**改写的（`tasks.push`），引用比对发现不了变化；
         * 派生层（`handleTaskData`）靠这个签名判断是否需要重建 `tasks`。
         */
        taskFactsSignature?: string;
        agentType?: MESSAGE.ResultMap["agentType"];
        conclusion?: Task;
        responseType?: string;
        loading: boolean;
        tasks: Task[][];
        thought?: string;
        response?: string;
        taskStatus?: MESSAGE.MsgItem["taskStatus"];
        planList?: PlanItem[];
        timeline?: TimelineEntry[];
        metrics?: {
          status?: RunStatus;
        };
        /** 上下文占用（SSE context_usage） */
        contextUsage?: ContextUsage;
        startedAt?: string;
        finishedAt?: string;
        runDurationMs?: number;
        runTimingSource?: MESSAGE.ReplayTiming["source"];
        /** 当前 chat 是否已经用单 run replay 补齐过过程事件。 */
        replayLoaded?: boolean;
        /** 后端是否允许从该摘要进入单 run replay。 */
        replayAvailable?: boolean;
        /** 后台任务唤醒轮：不渲染用户气泡，只展示「后台任务已完成」系统条。 */
        backgroundWake?: boolean;
      }
    >;

    export type ContextUsage = {
      max: number;
      promptTokens?: number;
    };

    export type TimelineEntry = {
      seq: number;
      type: string;
      subType?: string;
      area: string;
      title: string;
      content?: string;
      taskId?: string;
      taskOrder?: number;
      messageIdExt?: string;
      isFinal: boolean;
      status?: string;
      payload?: Record<string, unknown>;
    };

    type PlanItem = {
      name: string;
      list: string[];
    };

    export type TFile = {
      name: string;
      url: string;
      type: string;
      size: number;
      requestId?: string;
      artifactRole?: string;
      previewUrl?: string;
      downloadUrl?: string;
      missing?: boolean;
      missingReason?: string;
      resourceKey?: string;
      mimeType?: string | null;
      originFileName?: string;
      relativePath?: string;
    };

    export type TInputInfo = {
      files?: TFile[];
      message: string;
      outputStyle?: string;
      deepThink: boolean;
      /** 本轮模型 modelId 或上游 modelName；空=后端默认 */
      model?: string;
      /** 深度思考（本轮） */
      thinking?: boolean;
      /** low | medium | high */
      thinkingEffort?: string;
      /** 本轮强制进入 plan mode（PlanExecute 会话的计划按钮） */
      forcePlanMode?: boolean;
    };

    export type Task = CuteType.Merge<
      MESSAGE.Task,
      {
        resultMap: CuteType.Merge<
          MESSAGE.ResultMap,
          {
            searchResult?: CuteType.Merge<
              MESSAGE.SearchResult,
              {
                docs: MESSAGE.Doc[];
              }
            >;
            code?: string;
          }
        >;
        id: string;
        children?: Task[];
      }
    >;

    export type AgentDetailTarget = {
      tool: Task;
      chat: ChatItem;
    };

    export type OpenTaskHandler = (
      task: Task,
      chat: ChatItem,
      backTarget?: AgentDetailTarget
    ) => void;

    export type OpenFileHandler = (
      file: TFile,
      chat: ChatItem,
      backTarget?: AgentDetailTarget
    ) => void;

    export type DataChatChartItem = Record<string, unknown>;

    export type DataChatItem = {
      query: string;
      loading: boolean;
      think: string;
      chartData?: DataChatChartItem[];
      error: string;
    };

    export type DataChatEvent =
      | {
        eventType: "THINK";
        data: string;
      }
      | {
        eventType: "CHART_DATA";
        data: DataChatChartItem[];
      }
      | {
        eventType: "ERROR";
        data: string;
      }
      | {
        eventType: "READY";
        data?: unknown;
      };

    export type FileList = MESSAGE.FileInfo;

    type PlanStatus = MESSAGE.PlanStatus;

    export type Plan = MESSAGE.Plan;
    export type PlannerRound = MESSAGE.PlannerRound;

    export type Product = {
      name: string;
      img: string;
      type: string;
      placeholder: string;
      color: string;
    };

    export type ConversationHistory = {
      id: string;
      sessionId: string;
      title: string;
      productType: string;
      deepThink: boolean;
      /** 会话级模型标识（库值）；null/undefined 表示用户从未显式选择过。 */
      sessionModel?: string | null;
      /** 会话级深度思考开关（库值）；null/undefined 表示未显式选择。 */
      sessionThinking?: boolean | null;
      /** 会话级思考强度（库值）；null/undefined 表示用默认。 */
      sessionThinkingEffort?: string | null;
      createdAt: number;
      updatedAt: number;
      chatTitle: string;
      chatList: ChatItem[];
      dataChatList: DataChatItem[];
      historyNextCursor?: string | null;
      historyHasMore?: boolean;
    };

    export type ConversationSessionItem = import("@/services/agentConversation").ConversationSessionItem;
    export type ConversationHistoryDetail = import("@/services/agentConversation").ConversationHistoryDetail;
    export type ConversationHistoryPage = import("@/services/agentConversation").ConversationHistoryPage;
    export type ConversationRunSummary = import("@/services/agentConversation").ConversationRunSummary;
    export type ConversationRunReplay = import("@/services/agentConversation").ConversationRunReplay;
    export type ConversationHistoryRunDetail = import("@/services/agentConversation").ConversationHistoryRunDetail;
    export type ConversationReplayFrame = import("@/services/agentConversation").ConversationReplayFrame;
  }
}
