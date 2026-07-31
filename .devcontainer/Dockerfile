# SPDX-License-Identifier: GPL-3.0-or-later
#
# Podsilo development container.
#
# Deliberately built from plain `ubuntu:24.04` with distro packages only — no
# vendor-specific base image, no devcontainer "features", no third-party apt
# repositories. The only thing fetched from outside Ubuntu's archive is Google's
# Android command-line tools, which have no distro package.
#
# The Android SDK itself is NOT baked into this image. `post-create.sh` installs
# the SDK components into $ANDROID_HOME, which devcontainer.json backs with a
# named volume so a rebuild does not re-download gigabytes.

FROM ubuntu:24.04

# Host UID/GID so bind-mounted workspace files are not root-owned.
# KVM_GID must match `getent group kvm` on the WSL2 host for the in-container
# emulator (Tier 2) to be able to open /dev/kvm.
ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000
ARG KVM_GID=993
# Must equal `getent group docker | cut -d: -f3` on the WSL2 host, so the
# bind-mounted /var/run/docker.sock is readable by the container user.
# 109 on this host — NOT the 999 that Ubuntu's docker.io package picks inside the
# image, which is exactly why the user setup below groupmods it.
ARG DOCKER_GID=109

# Android command-line tools build number. Bump deliberately; keep it in sync
# with the platform-tools/adb version documented for Tier 3 (adb over TCP to a
# Windows-hosted emulator) — mismatched adb versions fail the handshake in
# confusing ways.
ARG CMDLINE_TOOLS_BUILD=13114758

ENV DEBIAN_FRONTEND=noninteractive

