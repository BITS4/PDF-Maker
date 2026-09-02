# syntax=docker/dockerfile:1.7

# Both toolchain images are immutable. The Android image ships JDK 21, so copy the
# repository's supported JDK 17 into it instead of silently building on a newer JVM.
FROM eclipse-temurin:17-jdk-jammy@sha256:400014962ad7224461f945bb1cc3d7d5a1927ce15b8245b72d9cedcda554cd2a AS jdk

FROM ghcr.io/cirruslabs/android-sdk:36@sha256:d9c965f2373f9c8cc023b207cdcc7d21508f98e6a45ab66462fe41aa9f866dea AS development

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
