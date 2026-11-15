# syntax=docker/dockerfile:1
# Build context: ./backend   (docker build -t bookbuddy-api backend)

# ---- build stage -----------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src

# Copy the wrapper + build scripts first so the dependency layer is cached until they change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
# Tests run in CI; the image build only packages. Remove "-x test" if you want tests in the image build.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/bookbuddy-*[0-9].jar /src/app.jar

# ---- runtime stage ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system bookbuddy \
    && useradd --system --gid bookbuddy --home-dir /app --shell /usr/sbin/nologin bookbuddy \
    && mkdir -p /app/uploads /app/models \
    && chown -R bookbuddy:bookbuddy /app

WORKDIR /app
COPY --from=build --chown=bookbuddy:bookbuddy /src/app.jar /app/app.jar

USER bookbuddy

# Paths are overridable; mount volumes over them (uploads: transient PDFs, models: ONNX model + tokenizer).
ENV PORT=8080 \
    UPLOAD_DIR=/app/uploads \
    EMBEDDING_MODEL_PATH=/app/models/model.onnx \
    EMBEDDING_TOKENIZER_PATH=/app/models/tokenizer.json \
    FORWARD_HEADERS_STRATEGY=framework \
    JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"

VOLUME ["/app/uploads", "/app/models"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${PORT}/api/v1/health" >/dev/null || exit 1

# exec form through sh so JAVA_OPTS is expanded while java stays PID 1 (receives SIGTERM for graceful shutdown)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
