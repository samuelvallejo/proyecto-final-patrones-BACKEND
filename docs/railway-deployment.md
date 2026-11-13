# Railway deployment

Production runs on [Railway](https://railway.com) in one project with two services:

- **streamguard-backend**: this repository (`main`, repository root), built from the `Dockerfile` (Java 21 and FFmpeg).
- **Postgres**: Railway's PostgreSQL database. The backend reaches it over Railway's private network.

The frontend stays on Vercel and proxies `/api` to the backend's public Railway domain.

## Service configuration

`railway.toml` builds the `Dockerfile`, gives Spring Boot up to 300 seconds to become healthy at `/actuator/health` and limits failure restarts to three attempts. Spring Boot binds to the port supplied through `PORT`.

Set these variables on the backend service. The `${{Postgres.*}}` values are Railway references to the database service, so no database password is copied by hand:

| Variable | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `cloud` |
| `SPRING_FLYWAY_CREATE_SCHEMAS` | `true` (Flyway creates the private `streamguard` schema and runs every migration on the first start) |
| `JDBC_DATABASE_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `PGUSER` | `${{Postgres.PGUSER}}` |
| `PGPASSWORD` | `${{Postgres.PGPASSWORD}}` |
| `DATA_ENCRYPTION_KEY` | Base64 encoding of 32 random bytes. Back it up privately: losing it makes encrypted fields unreadable. |
| `JWT_SECRET` | At least 32 random characters. Changing it closes existing sessions. |
| `FRONTEND_ORIGIN` | `https://streamguard-frontend.vercel.app` |
| `MEDIA_STORAGE` | `database` |
| `MEDIA_DIR` / `MEDIA_WORK_DIR` | `/tmp/streamguard/media` and `/tmp/streamguard/work` |
| `MAX_VIEWERS` | `6` |
| `AI_PROVIDER` | `local-rules`, or `gemini` with `GEMINI_API_KEY` |

Generate the two keys outside Git, for example in PowerShell:

```powershell
$k = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($k); [Convert]::ToBase64String($k)
$j = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($j); -join ($j | % { $_.ToString('x2') })
```

Never store variable values, exported environment files or API keys in Git. Keep one backend replica because signaling and relay state live in process memory.

## Public domain and frontend

1. In the backend service, open **Settings → Networking → Generate Domain** and verify `https://<domain>/actuator/health` returns `UP`.
2. Point the Vercel `/api/:path*` rewrite in the frontend's `vercel.json` to `https://<domain>/api/:path*`. The proxy keeps the HttpOnly session cookie first-party.
3. Set the frontend's `PUBLIC_API_URL` to `https://<domain>` (used for WebSocket and media connections) and the Android `BACKEND` build setting to the same origin.
4. Redeploy the frontend and verify registration, login, chat and a live broadcast.

## Cost

Railway's trial credit is limited. Once it runs out, services stop until the account moves to a paid plan (Hobby has a monthly minimum and charges usage beyond it). Check the plan, credits and usage in the Railway dashboard; a stopped service makes the frontend report that the server is unavailable.
