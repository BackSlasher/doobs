####
# doobs build toolchain — everything lives in here, nothing on the host.
#   podman build -t doobs-build -f Containerfile .
####
FROM docker.io/library/eclipse-temurin:21-jdk-noble

# Pinned so builds are reproducible. Bump deliberately.
ARG CMDLINE_TOOLS_ZIP=commandlinetools-linux-11076708_latest.zip
ARG GRADLE_VERSION=8.11.1
ARG ANDROID_PLATFORM=android-35
ARG ANDROID_BUILD_TOOLS=35.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    GRADLE_HOME=/opt/gradle \
    GRADLE_USER_HOME=/cache/gradle \
    ANDROID_USER_HOME=/cache/android \
    JAVA_TOOL_OPTIONS=-Duser.home=/cache

ENV PATH=$GRADLE_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip zip ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

# Android command-line tools + SDK
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}" -o /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" > /dev/null

# Gradle (no wrapper jar committed to the repo; the image pins the version)
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && mv "/opt/gradle-${GRADLE_VERSION}" "$GRADLE_HOME" \
    && rm /tmp/gradle.zip

# Caches are bind-mounted volumes; the build runs as the host UID via --userns=keep-id,
# so these have to be writable by an arbitrary uid.
RUN mkdir -p /cache/gradle /cache/android /work \
    && chmod -R 0777 /cache \
    && chmod -R a+rX "$ANDROID_HOME"

WORKDIR /work
CMD ["bash"]
