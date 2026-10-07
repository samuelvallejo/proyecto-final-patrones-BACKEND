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