# C.UTF-8 rather than a generated locale: it is built into glibc, needs no
# `locales` package, and the naming/sanitisation tests depend on a UTF-8
# default charset (umlauts, CJK, NFC normalisation).
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# --- Toolchain and everyday CLI ------------------------------------------------
# openjdk-17-jdk (not -headless): AGP crunches PNGs through java.awt/ImageIO.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      openjdk-17-jdk \
      ca-certificates \
      curl \
      git \
      openssh-client \
      unzip \
      zip \
      ripgrep \
      jq \
      less \
      procps \
      sudo \
      tzdata \
 && rm -rf /var/lib/apt/lists/*

# --- Android emulator runtime libraries ---------------------------------------
# Only needed for Tier 2 (headless emulator inside the container). Safe to drop
# this layer if you only ever use Tier 1 (JVM tests) and Tier 3 (emulator on the
# Windows host). Note noble renamed libasound2 -> libasound2t64.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libasound2t64 \
      libpulse0 \
      libdbus-1-3 \
      libnss3 \
      libgl1 \
      libglu1-mesa \
      libice6 \
      libsm6 \
      libx11-6 \
      libxcb1 \
      libxcb-cursor0 \
      libxcb-icccm4 \
      libxcb-image0 \
      libxcb-keysyms1 \
      libxcb-render-util0 \
      libxcb-shape0 \
      libxcb-xkb1 \
      libxcursor1 \
      libxi6 \
      libxkbcommon0 \
      libxkbcommon-x11-0 \
      libxkbfile1 \
      libxrandr2 \
      libxtst6 \
      fontconfig \
      libfontconfig1 \
      libfreetype6 \
 && rm -rf /var/lib/apt/lists/*

# --- Docker CLI (docker-outside-of-docker) -------------------------------------
# For the disposable opodsync test sync server (CLAUDE.md §4). devcontainer.json
# bind-mounts the HOST's /var/run/docker.sock, so containers started from in here
# are SIBLINGS on the host's daemon, not children — no daemon runs in this image.
#
# `docker.io` is Ubuntu's combined daemon+CLI package (there is no separate
# docker-cli in noble). The daemon it ships is simply never started; only the
# client binary is used. Both packages come from Ubuntu's own archive, keeping
# this image free of third-party apt repositories as the header promises.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      docker.io \
      docker-compose-v2 \
 && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# --- Non-root user -------------------------------------------------------------
# ubuntu:24.04 ships a stock `ubuntu` user at UID 1000, which collides with the
# usual host UID; remove it before creating ours.
RUN if getent passwd "${USER_UID}" >/dev/null; then \
      userdel -r "$(getent passwd "${USER_UID}" | cut -d: -f1)" 2>/dev/null || true; \
    fi \
 && if ! getent group "${USER_GID}" >/dev/null; then groupadd -g "${USER_GID}" "${USERNAME}"; fi \
 && useradd -m -u "${USER_UID}" -g "${USER_GID}" -s /bin/bash "${USERNAME}" \
 && echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/${USERNAME}" \
 && chmod 0440 "/etc/sudoers.d/${USERNAME}" \
 && if ! getent group kvm >/dev/null; then groupadd -g "${KVM_GID}" kvm; fi \
 && usermod -aG kvm "${USERNAME}" \
 # The docker.io package already created a `docker` group with an arbitrary GID.
 # It must match the GID owning the host's /var/run/docker.sock or the bind-mounted
 # socket is unreadable — hence groupmod, not groupadd. Falls back to adding a
 # secondary group if that GID is already taken by something else in the image.
 && if getent group "${DOCKER_GID}" >/dev/null; then \
      usermod -aG "$(getent group "${DOCKER_GID}" | cut -d: -f1)" "${USERNAME}"; \
    elif getent group docker >/dev/null; then \
      groupmod -g "${DOCKER_GID}" docker && usermod -aG docker "${USERNAME}"; \
    else \
      groupadd -g "${DOCKER_GID}" docker && usermod -aG docker "${USERNAME}"; \
    fi

# --- Android command-line tools ------------------------------------------------
# Installed under /opt (part of the image) and kept separate from $ANDROID_HOME
# (a named volume), so mounting the volume never shadows sdkmanager.
ENV ANDROID_CMDLINE_TOOLS=/opt/android-cmdline-tools
RUN curl -fsSL -o /tmp/cmdline-tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip" \
 && mkdir -p "${ANDROID_CMDLINE_TOOLS}" \
 && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
 && mv /tmp/cmdline-tools/cmdline-tools "${ANDROID_CMDLINE_TOOLS}/latest" \
 && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools

# --- Persisted directories -----------------------------------------------------
# Created here (owned by the dev user) so that when Docker initialises the named
# volumes from the image they inherit the right ownership.
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_USER_HOME=/home/${USERNAME}/.android \
    GRADLE_USER_HOME=/home/${USERNAME}/.gradle \
    CLAUDE_CONFIG_DIR=/home/${USERNAME}/.claude
RUN mkdir -p "${ANDROID_HOME}" "${ANDROID_USER_HOME}" "${GRADLE_USER_HOME}" "${CLAUDE_CONFIG_DIR}" \
 && chown -R "${USER_UID}:${USER_GID}" \
      "${ANDROID_HOME}" "${ANDROID_USER_HOME}" "${GRADLE_USER_HOME}" "${CLAUDE_CONFIG_DIR}"

ENV PATH=/home/${USERNAME}/.local/bin:${ANDROID_CMDLINE_TOOLS}/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${PATH}

# Gradle runs via ./gradlew only — no system Gradle is installed on purpose.
USER ${USERNAME}

# --- Claude Code ---------------------------------------------------------------
# Anthropic's native installer: a checksum-verified static binary. No Node.js, no
# npm, no third-party apt repository — which is why it is preferred here over
# `npm i -g @anthropic-ai/claude-code` (Ubuntu 24.04 only ships Node 18).
#
# Everything lands under $HOME, and deliberately in two separate places:
#   ~/.local/share/claude/versions/<v>  binary (+ ~/.local/bin/claude symlink)
#   ~/.local/state/claude, ~/.cache/claude
#   $CLAUDE_CONFIG_DIR (~/.claude)      credentials, settings, project history
# Only the last one is volume-mounted, so a login survives an image rebuild while
# the mount can never shadow the installed binary.
#
# Runs as the dev user on purpose: the installer refuses sudo and installs into
# whichever HOME it sees. Set to `latest`, a pinned `2.1.220`-style version, or
# `skip` to build without Claude Code; it self-updates at runtime regardless.
ARG CLAUDE_CODE_VERSION=stable
RUN if [ "${CLAUDE_CODE_VERSION}" = "skip" ]; then \
      echo "Skipping Claude Code (CLAUDE_CODE_VERSION=skip)."; \
    else \
      { curl -fsSL https://claude.ai/install.sh | bash -s -- "${CLAUDE_CODE_VERSION}" \
        && claude --version; } \
      || { echo; \
           echo "Claude Code install failed."; \
           echo "If that was a connection timeout to downloads.claude.ai: it is an"; \
           echo "IPv4-only host (no real AAAA record), so it is unreachable from a"; \
           echo "network with no IPv4 default route — which the rest of this build"; \
           echo "survives because dl.google.com and archive.ubuntu.com are dual-stack."; \
           echo "Restore IPv4, or rebuild with --build-arg CLAUDE_CODE_VERSION=skip and"; \
           echo "install it later with: curl -fsSL https://claude.ai/install.sh | bash"; \
           exit 1; }; \
    fi

WORKDIR /workspaces/podsilo
