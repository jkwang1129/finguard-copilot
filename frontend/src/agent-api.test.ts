import { expect, test, vi } from "vitest";
import { ReviewApi } from "./api";
const id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
test("resume preserves int64 revision and sends only action with auth outside URL", async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(
    JSON.stringify({ analysisId: id, revision: "9007199254740994", status: "PLANNING", replayed: false }), { status: 202 }));
  const api = new ReviewApi(fetcher);
  await api.resume("private.jwt", id, "9007199254740993", "CONTINUE", "same-key");
  expect(fetcher.mock.calls[0][0]).toBe("/api/copilot/review-analyses/" + id + "/resume");
  const request = fetcher.mock.calls[0][1];
  expect(JSON.parse(request.body)).toEqual({ expectedRevision: "9007199254740993", action: "CONTINUE" });
  expect(new Headers(request.headers).get("Authorization")).toBe("Bearer private.jwt");
  expect(new Headers(request.headers).get("Idempotency-Key")).toBe("same-key");
});
test("overflowing revision is rejected before any request", async () => {
  const fetcher = vi.fn();
  await expect(new ReviewApi(fetcher).resume("token", id, "9223372036854775808", "CONTINUE", "key")).rejects.toThrow();
  expect(fetcher).not.toHaveBeenCalled();
});
test("trace refuses an impossible call ordinal and nonnumeric accounting", async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ analysisId: id, revision: "3", replayable: true, tools: [],
    calls: [{ ordinal: 3, status: "SETTLED", estimatedCost: "private-body", inputTokens: "1", outputTokens: "1" }] })));
  await expect(new ReviewApi(fetcher).trace("token", id)).rejects.toThrow();
});
