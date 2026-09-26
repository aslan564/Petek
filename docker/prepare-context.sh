#!/bin/sh
#
# Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
# Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
# compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software distributed under the License is
# distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and limitations under the License.
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
