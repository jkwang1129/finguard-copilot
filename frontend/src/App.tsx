import { useEffect, useRef, useState } from "react";
import { ApiError, ReviewApi } from "./api";
import type { Api, Page, Progress, Session, Status, Verdict } from "./api";
import { readProgress } from "./events";
import "./App.css";

const defaultClient = new ReviewApi();
const labels: Record<Status, string> = {
  CONTEXT_READY: "业务上下文已就绪",
  QUEUED: "等待执行",
  RETRIEVAL_READY: "检索上下文已就绪",
  GENERATING: "正在生成建议",
  VALIDATING: "正在校验结果",
  SUCCEEDED: "建议已通过校验",
  FAILED: "分析失败",
};
const recommendations = {
  CONFIRMED: "建议确认风险",
  IGNORED: "建议忽略风险",
  INSUFFICIENT_EVIDENCE: "证据不足，需补充核查",
};
const feedbackLabels: Record<Verdict, string> = {
  AGREE: "同意建议",
  DISAGREE: "不同意建议",
  UNSURE: "暂无法判断",
};
const costLabels: Record<string, string> = {
  SETTLED: "已按用量结算（估算）",
  RESERVED: "已预留预算，尚未结算",
  UNKNOWN_HELD: "调用结果未知，继续保留预算",
  RELEASED_NOT_SENT: "已确认未发送，预留预算已释放",
  NOT_RECORDED: "暂无核算记录",
  LEGACY_ESTIMATE: "历史用量估算",
};
const terminal = (status: Status) =>
  status === "SUCCEEDED" || status === "FAILED";
