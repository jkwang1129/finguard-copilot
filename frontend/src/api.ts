export const states = [
  "CONTEXT_READY",
  "QUEUED",
  "RETRIEVAL_READY",
  "GENERATING",
  "VALIDATING",
  "SUCCEEDED",
  "FAILED",
  "PLANNING",
  "TOOL_RUNNING",
  "WAITING_FOR_HUMAN",
] as const;
export type Status = (typeof states)[number];
export type Verdict = "AGREE" | "DISAGREE" | "UNSURE";
export interface Session {
  token: string;
  username: string;
}
export interface Progress {
  agentRevision?: string | null;
  agentNode?: string | null;
  toolNames?: (ToolName | "OTHER")[] | null;
  analysisId: string;
  sequence: string;
  type: "SNAPSHOT" | "STATE_CHANGED";
  status: Status;
  failureCode: string | null;
  resultAvailable: boolean;
  occurredAt: string;
}
export interface Result {
  analysisId: string;
  reviewTaskSummary: string;
  riskRuleExplanation: string;
  recommendation: "CONFIRMED" | "IGNORED" | "INSUFFICIENT_EVIDENCE";
  reasoningSummary: string;
  businessEvidence: {
    transactionId: string;
    accountId: string;
    amount: string;
    currency: string;
    ruleCode: string;
    reasonCode: string;
    sourcePaths: string[];
  }[];
  policyCitations: {
    documentId: string;
    version: string;
    sourcePath: string;
    excerpt: string;
  }[];
  missingInformation: string[];
  limitations: string[];
}
export interface Page {
  schemaVersion: number;
  analysisId: string;
  reviewTaskId: string;
  status: Status;
  failureCode: string | null;
  result: Result | null;
  cost: {
    accountingStatus: string;
    estimatedCost: string | null;
    currency: string | null;
    heldCny: string | null;
    inputTokens: string | null;
    outputTokens: string | null;
    callSlotClaimed: boolean;
  };
  versions: {
    manifestSha256: string;
    modelFingerprint: string;
    promptFingerprint: string;
    indexFingerprint: string;
    priceFingerprint: string;
    provider: string;
    model: string;
  } | null;
  traceId: string | null;
  feedbackVerdict: Verdict | null;
  agent?: AgentSummary | null;
}
export type ToolName = "get_review_facts" | "get_task_state" | "search_review_policy";
export interface AgentSummary {
  workflowVersion: string;
  revision: string;
  node: string;
  waitingReason: string | null;
  waitExpiresAt: string | null;
  capturedAt: string;
  snapshotExpiresAt: string;
  modelCalls: number;
  toolCalls: number;
  plannedTools: ToolName[];
  replayable: boolean;
  canResume: boolean;
}
export interface AgentTrace {
  analysisId: string;
  revision: string;
  replayable: boolean;
  tools: { slot: number; toolName: ToolName | "OTHER"; status: string; checksum: string | null }[];
  calls: { ordinal: number; status: string; estimatedCost: string | null; inputTokens: string | null; outputTokens: string | null }[];
}
export function validateRevision(value: string): string {
  if (!/^(0|[1-9][0-9]{0,18})$/.test(value) || BigInt(value) > 9223372036854775807n)
    throw new ApiError("INVALID_REQUEST");
  return value;
}
const messages: Record<string, string> = {
  UNAUTHENTICATED: "登录已失效，请重新登录。",
  FORBIDDEN: "当前账号没有审核权限。",
  NOT_FOUND: "分析不存在或不属于当前审核员。",
  CONFLICT: "当前操作与已有记录冲突，请刷新分析。",
  INVALID_REQUEST: "请检查任务编号或分析编号。",
  SERVICE_UNAVAILABLE: "服务暂不可用，请稍后重试。",
  NETWORK: "连接中断，请重试。",
  INVALID_STREAM: "进度数据不完整，请重新连接。",
  INVALID_RESPONSE: "服务返回的数据未通过校验。",
};
export class ApiError extends Error {
  readonly code: string;
  constructor(code: string) {
    super(messages[code] ?? "分析暂不可用，请刷新或联系维护者。");
    this.code = code in messages ? code : "SERVICE_UNAVAILABLE";
  }
}
export function validateTaskId(value: string): string {
  if (!/^[1-9][0-9]{0,18}$/.test(value) || BigInt(value) > 9223372036854775807n)
    throw new ApiError("INVALID_REQUEST");
  return value;
}
export function validateAnalysisId(value: string): string {
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
      value,
    )
  )
    throw new ApiError("INVALID_REQUEST");
  return value;
}
export interface Api {
  resume?(token: string, id: string, revision: string, action: "CONTINUE" | "CANCEL", key: string, signal?: AbortSignal): Promise<void>;
  trace?(token: string, id: string, signal?: AbortSignal): Promise<AgentTrace>;
  login(
    username: string,
    password: string,
    signal?: AbortSignal,
  ): Promise<Session>;
  create(
    token: string,
    taskId: string,
    key: string,
    signal?: AbortSignal,
  ): Promise<{ analysisId: string }>;
  view(token: string, id: string, signal?: AbortSignal): Promise<Page>;
  progress(
    token: string,
    id: string,
    cursor: string,
    signal: AbortSignal,
  ): Promise<ReadableStream<Uint8Array>>;
  feedback(
    token: string,
    id: string,
    verdict: Verdict,
    signal?: AbortSignal,
  ): Promise<void>;
}
export class ReviewApi implements Api {
  private readonly fetcher: typeof fetch;
  async resume(token: string, id: string, revision: string, action: "CONTINUE" | "CANCEL", key: string, signal?: AbortSignal): Promise<void> {
    const response = await this.request("/api/copilot/review-analyses/" + validateAnalysisId(id) + "/resume", token, {
      method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": key },
      body: JSON.stringify({ expectedRevision: validateRevision(revision), action }), signal,
    });
    const receipt = await response.json();
    if (receipt.analysisId !== id || typeof receipt.revision !== "string" || !states.includes(receipt.status))
      throw new ApiError("INVALID_RESPONSE");
    validateRevision(receipt.revision);
  }
  async trace(token: string, id: string, signal?: AbortSignal): Promise<AgentTrace> {
    const trace: AgentTrace = await (await this.request(
      "/api/copilot/review-analyses/" + validateAnalysisId(id) + "/trace", token, { signal })).json();
    if (trace.analysisId !== id || typeof trace.revision !== "string" || !Array.isArray(trace.tools) || !Array.isArray(trace.calls)
      || typeof trace.replayable !== "boolean") throw new ApiError("INVALID_RESPONSE");
    validateRevision(trace.revision);
    for (const tool of trace.tools) {
      if (!Number.isInteger(tool.slot) || tool.slot < 1 || tool.slot > 4 ||
        !["get_review_facts", "get_task_state", "search_review_policy", "OTHER"].includes(tool.toolName) ||
        !["CLAIMED", "SUCCEEDED", "REJECTED", "UNKNOWN"].includes(tool.status) ||
        tool.checksum !== null && !/^[a-f0-9]{64}$/.test(tool.checksum)) throw new ApiError("INVALID_RESPONSE");
    }
    for (const call of trace.calls) {
      if (!Number.isInteger(call.ordinal) || call.ordinal < 1 || call.ordinal > 2 ||
        !["RESERVED", "SETTLED", "UNKNOWN_HELD", "RELEASED_NOT_SENT"].includes(call.status)) throw new ApiError("INVALID_RESPONSE");
      for (const field of ["estimatedCost", "inputTokens", "outputTokens"] as const) {
        const value = call[field];
        if (value !== null && (typeof value !== "string" || !/^[0-9]+(?:\.[0-9]+)?$/.test(value))) throw new ApiError("INVALID_RESPONSE");
      }
    }
    return trace;
  }
  constructor(fetcher: typeof fetch = fetch) {
    this.fetcher = fetcher.bind(globalThis);
  }
  private async request(
    path: string,
    token: string | null,
    init: RequestInit = {},
    streaming = false,
  ): Promise<Response> {
    const headers = new Headers(init.headers);
    if (token) headers.set("Authorization", `Bearer ${token}`);
    const timeout = streaming ? undefined : AbortSignal.timeout(15_000);
    const signal = timeout
      ? init.signal
        ? AbortSignal.any([init.signal, timeout])
        : timeout
      : init.signal;
    let response: Response;
    try {
      response = await this.fetcher(path, {
        ...init,
        headers,
        signal,
        credentials: "omit",
        cache: "no-store",
      });
    } catch {
      if (init.signal?.aborted) throw new DOMException("Aborted", "AbortError");
      throw new ApiError("NETWORK");
    }
    if (!response.ok)
      throw new ApiError(
        (
          {
            401: "UNAUTHENTICATED",
            403: "FORBIDDEN",
            404: "NOT_FOUND",
            409: "CONFLICT",
            400: "INVALID_REQUEST",
            503: "SERVICE_UNAVAILABLE",
          } as Record<number, string>
        )[response.status] ?? "SERVICE_UNAVAILABLE",
      );
    return response;
  }
  async login(
    username: string,
    password: string,
    signal?: AbortSignal,
  ): Promise<Session> {
    const response = await this.request("/core-api/api/auth/login", null, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
      signal,
    });
    const data = await response.json();
    if (typeof data.accessToken !== "string" || data.tokenType !== "Bearer")
      throw new ApiError("INVALID_RESPONSE");
    const identity = await (
      await this.request("/core-api/api/auth/me", data.accessToken, { signal })
    ).json();
    if (!Array.isArray(identity.roles) || !identity.roles.includes("REVIEWER"))
      throw new ApiError("FORBIDDEN");
    if (typeof identity.username !== "string")
      throw new ApiError("INVALID_RESPONSE");
    return { token: data.accessToken, username: identity.username };
  }
  async create(
    token: string,
    taskId: string,
    key: string,
    signal?: AbortSignal,
  ): Promise<{ analysisId: string }> {
    const response = await this.request("/api/copilot/review-analyses", token, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": key },
      body: '{"reviewTaskId":' + validateTaskId(taskId) + "}",
      signal,
    });
    const result = await response.json();
    validateAnalysisId(result.analysisId);
    return { analysisId: result.analysisId };
  }
  async view(token: string, id: string, signal?: AbortSignal): Promise<Page> {
    const page: Page = await (
      await this.request(
        `/api/copilot/review-analyses/${validateAnalysisId(id)}/view`,
        token,
        { signal },
      )
    ).json();
    if (
      page.schemaVersion !== 1 ||
      page.analysisId !== id ||
      !states.includes(page.status) ||
      typeof page.reviewTaskId !== "string" ||
      !page.cost ||
      (page.status === "SUCCEEDED") !== (page.result !== null)
    )
      throw new ApiError("INVALID_RESPONSE");
    validateTaskId(page.reviewTaskId);
    if (page.agent) {
      validateRevision(page.agent.revision);
      if (page.agent.workflowVersion !== "agent-v1" || !Number.isInteger(page.agent.modelCalls) ||
        page.agent.modelCalls < 0 || page.agent.modelCalls > 2 || !Number.isInteger(page.agent.toolCalls) ||
        page.agent.toolCalls < 0 || page.agent.toolCalls > 4 || !Array.isArray(page.agent.plannedTools) ||
        !page.agent.plannedTools.every((tool) => ["get_review_facts", "get_task_state", "search_review_policy"].includes(tool)) ||
        !Number.isFinite(Date.parse(page.agent.capturedAt)) || !Number.isFinite(Date.parse(page.agent.snapshotExpiresAt)) ||
        typeof page.agent.canResume !== "boolean") throw new ApiError("INVALID_RESPONSE");
    }
    for (const key of [
      "estimatedCost",
      "heldCny",
      "inputTokens",
      "outputTokens",
    ] as const) {
      const value = page.cost[key];
      if (
        value !== null &&
        (typeof value !== "string" || !/^[0-9]+(?:\.[0-9]+)?$/.test(value))
      )
        throw new ApiError("INVALID_RESPONSE");
    }
    if (page.result) {
      if (
        page.result.analysisId !== id ||
        !Array.isArray(page.result.businessEvidence)
      )
        throw new ApiError("INVALID_RESPONSE");
      for (const evidence of page.result.businessEvidence) {
        validateTaskId(evidence.transactionId);
        validateTaskId(evidence.accountId);
        if (
          typeof evidence.amount !== "string" ||
          !/^[0-9]+(?:\.[0-9]+)?$/.test(evidence.amount)
        )
          throw new ApiError("INVALID_RESPONSE");
      }
    }
    return page;
  }
  async progress(
    token: string,
    id: string,
    cursor: string,
    signal: AbortSignal,
  ): Promise<ReadableStream<Uint8Array>> {
    const response = await this.request(
      `/api/copilot/review-analyses/${validateAnalysisId(id)}/events`,
      token,
      {
        headers: { Accept: "text/event-stream", "Last-Event-ID": cursor },
        signal,
      },
      true,
    );
    if (
      !response.headers.get("content-type")?.startsWith("text/event-stream") ||
      !response.body
    )
      throw new ApiError("INVALID_STREAM");
    return response.body;
  }
  async feedback(
    token: string,
    id: string,
    verdict: Verdict,
    signal?: AbortSignal,
  ): Promise<void> {
    await this.request(
      `/api/copilot/review-analyses/${validateAnalysisId(id)}/feedback`,
      token,
      {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ verdict }),
        signal,
      },
    );
  }
}
