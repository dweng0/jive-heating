FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web ./
RUN npm run build

FROM gradle:8-jdk17 AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew installDist --no-daemon --console=plain -x test

FROM eclipse-temurin:17-jre
RUN useradd --system --create-home --uid 10001 heating \
    && mkdir -p /data && chown heating /data
WORKDIR /app
COPY --from=build /src/build/install/home-heating/ /app/
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
