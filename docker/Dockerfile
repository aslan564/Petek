# syntax=docker/dockerfile:1
#
# Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
# Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
# Licensed under the Apache License, Version 2.0; see LICENSE.
#
# The Pətək image (R15, Faza 12c): the platform bundle (launcher, jars, its own Java runtime, the platform's
# Playwright driver) on Playwright's official image, which already carries the matching Chromium and every system
# library it needs. Nothing is downloaded at run time. The Playwright version of the base image must equal the
# `playwright` version in gradle/libs.versions.toml, so the driver in the bundle finds its browsers in /ms-playwright.
#
# Build context: docker/ with context/<arch>/ holding an extracted bundle (docker/prepare-context.sh puts it there);
# buildx sets TARGETARCH to amd64 or arm64 per platform, so one build produces both images without emulation.
#
#   docker/prepare-context.sh app/build/distributions/petek-0.1.0-linux-x64.tar.gz amd64
#   docker build -f docker/Dockerfile -t petek docker/
#   docker run --rm -v "$PWD:/work" --env-file .env petek doctor
#   docker run --rm -v "$PWD:/work" --env-file .env petek run docs/examples/company-portal.yaml --json
#   docker run --rm -v "$PWD:/work" --env-file .env --network host petek panel --no-open   # the panel binds loopback only
#
ARG PLAYWRIGHT_VERSION=1.63.0
FROM mcr.microsoft.com/playwright:v${PLAYWRIGHT_VERSION}-noble

ARG TARGETARCH
COPY context/${TARGETARCH}/ /opt/petek/

ENV PATH="/opt/petek/bin:${PATH}" \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# The project under test is mounted here: .env, scenarios/, and evidence/ is written back to it.
WORKDIR /work
VOLUME ["/work"]
EXPOSE 7070

ENTRYPOINT ["petek"]
CMD ["--help"]
