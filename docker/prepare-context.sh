#!/bin/sh
#
# Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
# Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
#
# Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
# compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
# Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
#
# Puts a platform bundle where docker/Dockerfile copies it from: docker/context/<arch>/ with bin/, lib/, runtime/ ...
# at its root (the archive's single top-level directory is stripped).
#
#   docker/prepare-context.sh app/build/distributions/petek-0.1.0-linux-x64.tar.gz amd64
#   docker/prepare-context.sh app/build/distributions/petek-0.1.0-linux-arm64.tar.gz arm64

set -eu

archive="${1:?usage: prepare-context.sh <petek-<version>-linux-*.tar.gz> <amd64|arm64>}"
arch="${2:?usage: prepare-context.sh <petek-<version>-linux-*.tar.gz> <amd64|arm64>}"
case "$arch" in amd64 | arm64) ;; *) echo "arch must be amd64 or arm64, not '$arch'" >&2; exit 2 ;; esac

here=$(cd "$(dirname "$0")" && pwd)
target="$here/context/$arch"
rm -rf "$target"
mkdir -p "$target"
tar -xzf "$archive" -C "$target" --strip-components=1
[ -x "$target/bin/petek" ] || { echo "$archive does not look like a Pətək bundle (no bin/petek)" >&2; exit 1; }
echo "$target"
