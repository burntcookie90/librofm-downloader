FROM eclipse-temurin:21-alpine AS build
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dkotlin.incremental=true -Dorg.gradle.parallel=true -Dorg.gradle.caching=true"
WORKDIR /app

# Copy only necessary files first to leverage caching
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle

# Ensure gradlew is executable
RUN chmod +x gradlew

RUN ./gradlew --version

# Download dependencies first to leverage caching
RUN --mount=type=cache,target=/root/.gradle ./gradlew dependencies --no-daemon --stacktrace

# Copy source code after dependencies are cached
COPY server ./server

# Build the project efficiently
RUN --mount=type=cache,target=/root/.gradle ./gradlew :server:app:installDist --no-daemon

# Use a minimal runtime image
FROM eclipse-temurin:21-jre-alpine AS runtime
LABEL maintainer="Vishnu Rajeevan <github@vishnu.email>"

RUN apk add --no-cache \
      bash \
      curl \
      ffmpeg \
      tini \
 && rm -rf /var/cache/* \
 && mkdir /var/cache/apk

ENV \
    JAVA_OPTS="-Xmx4G" \
    LIBRO_FM_USERNAME="" \
    LIBRO_FM_PASSWORD="" \
    DRY_RUN="false" \
    LOG_LEVEL="NONE" \
    FORMAT="M4B_MP3_FALLBACK" \
    RENAME_CHAPTERS="false" \
    WRITE_TITLE_TAG="false" \
    LIMIT="-1" \
    SYNC_INTERVAL="d" \
    PARALLEL_COUNT=1 \
    PATH_PATTERN="FIRST_AUTHOR/BOOK_TITLE" \
    HEALTHCHECK_ID="" \
    HEALTHCHECK_HOST="https://hc-ping.com" \
    HARDCOVER_TOKEN="" \
    HARDCOVER_SYNC_MODE="ALL"

WORKDIR /app
COPY scripts/run.sh ./
COPY --from=build /app/server/app/build/install/app ./

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["/app/run.sh"]
