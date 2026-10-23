# Gemini conversation gateway

Authenticated users can ask general questions without creating a channel. `GeminiChatController`
exposes `GET /api/assistant/chat/config` and `POST /api/assistant/chat`. The latter accepts
`message` (1-2000 characters) and `history` (up to ten user/assistant turns, 6000 characters
per turn). Combined context cannot exceed 24000 characters. The response contains only
`answer`, `provider` and `model`.

The chat uses `GeminiAdapter` independently of `AI_PROVIDER`. Moderation and editorial
analysis continue using the selected provider. Configure `GEMINI_API_KEY` privately in Render;
The chat discovers available Flash text models using Google's `models.list` endpoint, caches the
catalog for ten minutes, prefers Flash Lite models, and remembers the model that actually answered.
`GEMINI_MODEL` and `GEMINI_FALLBACK_MODEL` remain fallback choices if discovery is unavailable.
Up to three eligible models can be tried within one 45-second budget for transient errors, missing
models or model quota limits. Invalid requests and credential errors stop immediately. Gemini 3
requests use low thinking; Gemini 2.5 Flash requests disable thinking for conversational latency.
The UI receives the selected model and then the model that actually answered. Configuration status
means a key exists, not that Google has accepted it. A successful question verifies generation.

Chat uses native Gemini `user`/`model` conversation turns and plain text responses. It does not
require the model to serialize conversational prose inside a JSON object. Structured moderation
and editorial requests retain their separate schema-based gateway.

The application never stores conversations in PostgreSQL and does not log questions,
answers, provider response bodies or credentials. Google still receives the submitted context;
the free tier may use it for product improvement. Users see this notice before sending.

The gateway allows one concurrent chat request and six requests per authenticated user per
minute per instance, with expiring in-memory counters. It caps output tokens at 4096, safely bounds
the returned answer to 6000 characters, converts quota errors to a safe 429, and returns messages for
invalid requests, access failures, and unavailable models. It does not expose provider response
bodies, prompts, or credentials in logs. Gemini 3 requests omit the deprecated `temperature`
parameter. These limits reduce traffic but do not establish or verify a Google billing tier. Keep
the Google project on its free tier if costs are not authorized. There is no automatic paid
upgrade or account configuration action in this implementation.

No system roles, platform data, channel records or tools are accepted from the browser.
User-submitted history is untrusted context. There is no cross-user server conversation state.
`GeminiChatServiceTest` covers session checks, validation, isolation, concurrency, quotas,
malformed output, long replies and recovery without contacting Google. `GeminiAdapterTest` verifies
model discovery, native conversation roles, text-only output, provider fallback and invalid requests.
