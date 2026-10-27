# StreamGuard Backend

API REST y WebSocket de StreamGuard, desarrollada en Java 21 con Spring Boot y PostgreSQL. Incluye migraciones Flyway, autenticación, transmisión, moderación asistida por IA y persistencia de clips.

## Design patterns

The backend implements Builder, Factory Method, Abstract Factory, Adapter, Bridge,
Singleton, Prototype, Decorator and Facade. See [design-pattern evidence and workflows](docs/design-patterns.md)
for production classes, policy template endpoints, authorization and verification.

## Ejecutar localmente

Requiere Java 21 y Maven. Copia `.env.example` a `.env` y configura la conexión PostgreSQL y las variables que necesites. El perfil `cloud` usa `JDBC_DATABASE_URL`, `PGUSER` y `PGPASSWORD`. La sesión es un JWT (HS256) firmado con `JWT_SECRET` (mínimo 32 caracteres, por ejemplo `openssl rand -hex 32`); si falta, se usa uno aleatorio y cada reinicio cierra las sesiones. Dura 2 horas.

```powershell
mvn spring-boot:run
```

La API local queda en http://localhost:8080. Para compilar el paquete ejecutable: `mvn -DskipTests package`.

## Contenedor

El `Dockerfile` compila y ejecuta el backend e incluye FFmpeg para procesar clips. Define los secretos de base de datos y del proveedor de IA en el entorno del servicio; no los guardes en el repositorio.

The container publishing workflow builds `main` for GitHub Container Registry.
See [Azure Container Apps deployment](docs/azure-container-apps.md) for runtime
configuration, secrets, replica limits, costs and the migration checklist.

## Publicación y traslado a Azure

El servicio `streamguard-backend` de Railway está conectado a `main` de este repositorio. El origen publicado es `https://streamguard-backend-production.up.railway.app`. Se está preparando el traslado a Azure Container Apps con la misma imagen Java y FFmpeg; la guía [Azure Container Apps deployment](docs/azure-container-apps.md) describe los requisitos antes de cambiar el tráfico del frontend.

Una respuesta `UP` en `/actuator/health` confirma que el proceso está sano, pero no demuestra que ejecute la versión nueva. Para verificar el chat, envía un mensaje en una transmisión de prueba y comprueba su estado `VISIBLE`, su persistencia y la recepción por WebSocket. Las caídas del proveedor de IA deben guardar la respuesta de las reglas locales con el estado `LOCAL`, admitido por PostgreSQL.

## Captura de cámara y pantalla

Los permisos de cámara y micrófono se solicitan juntos en el frontend. Al compartir pantalla desde un navegador de escritorio compatible, el cliente integra la cámara en una esquina del video y envía una sola pista de video junto al audio por WebRTC o por la pasarela WSS existente. Desactivar la cámara oculta esa superposición sin interrumpir la pantalla ni el micrófono.

Chrome en Android y Safari en iOS no admiten `getDisplayMedia`; el frontend informa esa limitación y ofrece transmitir con cámara y micrófono. Compartir juegos u otras aplicaciones de Android requiere una aplicación nativa con acceso a MediaProjection; el backend por sí solo no puede habilitar esa capacidad en el navegador. El acceso físico a los dispositivos debe comprobarse también en el teléfono real, además de las pruebas automatizadas con fuentes sintéticas.

## Secure workflows update (2026-10-09)

See [secure workflows](docs/secure-workflows.md) for persistent encrypted HttpOnly sessions, local form limits, the light interface, optional location sharing and private profile recordings. The frontend API proxy must target the active backend. Configure the private DATA_ENCRYPTION_KEY before deploying Flyway V7; retain the existing JWT_SECRET during a migration to preserve existing sessions.

## Gemini chat

Authenticated question-and-answer endpoints use Gemini independently of the moderation provider. See [the conversation gateway](docs/gemini-chat.md) for privacy, input limits, rate limiting and configuration.
