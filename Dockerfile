FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml pom.xml
RUN mvn -B -ntp dependency:resolve
COPY src src
RUN mvn -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/backend-1.0.0.jar /app/backend.jar
ENV MEDIA_DIR=/data/media
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:ActiveProcessorCount=1"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/backend.jar"]
