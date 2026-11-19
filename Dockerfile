FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml pom.xml
RUN mvn -B -ntp dependency:resolve
COPY src src
RUN mvn -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV MEDIA_DIR=/data/media
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:ActiveProcessorCount=1"
COPY --from=build /workspace/target/backend-1.0.0.jar /app/backend.jar
# Faster cold starts (the free host sleeps when idle): extract the jar and record a class data
# sharing archive from a training start that stops right after the context refresh. The training
# run needs no database or real secrets; it uses a throwaway all-zero encryption key.
RUN java -Djarmode=tools -jar backend.jar extract --destination application \
 && (java -XX:ArchiveClassesAtExit=application.jsa -Dspring.context.exit=onRefresh \
      -Dspring.flyway.enabled=false -Dspring.main.lazy-initialization=false -Dapp.media-dir=/tmp/cds-media \
      -Dapp.data-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= \
      -jar application/backend.jar > /tmp/cds-training.log 2>&1 || echo "CDS training skipped") \
 && rm -rf backend.jar /tmp/cds-media
EXPOSE 8080
ENTRYPOINT ["java","-XX:SharedArchiveFile=application.jsa","-XX:TieredStopAtLevel=1","-Xlog:cds=off","-Xlog:cds+dynamic=off","-jar","application/backend.jar"]
