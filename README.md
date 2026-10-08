# StreamGuard Backend

API REST y WebSocket de StreamGuard, desarrollada en Java 21 con Spring Boot y PostgreSQL. Incluye migraciones Flyway, autenticación, transmisión, moderación asistida por IA y persistencia de clips.

## Ejecutar localmente

Requiere Java 21 y Maven. Copia `.env.example` a `.env` y configura la conexión PostgreSQL y las variables que necesites. El perfil `cloud` usa `JDBC_DATABASE_URL`, `PGUSER` y `PGPASSWORD`.

```powershell
mvn spring-boot:run
```

La API local queda en http://localhost:8080. Para compilar el paquete ejecutable: `mvn -DskipTests package`.

## Contenedor

El `Dockerfile` compila y ejecuta el backend e incluye FFmpeg para procesar clips. Define los secretos de base de datos y del proveedor de IA en el entorno del servicio; no los guardes en el repositorio.

## Publicación actual en Render

El servicio gratuito `streamguard-backend` está conectado a la rama `codex/streamguard` del repositorio principal `Proyecto-final-patrones-de-software`. Actualmente los despliegues son manuales: después de trasladar la corrección a esa rama y subir el commit, selecciona **Manual Deploy → Deploy latest commit** en Render y espera **Deploy succeeded | Live**. Comprueba que el enlace **Source** corresponde al commit esperado. Subir cambios a este repositorio separado no actualiza por sí solo ese servicio.

Una respuesta `UP` en `/actuator/health` confirma que el proceso está sano, pero no demuestra que ejecute la versión nueva. Para verificar el chat, envía un mensaje en una transmisión de prueba y comprueba su estado `VISIBLE`, su persistencia y la recepción por WebSocket. Las caídas del proveedor de IA deben guardar la respuesta de las reglas locales con el estado `LOCAL`, admitido por PostgreSQL.
