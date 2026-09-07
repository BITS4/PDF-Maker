# syntax=docker/dockerfile:1.7

# Both toolchain images are immutable. The Android image ships JDK 21, so copy the
# repository's supported JDK 17 into it instead of silently building on a newer JVM.
FROM eclipse-temurin:25-jdk-jammy@sha256:89565961a318534f01c971c7b1d030e60713c66995b887c94010cef938dbc53e AS jdk

FROM ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31 AS development

COPY --from=jdk /opt/java/openjdk /opt/java/openjdk

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /workspace

# AGP 8.13.2 selects Build Tools 35.0.0. Install that exact revision rather
# than relying on the SDK manager's moving "latest" selection.
RUN sdkmanager "build-tools;35.0.0"

COPY --chmod=755 gradlew ./gradlew
COPY gradle ./gradle
COPY build.gradle.kts gradle.properties settings.gradle.kts ./

RUN ./gradlew --version --no-daemon

COPY . .

CMD ["bash"]

FROM development AS verification

RUN ./gradlew verify --no-daemon --stacktrace

CMD ["bash", "-lc", "cp app/build/outputs/apk/debug/*.apk /tmp/pdf-maker-debug.apk && sha256sum /tmp/pdf-maker-debug.apk"]
