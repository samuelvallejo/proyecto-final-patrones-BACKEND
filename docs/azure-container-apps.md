# Azure Container Apps deployment

The existing Dockerfile packages Java 21, Spring Boot and FFmpeg. The container
publishing workflow builds production source from `main` and publishes immutable
commit tags plus `main` to `ghcr.io/samuelvallejo/streamguard-backend`. It uses the
short-lived repository workflow token; no Azure or database secret is needed to
build the image. The Docker build context contains only the application source
and Maven descriptor.

New GitHub packages are private by default. Before an unauthenticated Azure pull,
the owner must make this application image public in GitHub package settings.
The image contains public application code; runtime secrets must be configured
separately in Azure. Prefer deploying the digest recorded in the workflow summary
so later pushes cannot change a running revision implicitly.

## Runtime configuration

- Use a Consumption workload profile with 0.5 vCPU and 1 GiB memory.
- Use one active revision, a minimum of zero replicas and a maximum of one.
  Stream signaling and relay delivery keep process-local state; multiple replicas
  require a separate shared coordination design.
- Enable external HTTP ingress to container port 8080, automatic transport and
  HTTPS only. Both REST and WebSocket endpoints use the same origin.
- Keep `/actuator/health` as the readiness endpoint. Allow at least 180 seconds
  for startup, including database connection and Flyway validation.
- Use `SPRING_PROFILES_ACTIVE=cloud`, `MEDIA_STORAGE=database`,
  `MEDIA_DIR=/tmp/streamguard-media`, `MEDIA_WORK_DIR=/tmp/streamguard-work`,
  `PORT=8080` and `MAX_VIEWERS=6`.
- Set `FRONTEND_ORIGIN` to the exact production Vercel origin.
- Store `JDBC_DATABASE_URL`, `PGUSER`, `PGPASSWORD`, `DATA_ENCRYPTION_KEY` and
  `JWT_SECRET` as private Azure secrets and reference them from environment
  variables. Preserve the encryption key to read existing protected database
  fields. Preserve the JWT secret when moving existing sessions.
- Configure Gemini or Ollama credentials only as private runtime secrets when
  their values are available. `AI_PROVIDER=local-rules` works without an external
  model and does not provide Gemini chat responses.

## Cost and cutover

Consumption has monthly free allowances and metered charges beyond them. A
minimum of zero replicas lets the app stop when idle, introducing a cold start on
the next request. A maximum of one replica limits capacity, not total spending.
Obtain approval before consuming student credits or activating paid resources.
Avoid creating a dedicated workload profile, registry or logging workspace
unless it is specifically needed and its cost has been reviewed.

Create and check the Azure backend before changing the frontend. Then update the
Vercel API proxy and public backend origin, publish a new frontend build, and
stop the previous backend once the cutover is verified. Two active backend
instances against the same database are not a supported streaming deployment.

## Verification

Check the exact deployed image digest, healthy revision and
`/actuator/health` response. Verify first-party cookie login through the Vercel
API proxy, WebSocket signaling, normal chat, broadcast playback and clip
processing. A health response alone does not verify those application flows.

Official references: [billing](https://learn.microsoft.com/azure/container-apps/billing),
[ingress](https://learn.microsoft.com/azure/container-apps/ingress-overview),
[scaling](https://learn.microsoft.com/azure/container-apps/scale-app), and
[GitHub Container Registry](https://docs.github.com/packages/working-with-a-github-packages-registry/working-with-the-container-registry).
