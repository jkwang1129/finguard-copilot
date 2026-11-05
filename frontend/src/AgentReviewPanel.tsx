import { useEffect, useRef, useState } from "react";
import type { AgentTrace, Api, Page, Session, ToolName } from "./api";
import { ApiError } from "./api";
const toolLabels: Record<ToolName | "OTHER", string> = {
  get_review_facts: "读取事实快照",
  get_task_state: "读取任务状态快照",
  search_review_policy: "检索政策",
  OTHER: "已拒绝的未知工具",
};
const toolStates: Record<string, string> = {
  SUCCEEDED: "已完成", CLAIMED: "执行占用已记录", REJECTED: "已拒绝", UNKNOWN: "结果不确定",
};
interface Props {
  page: Page; session: Session; client: Api;
  onUpdated: () => void; onError: (cause: unknown) => void;
}
export function AgentReviewPanel({ page, session, client, onUpdated, onError }: Props) {
  const [trace, setTrace] = useState<AgentTrace | null>(null);
  const [busy, setBusy] = useState(false);
  const [traceError, setTraceError] = useState("");
  const mounted = useRef(true), inFlight = useRef(false);
  const request = useRef<{ action: string; revision: string; key: string } | null>(null);
  const resumeAbort = useRef<AbortController | null>(null);
  const callbacks = useRef({ onError });
  useEffect(() => { callbacks.current = { onError }; }, [onError]);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; resumeAbort.current?.abort(); };
  }, []);
  const revision = page.agent?.revision;
  useEffect(() => {
    if (!client.trace || !revision) return;
    const controller = new AbortController();
    client.trace(session.token, page.analysisId, controller.signal).then((fresh) => {
      if (!controller.signal.aborted) { setTrace(fresh); setTraceError(""); }
    }).catch((cause) => {
      if (controller.signal.aborted) return;
      setTraceError("轨迹暂不可用，请稍后刷新。");
      if (cause instanceof ApiError && cause.code === "UNAUTHENTICATED") callbacks.current.onError(cause);
    });
    return () => controller.abort();
  }, [client, session.token, page.analysisId, revision]);
  if (!page.agent) return null;
  const agent = page.agent;
  async function resume(action: "CONTINUE" | "CANCEL") {
    if (!client.resume || !page.agent?.canResume || inFlight.current) return;
    inFlight.current = true; setBusy(true);
    const revision = page.agent.revision;
    if (!request.current || request.current.action !== action || request.current.revision !== revision)
      request.current = { action, revision, key: crypto.randomUUID() };
    const controller = new AbortController(); resumeAbort.current = controller;
    try {
      await client.resume(session.token, page.analysisId, revision, action, request.current.key, controller.signal);
      if (mounted.current) onUpdated();
    } catch (cause) { if (mounted.current && !controller.signal.aborted) onError(cause); }
    finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  }
  return (
    <section className="card" aria-labelledby="agent-title">
      <h2 id="agent-title">只读核查轨迹</h2>
      <p className="muted">事实来自 {new Date(agent.capturedAt).toLocaleString("zh-CN")} 捕获的快照。</p>
      <p>模型调用剩余：{2 - agent.modelCalls} / 2 · 工具执行剩余：{4 - agent.toolCalls} / 4</p>
      <p className="muted">本地 MCP 与本次分析共用工具额度；仅在等待确认或分析结束时可读取。</p>
      {!agent.replayable && <p className="hint">来源已过期或清理，只能查看轨迹摘要。</p>}
      {page.status === "WAITING_FOR_HUMAN" && (
        <div className="notice">
          <h3>需要人工确认后继续核查</h3>
          <p>待核查：{agent.plannedTools.map((name) => toolLabels[name]).join("、") || "已选定的只读证据"}。</p>
          <p>继续时会重新核对您的权限、任务状态和事实。最终决定仍在 Core 完成。</p>
          {agent.waitExpiresAt && <p className="muted">等待期限：{new Date(agent.waitExpiresAt).toLocaleString("zh-CN")}</p>}
          <div className="agent-actions">
            <button disabled={busy || !agent.canResume || !client.resume} onClick={() => void resume("CONTINUE")}>继续只读核查</button>
            <button className="secondary" disabled={busy || !agent.canResume || !client.resume} onClick={() => void resume("CANCEL")}>取消本次分析</button>
          </div>
        </div>
      )}
      {traceError && <p className="hint">{traceError}</p>}
      <ol className="timeline">
        {trace?.tools.map((tool) => <li key={tool.slot}><span>{toolLabels[tool.toolName]}</span><span>{toolStates[tool.status] || "记录不可用"}</span></li>)}
      </ol>
      {!!trace?.calls.length && <details><summary>模型调用核算</summary><ul>
        {trace.calls.map((call) => <li key={call.ordinal}>调用 {call.ordinal} · {call.status === "SETTLED" ? "已结算" : call.status === "UNKNOWN_HELD" ? "结果不确定，额度保持占用" : "核算未完成"} · {call.estimatedCost === null ? "费用未知" : call.estimatedCost + " CNY"}</li>)}
      </ul></details>}
    </section>
  );
}
