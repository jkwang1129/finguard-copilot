import { expect, test } from "vitest";
import { readProgress } from "./events";

const id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
function stream(text: string, size = 7) {
  const bytes = new TextEncoder().encode(text);
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (let i = 0; i < bytes.length; i += size)
        controller.enqueue(bytes.slice(i, i + size));
      controller.close();
    },
  });
}
function frame(sequence: string, status = "GENERATING") {
  return `id: ${sequence}\r\nevent: analysis-state\r\ndata: ${JSON.stringify({ analysisId: id, sequence, type: "STATE_CHANGED", status, failureCode: null, resultAvailable: status === "SUCCEEDED", occurredAt: "2026-11-02T00:00:00Z" })}\r\n\r\n`;
}
test("decodes fragmented CRLF/UTF8 and ignores duplicate sequences", async () => {
  const values: string[] = [];
  const last = await readProgress(
    stream(
      ": 中文心跳\r\n\r\n" + frame("1") + frame("1") + frame("2", "SUCCEEDED"),
      1,
    ),
    id,
    "0",
    (e) => values.push(e.sequence),
  );
  expect(values).toEqual(["1", "2"]);
  expect(last).toBe("2");
});
test("refuses gaps and a mismatched analysis without advancing the cursor", async () => {
  await expect(
    readProgress(stream(frame("3")), id, "0", () => {}),
  ).rejects.toThrow();
  await expect(
    readProgress(
      stream(frame("1")),
      "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
      "0",
      () => {},
    ),
  ).rejects.toThrow();
});
test("returns a controlled authentication error and never echoes payload text", async () => {
  const body =
    'event: stream-error\ndata: {"code":"UNAUTHENTICATED","secret":"do-not-echo"}\n\n';
  await expect(
    readProgress(stream(body), id, "0", () => {}),
  ).rejects.toMatchObject({ code: "UNAUTHENTICATED" });
});
test("accepts controlled Agent revision metadata in fragmented reconnect events", async () => {
  const text = frame("1", "WAITING_FOR_HUMAN").replace('"failureCode":null', '"agentRevision":"9007199254740993","agentNode":"TOOLS","toolNames":["search_review_policy"],"failureCode":null');
  const values: string[] = [];
  await readProgress(stream(text, 1), id, "0", (event) => values.push(event.agentRevision!));
  expect(values).toEqual(["9007199254740993"]);
  await expect(readProgress(stream(text.replace('"TOOLS"', '"uncontrolled-body"')), id, "0", () => {})).rejects.toThrow();
});
