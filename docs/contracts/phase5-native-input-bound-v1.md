# DeepSeek V4.1 native-tool input bound v1

This is a separate, opt-in contract for `https://api.deepseek.com/chat/completions`, model alias `deepseek-flash` (officially advertised DeepSeek-V4.1-Flash-1017), non-thinking text-only requests with the three frozen Phase5 tools. It does not qualify another endpoint/model, images, arbitrary schemas, thinking, prefix continuation or custom roles.

## Public encoding assumptions

The official recipe revision is `935817c66f5d6e64838f419692545f06787e485f`. Source: [V4.1 renderer](https://github.com/deepseek-ai/deepseek-recipe/blob/935817c66f5d6e64838f419692545f06787e485f/deepseek-recipe-encoding/src/v4/dsv41.rs), [shared tool instructions](https://github.com/deepseek-ai/deepseek-recipe/blob/935817c66f5d6e64838f419692545f06787e485f/deepseek-recipe-encoding/src/v4/mod.rs), and [Python-style JSON serializer](https://github.com/deepseek-ai/deepseek-recipe/blob/935817c66f5d6e64838f419692545f06787e485f/deepseek-recipe-core/src/json.rs).

Reuse the P4-reviewed V4.1 identity-normalized byte-BPE tokenizer, SHA256 `81f64d1248a68ce3663e07ab3ee48b851e5df0e32d27cb98e4c9a268151e8d99`, whose pinned [provenance](https://github.com/deepseek-ai/deepseek-recipe/blob/935817c66f5d6e64838f419692545f06787e485f/static/tokenizers/v41/README.md) remains identical. This reuses the tokenizer assumptions from phase4-3-deepseek-input-bound-v1.md; it does not reuse the old two-message framing allowance.

Ordinary text starts with at most one BPE unit per UTF8 byte; merges cannot increase that number. Explicit special ranges consume special tokens and cannot exceed the byte-based allowance. The API's private renderer and remote weights are not proven by these public files; alias/template drift is a remaining provider assumption. Returned usage must be checked against the frozen bound and output cap, with unknown/overrun held and the qualification batch stopped.

## Restricted request grammar

- First request: exactly system + user text messages, tool_choice auto.
- Second request: system + user + repair user, or system + user + assistant tool batch + the matching ordered tool messages. Maximum7 messages and4 calls. No assistant free text, reasoning, prefix flags or message-local tool schemas.
- Tool definitions come only from resource SHA256 `e0401e9bcd95bd5290d8184e89b2aa97ed164f6fc697f49b3af8780931c7d80f`. Names and flat arguments are validated by ToolRegistry; tool ids match exactly once. The only variable tool argument string is query (at most256 codepoints), and topK is1..5.
- The second request uses tool_choice none and the fixed json_object response format. No other response schema is injected outside the measured messages.
- All text leaves and keys must be valid UTF16 before strict UTF8 encoding. Unknown roles/fields, malformed arguments and unsupported model/endpoint are rejected before financial claim or HTTP.

## Bound

`upper = strictUtf8Bytes(exactSharedHttpPayload) + 8192 + 128 * messageCount + 512 * assistantToolCallCount`.

This is a conservative structural bound, not a token-estimation heuristic:

1. Every variable system/user/tool content and decoded string argument is emitted at most once. Its rendered UTF8 byte count cannot exceed its representation in the measured JSON payload. Conversation normalization merges/reorders messages without duplicating their contents.
2. The pinned renderer's fixed tool instructions, closing directive, three tool definitions (with Python comma/colon spaces) and json_object instruction fit within the8192-byte static allowance. Tool schemas are hash-pinned, so arbitrary expanding schemas cannot enter this premise.
3. Per-message role/end markers, tool_result wrappers, adjacent-message separators and assistant prefix fit within128 units per message. Thinking-mode framing is excluded by the request contract.
4. Each validated native call has a fixed short function name and at most two fixed argument names. Its DSML wrappers and formatting fit within512 additional bytes per call; string values are not XML-escaped by the pinned renderer, and topK is a single digit. The payload already includes the argument values and their JSON escaping.

The implementation must enforce this grammar rather than merely add these constants to arbitrary JSON. It rejects inputs over min(32000, configured input limit) and outputs over2000. The bound version and recipe/tokenizer/schema identities must be frozen in the per-analysis model policy. The fixture keeps its separate identity and cannot target the real endpoint.

## Qualification and pricing

User authorized on2026-11-04: at most12 synthetic cases,24 requests,0.10CNY each,2.00CNY total, no automatic retries. [Official CNY pricing](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) was fetched on2026-11-04: reserve at peak cache-miss2CNY/M input and8CNY/M output even when actual traffic is off-peak/cached. At32000/2000 this reserves at most0.08000000CNY per request. Known estimates are not a provider billing invoice.

Reviewed price version is `deepseek-flash-native-20261104-peak-miss-cny-v1`, effective2026-11-04T00:00:00Z through2026-11-05T00:00:00Z exclusive. Real preparation rejects another version, another rate or an expired window, and freezes the full price plus the intersected governance validity in the analysis policy. Existing legacy/fixture policy shapes remain unchanged. A future price refresh requires a new reviewed snapshot; explicit native input-bound configuration alone cannot authorize arbitrary cheap prices.

Runtime real-native mode remains default-denied unless the exact reviewed native-bound version is explicitly configured. Protocol qualification is a small synthetic component exercise using actual provider HTTP, shared ToolExecutor, SQL call ledger, Redis permits and final validation; it does not imply human quality, real embedding quality or new production/Core API deployment acceptance.
