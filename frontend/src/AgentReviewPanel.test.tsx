import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import type { Api, Page } from "./api";
import { ApiError } from "./api";
import { AgentReviewPanel } from "./AgentReviewPanel";
afterEach(cleanup);
const id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const waiting = {
  schemaVersion: 1, analysisId: id, reviewTaskId: "42", status: "WAITING_FOR_HUMAN",
  failureCode: null, result: null, versions: null, traceId: null, feedbackVerdict: null,
  cost: { accountingStatus: "SETTLED", estimatedCost: "0", currency: "CNY", heldCny: "0", inputTokens: "100", outputTokens: "10", callSlotClaimed: true },
  agent: { workflowVersion: "agent-v1", revision: "2", node: "TOOLS", waitingReason: "REVIEW_BEFORE_TOOLS",
    waitExpiresAt: "2026-11-04T08:00:00Z", capturedAt: "2026-11-03T08:00:00Z",
    snapshotExpiresAt: "2026-12-03T08:00:00Z", modelCalls: 1, toolCalls: 1,
    plannedTools: ["get_task_state"], replayable: true, canResume: true },
} as Page;
function setup() {
  const resume = vi.fn().mockResolvedValue(undefined);
  const trace = vi.fn().mockResolvedValue({
    analysisId: id, revision: "2", replayable: true,
    tools: [{ slot: 1, toolName: "search_review_policy", status: "SUCCEEDED", checksum: "a".repeat(64), unvalidatedBody: "PRIVATE-MODEL-BODY" }],
    calls: [{ ordinal: 1, status: "SETTLED", estimatedCost: "0", inputTokens: "100", outputTokens: "10" }],
  });
  const client = { resume, trace } as unknown as Api;
  const updated = vi.fn(), error = vi.fn();
  render(<AgentReviewPanel page={waiting} session={{ token: "private.jwt", username: "reviewer" }}
    client={client} onUpdated={updated} onError={error} />);
  return { resume, trace, updated, error };
}
test("shows persisted tool traces and controlled human continuation without model body", async () => {
  const api = setup();
  await screen.findByText("检索政策");
  expect(screen.queryByText("PRIVATE-MODEL-BODY")).toBeNull();
  expect(screen.getByText(/模型调用剩余/)).toHaveTextContent("1");
  fireEvent.click(screen.getByRole("button", { name: "继续只读核查" }));
  await waitFor(() => expect(api.updated).toHaveBeenCalledTimes(1));
  expect(api.resume.mock.calls[0].slice(0, 4)).toEqual(["private.jwt", id, "2", "CONTINUE"]);
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});
test("double continuation clicks share one request and keep controls disabled", async () => {
  const api = setup();
  let finish: () => void = () => {};
  api.resume.mockReturnValue(new Promise<void>((resolve) => { finish = resolve; }));
  const button = screen.getByRole("button", { name: "继续只读核查" });
  fireEvent.click(button); fireEvent.click(button);
  expect(api.resume).toHaveBeenCalledTimes(1);
  expect(button).toBeDisabled();
  await act(async () => { finish(); });
  expect(api.updated).toHaveBeenCalledTimes(1);
});
test("expired identity is reported to the parent without exposing credentials", async () => {
  const api = setup();
  api.resume.mockRejectedValue(new ApiError("UNAUTHENTICATED"));
  fireEvent.click(screen.getByRole("button", { name: "取消本次分析" }));
  await waitFor(() => expect(api.error).toHaveBeenCalledTimes(1));
  expect(api.updated).not.toHaveBeenCalled();
  expect(document.body.textContent).not.toContain("private.jwt");
});
