# Gemini conversation gateway

Authenticated users can ask general questions without creating a channel. `GeminiChatController`
exposes `GET /api/assistant/chat/config` and `POST /api/assistant/chat`. The latter accepts
`message` (1-2000 characters) and `history` (up to ten user/assistant turns, 6000 characters
per turn). Combined context cannot exceed 24000 characters. The response contains only
`answer`, `provider` and `model`.

The chat uses `GeminiAdapter` independently of `AI_PROVIDER`. Moderation and editorial
analysis continue using the selected provider. Configure `GEMINI_API_KEY` privately in Render;
`GEMINI_MODEL` defaults to `gemini-3.8-flash`. The previous `gemini-2.5-flash` model is now
available only to projects that have actively used it, so new integrations should select a
current model. Configuration status means a key exists,
not that Google has accepted it. A successful question verifies actual generation.

The application never stores conversations in PostgreSQL and does not log questions,
answers, provider response bodies or credentials. Google still receives the submitted context;
the free tier may use it for product improvement. Users see this notice before sending.

The gateway allows one concurrent chat request and six requests per authenticated user per
minute per instance, with expiring in-memory counters. It caps output tokens at 4096, validates
the returned answer, converts quota errors to a safe 429, and returns safe, specific messages for
invalid requests, access failures, and unavailable models. It does not expose provider response
bodies, prompts, or credentials in logs. Gemini 3 requests omit the deprecated `temperature`
parameter. These limits reduce traffic but do not establish or verify a Google billing tier. Keep
the Google project on its free tier if costs are not authorized. There is no automatic paid
upgrade or account configuration action in this implementation.

No system roles, platform data, channel records or tools are accepted from the browser.
User-submitted history is untrusted context. There is no cross-user server conversation state.
`GeminiChatServiceTest` covers session checks, validation, isolation, concurrency, quotas,
malformed output and recovery without contacting Google.
