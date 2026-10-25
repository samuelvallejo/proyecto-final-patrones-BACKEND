# Railway deployment

The backend runs as a Java 21 Docker service. The frontend stays on Vercel, and PostgreSQL stays in the existing Supabase `streamguard` schema. This migration does not create or copy a database.

## Service configuration

Connect `samuelvallejo/proyecto-final-patrones-BACKEND`, branch `main`, with the repository root as the service root. Railway detects the existing Dockerfile. `railway.toml` gives Spring Boot up to 300 seconds to become healthy at `/actuator/health` and limits failure restarts to three attempts. Spring Boot already binds to the port supplied through `PORT`.

Before the first deployment, transfer the current private backend variables into the Railway service:

- `SPRING_PROFILES_ACTIVE=cloud`.
- `JDBC_DATABASE_URL`, `PGUSER`, and `PGPASSWORD` for the existing Supabase connection.
- The exact existing `DATA_ENCRYPTION_KEY` and `JWT_SECRET`. Replacing the encryption key would make existing encrypted account fields unreadable; changing the JWT secret would invalidate sessions.
- `FRONTEND_ORIGIN` containing the production Vercel origin and any other intentionally supported frontend origins.
- `MEDIA_STORAGE=database`, with `MEDIA_DIR` and `MEDIA_WORK_DIR` pointing to writable temporary directories. Media persisted in PostgreSQL survives container replacement.
- The existing `MAX_VIEWERS` and `JAVA_TOOL_OPTIONS` settings.
- `AI_PROVIDER`, `GEMINI_API_KEY`, `GEMINI_MODEL`, and `GEMINI_FALLBACK_MODEL` when Gemini is enabled. Preserve the Ollama gateway variables if that provider is still used.
- Existing TURN variables when a TURN server is configured.

Never store variable values, exported environment files, or API keys in Git. Keep one backend replica because signaling and relay state currently live in process memory. Choose a region near the existing database and compare latency after deployment.

## Frontend cutover

1. Generate a Railway HTTPS domain for the healthy backend and verify `/actuator/health`.
2. Set the frontend's `VITE_BACKEND_ORIGIN` to that origin. It is used for direct WebSocket and media connections.
3. Update the Vercel `/api/:path*` rewrite to the same backend's `/api/:path*`. Keeping this proxy preserves first-party HttpOnly session cookies.
4. Update the Android `BACKEND` build setting and rebuild the Android application if it is distributed.
5. Build the frontend, deploy it to the existing Vercel production project, and verify login, channel access, chat, a real Gemini answer, and live signaling/media.
6. Keep the previous Render deployment available until verification succeeds. If cutover fails, restore both the API rewrite and `VITE_BACKEND_ORIGIN` to their previous values and redeploy the frontend.

Avoid a prolonged overlap while broadcasts are active: both backend instances share the database, while each keeps its own live connection state. Schedule the cutover when no broadcast is running.

## Cost and activation status

Free and trial capacity depends on the account's remaining credits and provision limits. Do not enable a paid plan automatically. Railway's Hobby subscription has a minimum monthly charge and may charge additional resource usage; the subscription price is not a spending cap. Inspect the account's current plan, credits, and usage before deploying.

Adding this configuration does not migrate production traffic. A healthy Railway service, transferred private variables, frontend cutover, and end-to-end verification are still required.