function coreEntry(): string | null {
  try {
    const url = new URL(
      import.meta.env.VITE_CORE_REVIEW_URL || "http://127.0.0.1:8080/demo/index.html",
    );
    return ["http:", "https:"].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : null;
  } catch {
    return null;
  }
}
const coreUrl = coreEntry();
function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, ms);
    if (signal.aborted) abort();
    else signal.addEventListener("abort", abort, { once: true });
  });
}
function App({ client = defaultClient }: { client?: Api }) {
  const [session, setSession] = useState<Session | null>(null);
  const [username, setUsername] = useState(""),
    [password, setPassword] = useState("");
  const [taskId, setTaskId] = useState(""),
    [analysisInput, setAnalysisInput] = useState("");
  const [page, setPage] = useState<Page | null>(null),
    [events, setEvents] = useState<Progress[]>([]);
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [sendingFeedback, setSendingFeedback] = useState(false);
  const [connection, setConnection] = useState("尚未连接");
  const epoch = useRef(0),
    active = useRef<AbortController | null>(null),
    cursor = useRef("0"),
    selected = useRef("");
  const creation = useRef<{ taskId: string; key: string } | null>(null);
  useEffect(
    () => () => {
      epoch.current++;
      active.current?.abort();
    },
    [],
  );
  function cancel() {
    epoch.current++;
    active.current?.abort();
    const controller = new AbortController();
    active.current = controller;
    return { controller, version: epoch.current };
  }
  function logout() {
    cancel();
    creation.current = null;
    selected.current = "";
    cursor.current = "0";
    setSession(null);
    setPassword("");
    setPage(null);
    setEvents([]);
    setTaskId("");
    setAnalysisInput("");
    setBusy(false);
    setSendingFeedback(false);
    setConnection("尚未连接");
    setError("");
  }
  function report(cause: unknown, version: number) {
    if (
      version !== epoch.current ||
      (cause instanceof DOMException && cause.name === "AbortError")
    )
      return;
    if (cause instanceof ApiError && cause.code === "UNAUTHENTICATED") {
      logout();
      setError(cause.message);
      return;
    }
    setError(
      cause instanceof ApiError
        ? cause.message
        : new ApiError("SERVICE_UNAVAILABLE").message,
    );
  }
  async function login(event: React.SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const { controller, version } = cancel();
    setBusy(true);
    setError("");
    try {
      const next = await client.login(username, password, controller.signal);
      if (version === epoch.current) setSession(next);
    } catch (cause) {
      report(cause, version);
    } finally {
      if (version === epoch.current) {
        setPassword("");
        setBusy(false);
      }
    }
  }
  async function begin(id: string, auth: Session, resume = false) {
    const { controller, version } = cancel(),
      signal = controller.signal;
    const current = () => version === epoch.current && !signal.aborted;
    if (!resume || selected.current !== id) {
      cursor.current = "0";
      setEvents([]);
    }
    selected.current = id;
    setAnalysisInput(id);
    setPage(null);
    setError("");
    setBusy(false);
    setSendingFeedback(false);
    setConnection("连接中");
    try {
      const initial = await client.view(auth.token, id, signal);
      if (!current()) return;
      setPage(initial);
      for (let attempt = 0; attempt < 6 && current(); attempt++) {
        try {
          const body = await client.progress(
            auth.token,
            id,
            cursor.current,
            signal,
          );
          if (!current()) {
            await body.cancel();
            return;
          }
          setConnection(attempt ? "进度已恢复" : "进度已连接");
          await readProgress(body, id, cursor.current, (update) => {
            if (!current()) return;
            cursor.current = update.sequence;
            setEvents((previous) => [...previous, update]);
            setPage((previous) =>
              previous
                ? {
                    ...previous,
                    status: update.status,
                    failureCode: update.failureCode,
                  }
                : previous,
            );
          });
        } catch (cause) {
          if (
            cause instanceof ApiError &&
            [
              "UNAUTHENTICATED",
              "FORBIDDEN",
              "NOT_FOUND",
              "CONFLICT",
              "INVALID_STREAM",
            ].includes(cause.code)
          )
            throw cause;
          if (!current()) return;
        }
        const latest = await client.view(auth.token, id, signal);
        if (!current()) return;
        setPage(latest);
        if (terminal(latest.status)) {
          setConnection("进度已完成");
          return;
        }
        if (attempt === 5) throw new ApiError("NETWORK");
        setConnection("连接中断，正在恢复");
        await pause(Math.min(500 * 2 ** attempt, 8000), signal);
      }
    } catch (cause) {
      if (current()) setConnection("进度连接已暂停");
      report(cause, version);
    }
  }
  async function create(event: React.SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!session) return;
    const { controller, version } = cancel();
    setBusy(true);
    setError("");
    if (!creation.current || creation.current.taskId !== taskId)
      creation.current = { taskId, key: crypto.randomUUID() };
    try {
      const accepted = await client.create(
        session.token,
        taskId,
        creation.current.key,
        controller.signal,
      );
      if (version === epoch.current) void begin(accepted.analysisId, session);
    } catch (cause) {
      report(cause, version);
    } finally {
      if (version === epoch.current) setBusy(false);
    }
  }
  async function feedback(verdict: Verdict) {
    if (!session || !page || sendingFeedback || page.feedbackVerdict) return;
    const id = page.analysisId,
      version = epoch.current,
      signal = active.current?.signal;
    setSendingFeedback(true);
    setError("");
    try {
      try {
        await client.feedback(session.token, id, verdict, signal);
      } catch (cause) {
        if (!(cause instanceof ApiError && cause.code === "CONFLICT"))
          throw cause;
      }
      const fresh = await client.view(session.token, id, signal);
      if (version === epoch.current) setPage(fresh);
    } catch (cause) {
      report(cause, version);
    } finally {
      if (version === epoch.current) setSendingFeedback(false);
    }
  }
  return (
    <main className="app-shell">
      <header className="hero">
        <div>
          <p className="eyebrow">金融风险 · 人工审核辅助</p>
          <h1>FinGuard Copilot</h1>
          <p className="summary">
            沿着证据核查每一条建议，最终决策由审核员在 Core 完成。
          </p>
        </div>
        {session && (
          <div className="identity">
            <span>{session.username} · 审核员</span>
            <button className="secondary" onClick={logout}>
              退出登录
            </button>
          </div>
        )}
      </header>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {!session ? (
        <section className="card login" aria-labelledby="login-title">
          <p className="eyebrow">审核工作台</p>
          <h2 id="login-title">登录后开始核查</h2>
          <p className="muted">
            使用 Core 的审核员账号。退出或刷新页面后需要重新登录。
          </p>
          <form onSubmit={login}>
            <label>
              用户名
              <input
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
                maxLength={100}
              />
            </label>
            <label>
              密码
              <input
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                maxLength={256}
              />
            </label>
            <button disabled={busy}>{busy ? "正在登录…" : "登录审核台"}</button>
          </form>
        </section>
      ) : (
        <div className="workspace">
          <aside>
            <section className="card">
              <h2>创建分析</h2>
              <p className="muted">填写分配给你的审核任务编号。</p>
              <form onSubmit={create}>
                <label>
                  审核任务编号
                  <input
                    inputMode="numeric"
                    value={taskId}
                    onChange={(e) => {
                      setTaskId(e.target.value);
                      creation.current = null;
                    }}
                    required
                    pattern="[1-9][0-9]{0,18}"
                    maxLength={19}
                  />
                </label>
                <button disabled={busy}>
                  {busy ? "正在提交…" : "开始分析 / 重试提交"}
                </button>
              </form>
              <p className="hint">提交超时后可重试，同一请求会复用创建凭据。</p>
              <button
                className="text-button"
                onClick={() => {
                  creation.current = null;
                  setError("");
                }}
                disabled={busy}
              >
                下次提交时创建新分析
              </button>
            </section>
            <section className="card">
              <h2>恢复已有分析</h2>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void begin(
                    analysisInput,
                    session,
                    selected.current === analysisInput,
                  );
                }}
              >
                <label>
                  分析编号
                  <input
                    value={analysisInput}
                    onChange={(e) => setAnalysisInput(e.target.value)}
                    required
                    maxLength={36}
                    placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                  />
                </label>
                <button className="secondary">加载 / 恢复进度</button>
              </form>
            </section>
            <section className="card core-entry">
              <h2>完成最终审核</h2>
              <p className="muted">
                核对建议及证据后，前往 Core 提交人工决策。
              </p>
              {coreUrl && (
                <a
                  className="button secondary"
                  href={coreUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  前往 Core 审核入口 ↗
                </a>
              )}
            </section>
          </aside>
          <div className="content">
            {!page ? (
              <section className="card empty">
                <p className="eyebrow">准备就绪</p>
                <h2>从一个审核任务开始</h2>
                <p className="muted">
                  创建或加载分析后，这里展示状态记录、证据和审核建议。
                </p>
              </section>
            ) : (
              <>
                <section className="card">
                  <div className="section-top">
                    <h2>分析概览</h2>
                    <span
                      className={
                        "badge " + (page.status === "FAILED" ? "danger" : "")
                      }
                    >
                      {labels[page.status]}
                    </span>
                  </div>
                  <dl className="metadata">
                    <div>
                      <dt>任务编号</dt>
                      <dd>{page.reviewTaskId}</dd>
                    </div>
                    <div>
                      <dt>分析编号</dt>
                      <dd className="mono">{page.analysisId}</dd>
                    </div>
                  </dl>
                  <p className="hint" role="status">
                    {connection}
                  </p>
                  {page.failureCode && (
                    <p className="error">
                      分析未生成可用建议。错误码：{page.failureCode}
                    </p>
                  )}
                  <details open>
                    <summary>状态记录 · {events.length} 条</summary>
                    <p className="hint">
                      记录分析的状态变更；各状态不代表独立的工具调用。
                    </p>
                    <ol className="timeline">
                      {events.map((item) => (
                        <li key={item.sequence}>
                          <span>
                            {labels[item.status]}
                            {item.type === "SNAPSHOT"
                              ? "（迁移时的状态快照）"
                              : ""}
                          </span>
                          <time dateTime={item.occurredAt}>
                            {new Date(item.occurredAt).toLocaleTimeString(
                              "zh-CN",
                              { hour12: false },
                            )}
                          </time>
                          <small>#{item.sequence}</small>
                        </li>
                      ))}
                    </ol>
                  </details>
                </section>
                {page.result && page.status === "SUCCEEDED" && (
                  <section
                    className="card result"
                    aria-labelledby="result-title"
                  >
                    <p className="eyebrow">经过服务端校验的建议</p>
                    <h2 id="result-title">
                      {recommendations[page.result.recommendation]}
                    </h2>
                    <p>{page.result.reviewTaskSummary}</p>
                    <h3>规则与判断</h3>
                    <p>{page.result.riskRuleExplanation}</p>
                    <p>{page.result.reasoningSummary}</p>
                    <h3>业务证据</h3>
                    <div className="table-scroll">
                      <table>
                        <thead>
                          <tr>
                            <th>交易 / 账户</th>
                            <th>精确金额</th>
                            <th>规则 / 原因</th>
                          </tr>
                        </thead>
                        <tbody>
                          {page.result.businessEvidence.map((item, i) => (
                            <tr key={i}>
                              <td>
                                <span className="mono">
                                  {item.transactionId}
                                </span>
                                <br />
                                <span className="muted mono">
                                  {item.accountId}
                                </span>
                              </td>
                              <td className="money">
                                {item.amount} {item.currency}
                              </td>
                              <td>
                                {item.ruleCode}
                                <br />
                                <span className="muted">{item.reasonCode}</span>
                                <details>
                                  <summary>来源字段</summary>
                                  {item.sourcePaths.map((path) => (
                                    <p className="mono" key={path}>
                                      {path}
                                    </p>
                                  ))}
                                </details>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <h3>政策引用</h3>
                    {page.result.policyCitations.length ? (
                      page.result.policyCitations.map((item, i) => (
                        <blockquote key={i}>
                          <strong>
                            {item.documentId} · {item.version}
                          </strong>
                          <p>{item.excerpt}</p>
                          <span className="hint mono">{item.sourcePath}</span>
                        </blockquote>
                      ))
                    ) : (
                      <p className="muted">
                        没有可用政策引用，结合限制进行核查。
                      </p>
                    )}
                    <h3>待补充信息与限制</h3>
                    <ul>
                      {[
                        ...page.result.missingInformation,
                        ...page.result.limitations,
                      ].map((text, i) => (
                        <li key={i}>{text}</li>
                      ))}
                    </ul>
                    <div className="feedback">
                      <h3>记录对建议的反馈</h3>
                      <p className="hint">
                        反馈保存后不可覆盖；最终业务决策仍在 Core 提交。
                      </p>
                      {page.feedbackVerdict ? (
                        <p className="badge">
                          已记录：{feedbackLabels[page.feedbackVerdict]}
                        </p>
                      ) : (
                        <div className="button-row">
                          {(["AGREE", "DISAGREE", "UNSURE"] as Verdict[]).map(
                            (verdict) => (
                              <button
                                className="secondary"
                                key={verdict}
                                disabled={sendingFeedback}
                                onClick={() => void feedback(verdict)}
                              >
                                {feedbackLabels[verdict]}
                              </button>
                            ),
                          )}
                        </div>
                      )}
                    </div>
                  </section>
                )}
                <section className="card">
                  <h2>费用与运行版本</h2>
                  <p className="muted">
                    费用为冻结价格计算的估算，不代表供应商账单。
                  </p>
                  <dl className="metadata">
                    <div>
                      <dt>核算状态</dt>
                      <dd>
                        {costLabels[page.cost.accountingStatus] ??
                          "核算状态待核实"}
                      </dd>
                    </div>
                    <div>
                      <dt>已结算估算</dt>
                      <dd>
                        {page.cost.estimatedCost === null
                          ? "尚无可确认金额"
                          : `${page.cost.estimatedCost} ${page.cost.currency ?? ""}`}
                      </dd>
                    </div>
                    <div>
                      <dt>占用预算</dt>
                      <dd>
                        {page.cost.heldCny === null
                          ? "未记录"
                          : `${page.cost.heldCny} CNY`}
                      </dd>
                    </div>
                    <div>
                      <dt>输入 / 输出 Token</dt>
                      <dd>
                        {page.cost.inputTokens ?? "未知"} /{" "}
                        {page.cost.outputTokens ?? "未知"}
                      </dd>
                    </div>
                  </dl>
                  {page.versions && (
                    <details>
                      <summary>查看运行版本</summary>
                      <p>
                        {page.versions.provider} / {page.versions.model}
                        {page.versions.model === "local-test-stub"
                          ? "（本地测试模型）"
                          : ""}
                      </p>
                      <dl className="hashes">
                        {Object.entries(page.versions)
                          .filter(
                            ([name]) =>
                              name.endsWith("Fingerprint") ||
                              name === "manifestSha256",
                          )
                          .map(([name, value]) => (
                            <div key={name}>
                              <dt>{name}</dt>
                              <dd className="mono">{value}</dd>
                            </div>
                          ))}
                      </dl>
                    </details>
                  )}
                  {page.traceId && (
                    <p className="hint mono">Trace：{page.traceId}</p>
                  )}
                </section>
              </>
            )}
          </div>
        </div>
      )}
      <footer>FinGuard · 证据先行，人工决策</footer>
    </main>
  );
}
export default App;
