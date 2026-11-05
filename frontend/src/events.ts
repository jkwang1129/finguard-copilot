import { ApiError, states } from "./api";
import type { Progress } from "./api";

export async function readProgress(
  body: ReadableStream<Uint8Array>,
  expectedId: string,
  after: string,
  onState: (event: Progress) => void,
): Promise<string> {
  let cursor = BigInt(after),
    buffer = "";
  const reader = body.getReader(),
    decoder = new TextDecoder("utf-8", { fatal: true });
  try {
    while (true) {
      const part = await reader.read();
      buffer += decoder.decode(part.value, { stream: !part.done });
      if (buffer.length > 65536) throw new ApiError("INVALID_STREAM");
      let boundary: RegExpExecArray | null;
      while ((boundary = /\r?\n\r?\n/.exec(buffer)) !== null) {
        const block = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        let kind = "",
          id = "";
        const data: string[] = [];
        for (const line of block.split(/\r?\n/)) {
          if (line.startsWith(":")) continue;
          const colon = line.indexOf(":");
          if (colon < 0) continue;
          const value = line.slice(colon + 1).replace(/^ /, "");
          if (line.slice(0, colon) === "event") kind = value;
          if (line.slice(0, colon) === "id") id = value;
          if (line.slice(0, colon) === "data") data.push(value);
        }
        if (!data.length) continue;
        let event: Progress;
        try {
          event = JSON.parse(data.join("\n"));
        } catch {
          throw new ApiError("INVALID_STREAM");
        }
        if (kind === "stream-error")
          throw new ApiError((event as unknown as { code: string }).code);
        if (
          kind !== "analysis-state" ||
          !/^[1-9][0-9]{0,18}$/.test(id) ||
          event.sequence !== id ||
          event.analysisId !== expectedId ||
          !states.includes(event.status) ||
          !["SNAPSHOT", "STATE_CHANGED"].includes(event.type) ||
          event.resultAvailable !== (event.status === "SUCCEEDED") ||
          !Number.isFinite(Date.parse(event.occurredAt))
        )
          throw new ApiError("INVALID_STREAM");
        if (BigInt(id) <= cursor) continue;
        if (event.agentRevision != null && (typeof event.agentRevision !== "string" ||
          !/^(0|[1-9][0-9]{0,18})$/.test(event.agentRevision) || BigInt(event.agentRevision) > 9223372036854775807n ||
          !["RETRIEVE","PLAN","TOOLS","GENERATE","REPAIR","COMPLETE","DONE","INVALID"].includes(event.agentNode ?? "") ||
          !Array.isArray(event.toolNames) || event.toolNames.length > 4 ||
          !event.toolNames.every((tool) => ["get_review_facts","get_task_state","search_review_policy","OTHER"].includes(tool)))) throw new ApiError("INVALID_STREAM");
        if (BigInt(id) !== cursor + 1n) throw new ApiError("INVALID_STREAM");
        onState(event);
        cursor = BigInt(id);
        if (event.status === "SUCCEEDED" || event.status === "FAILED") {
          await reader.cancel();
          return cursor.toString();
        }
      }
      if (part.done) {
        if (buffer.trim()) throw new ApiError("INVALID_STREAM");
        return cursor.toString();
      }
    }
  } finally {
    reader.releaseLock();
  }
}
