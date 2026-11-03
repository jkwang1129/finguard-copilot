import { expect, test, vi } from "vitest";
import { ReviewApi, validateTaskId } from "./api";

test("creation sends int64 digits exactly and keeps auth outside the URL", async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValue(
      new Response(
        '{"analysisId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","status":"CONTEXT_READY","replayed":false}',
        { status: 202 },
      ),
    );
  const api = new ReviewApi(fetcher);
  await api.create("private.jwt.token", "9007199254740993", "same-request-key");
  expect(fetcher.mock.calls[0][0]).toBe("/api/copilot/review-analyses");
  const request = fetcher.mock.calls[0][1];
  expect(request.body).toBe('{"reviewTaskId":9007199254740993}');
  expect(new Headers(request.headers).get("Authorization")).toBe(
    "Bearer private.jwt.token",
  );
  expect(new Headers(request.headers).get("Idempotency-Key")).toBe(
    "same-request-key",
  );
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});
test("invalid and overflowing task IDs never become JSON numbers", () => {
  for (const value of [
    "1e3",
    "1.2",
    "0",
    "-1",
    "9223372036854775808",
    '1,"secret":1',
  ])
    expect(() => validateTaskId(value)).toThrow();
});
test("calls browser fetch with its Window receiver", async () => {
  const browserFetch = vi.fn(function (this: typeof globalThis) {
    if (this !== globalThis) throw new TypeError("Illegal invocation");
    return Promise.resolve(
      new Response('{"analysisId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}', {
        status: 202,
      }),
    );
  });
  await expect(
    new ReviewApi(browserFetch).create("token", "1", "key"),
  ).resolves.toMatchObject({
    analysisId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  });
});
