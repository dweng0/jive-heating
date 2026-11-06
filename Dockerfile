# syntax=docker/dockerfile:1
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY web ./
RUN npm run build

FROM gradle:8-jdk17 AS build
WORKDIR /src
# Gradle, its libraries and its compile caches persist between builds: without this every build downloaded them all
# and compiled from cold (about 2.5 minutes). The cache is a BuildKit cache mount, so it never lands in the image.
ENV GRADLE_USER_HOME=/cache/gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
# build/ is cached too, so Kotlin compiles only what changed; the app is copied out of it into the image.
RUN --mount=type=cache,target=/cache/gradle --mount=type=cache,target=/src/.gradle --mount=type=cache,target=/src/build \
    ./gradlew installDist --no-daemon --console=plain -x test --build-cache \
    && rm -rf /out && cp -r build/install/home-heating /out

FROM eclipse-temurin:17-jre
RUN useradd --system --create-home --uid 10001 heating \
    && mkdir -p /data && chown heating /data
WORKDIR /app
COPY --from=build /out/ /app/
COPY --from=web /web/dist/ /app/web/
# Android APK for the website's download link: put it in android-dist/ (gitignored) before building.
COPY android-dist/ /app/android/
ENV HEATING_DB_PATH=/data/heating.db
ENV WEB_DIR=/app/web
ENV ANDROID_APK=/app/android/heating.apk
VOLUME /data
EXPOSE 8080
USER heating
ENTRYPOINT ["/app/bin/home-heating"]
