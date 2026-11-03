import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import type { Api, Page } from "./api";
import { ApiError } from "./api";

import App from "./App";
afterEach(cleanup);
const id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const page: Page = {
  schemaVersion: 1,
  analysisId: id,
  reviewTaskId: "9007199254740993",
  status: "SUCCEEDED",
  failureCode: null,
  result: {
    analysisId: id,
    reviewTaskSummary: "<img src=x onerror=alert(1)>",
    riskRuleExplanation: "规则解释",
    recommendation: "INSUFFICIENT_EVIDENCE",
    reasoningSummary: "缺少必要材料",
    businessEvidence: [
      {
        transactionId: "9007199254740993",
        accountId: "1",
        amount: "99.999999999999999999",
        currency: "CNY",
        ruleCode: "LARGE_AMOUNT",
        reasonCode: "THRESHOLD",
        sourcePaths: ["transaction.amount"],
      },
    ],
    policyCitations: [],
    missingInformation: ["需核查豁免"],
    limitations: ["测试样本"],
  },
  cost: {
    accountingStatus: "UNKNOWN_HELD",
    estimatedCost: null,
    currency: "CNY",
    heldCny: "0.1",
    inputTokens: null,
    outputTokens: null,
    callSlotClaimed: true,
  },
  versions: null,
  traceId: null,
  feedbackVerdict: null,
};
function fakeApi(): Api {
  return {
    login: vi
      .fn()
      .mockResolvedValue({ token: "private.jwt.token", username: "reviewer" }),
    create: vi.fn().mockResolvedValue({ analysisId: id }),
    view: vi.fn().mockResolvedValue(page),
    progress: vi.fn().mockImplementation(
      async () =>
        new ReadableStream({
          start(c) {
            c.enqueue(
              new TextEncoder().encode(
                `id: 1\nevent: analysis-state\ndata: ${JSON.stringify({ analysisId: id, sequence: "1", type: "STATE_CHANGED", status: "SUCCEEDED", failureCode: null, resultAvailable: true, occurredAt: "2026-11-02T00:00:00Z" })}\n\n`,
              ),
            );
            c.close();
          },
        }),
    ),
    feedback: vi.fn().mockResolvedValue(undefined),
  };
}
async function login() {
  fireEvent.change(screen.getByLabelText("用户名"), {
    target: { value: "reviewer" },
  });
  fireEvent.change(screen.getByLabelText("密码"), {
    target: { value: "synthetic-password" },
  });
  fireEvent.click(screen.getByRole("button", { name: "登录审核台" }));
  await screen.findByLabelText("审核任务编号");
}

test("shows the reviewer login without displaying a JWT", () => {
  render(<App />);

  expect(
    screen.getByRole("heading", { level: 1, name: "FinGuard Copilot" }),
  ).toBeInTheDocument();
  expect(screen.getByLabelText("用户名")).toBeInTheDocument();
  expect(screen.getByLabelText("密码")).toHaveAttribute("type", "password");
  expect(
    screen.getByRole("button", { name: "登录审核台" }),
  ).toBeInTheDocument();
});
test("renders validated evidence exactly as text and records immutable feedback", async () => {
  const client = fakeApi();
  render(<App client={client} />);
  await login();
  fireEvent.change(screen.getByLabelText("审核任务编号"), {
    target: { value: page.reviewTaskId },
  });
  fireEvent.click(screen.getByRole("button", { name: "开始分析 / 重试提交" }));
  expect(await screen.findByText("证据不足，需补充核查")).toBeInTheDocument();
  expect(screen.getByText("99.999999999999999999 CNY")).toBeInTheDocument();
  expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
  expect(document.querySelector("img")).toBeNull();
  expect(document.body.textContent).not.toContain("private.jwt.token");
  expect(screen.getByText("尚无可确认金额")).toBeInTheDocument();
  await screen.findByText("进度已完成");
  vi.mocked(client.view).mockResolvedValue({
    ...page,
    feedbackVerdict: "UNSURE",
  });
  fireEvent.click(screen.getByRole("button", { name: "暂无法判断" }));
  await screen.findByText("已记录：暂无法判断");
  expect(client.feedback).toHaveBeenCalledExactlyOnceWith(
    "private.jwt.token",
    id,
    "UNSURE",
    expect.any(AbortSignal),
  );
  expect(
    screen.queryByRole("button", { name: "暂无法判断" }),
  ).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: /前往 Core/ })).toHaveAttribute(
    "href",
    "http://127.0.0.1:8080/demo/index.html",
  );
});
test("an uncertain create retry reuses its key and progress never creates a task", async () => {
  const client = fakeApi();
  vi.mocked(client.create).mockRejectedValueOnce(new ApiError("NETWORK"));
  render(<App client={client} />);
  await login();
  fireEvent.change(screen.getByLabelText("审核任务编号"), {
    target: { value: "1" },
  });
  fireEvent.click(screen.getByRole("button", { name: "开始分析 / 重试提交" }));
  await screen.findByRole("alert");
  fireEvent.click(screen.getByRole("button", { name: "开始分析 / 重试提交" }));
  await screen.findByText("进度已完成");
  const calls = vi.mocked(client.create).mock.calls;
  expect(calls).toHaveLength(2);
  expect(calls[0][2]).toBe(calls[1][2]);
  fireEvent.click(screen.getByRole("button", { name: "加载 / 恢复进度" }));
  await screen.findByText("进度已完成");
  expect(client.create).toHaveBeenCalledTimes(2);
  expect(vi.mocked(client.progress).mock.calls.at(-1)?.[2]).toBe("1");
});
test("logout fences an older view response and clears the session", async () => {
  const client = fakeApi();
  let finish!: (page: Page) => void;
  vi.mocked(client.view).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  render(<App client={client} />);
  await login();
  fireEvent.change(screen.getByLabelText("分析编号"), {
    target: { value: id },
  });
  fireEvent.click(screen.getByRole("button", { name: "加载 / 恢复进度" }));
  await waitFor(() => expect(client.view).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByRole("button", { name: "退出登录" }));
  await act(async () => finish(page));
  expect(
    screen.getByRole("button", { name: "登录审核台" }),
  ).toBeInTheDocument();
  expect(
    screen.queryByText("99.999999999999999999 CNY"),
  ).not.toBeInTheDocument();
  expect(client.progress).not.toHaveBeenCalled();
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});
test("an expired session clears the result and returns to login", async () => {
  const client = fakeApi();
  vi.mocked(client.view).mockRejectedValue(new ApiError("UNAUTHENTICATED"));
  render(<App client={client} />);
  await login();
  fireEvent.change(screen.getByLabelText("分析编号"), {
    target: { value: id },
  });
  fireEvent.click(screen.getByRole("button", { name: "加载 / 恢复进度" }));
  await screen.findByRole("button", { name: "登录审核台" });
  expect(screen.getByRole("alert")).toHaveTextContent("登录已失效");
  expect(screen.queryByText("分析概览")).not.toBeInTheDocument();
});
