#!/usr/bin/env node
/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

// `npx petek <command>`: the launcher only fetches the platform bundle of its own version from the GitHub release
// (once, into ~/.petek/versions/<version>), checks its SHA-256 against the release's SHA256SUMS, extracts it and
// runs its bin/petek with the given arguments. Nothing else is installed; the bundle carries its own Java runtime.
// Environment: PETEK_VERSION (another release), PETEK_DOWNLOAD_BASE (a mirror of the release assets),
// PETEK_HOME (where versions live, default ~/.petek).

'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const https = require('node:https');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const pkg = require('../package.json');

const REPOSITORY = 'aslan564/Petek';
const MAX_REDIRECTS = 5;

function platform() {
  const arm = process.arch === 'arm64';
  switch (process.platform) {
    case 'linux':
      return arm ? 'linux-arm64' : 'linux-x64';
    case 'darwin':
      return arm ? 'mac-arm64' : 'mac-x64';
    case 'win32':
      return 'win-x64';
    default:
      return null;
  }
}

function fail(message) {
  process.stderr.write(`petek: ${message}\n`);
  process.exit(2);
}

function fetch(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    const client = url.startsWith('http://') ? http : https;
    client
      .get(url, { headers: { 'user-agent': `petek-launcher/${pkg.version}` } }, (response) => {
        const { statusCode, headers } = response;
        if (statusCode >= 300 && statusCode < 400 && headers.location) {
          response.resume();
          if (redirects >= MAX_REDIRECTS) return reject(new Error(`too many redirects for ${url}`));
          return resolve(fetch(new URL(headers.location, url).toString(), redirects + 1));
        }
        if (statusCode !== 200) {
          response.resume();
          return reject(new Error(`HTTP ${statusCode} for ${url}`));
        }
        const chunks = [];
        response.on('data', (chunk) => chunks.push(chunk));
        response.on('end', () => resolve(Buffer.concat(chunks)));
        response.on('error', reject);
      })
      .on('error', reject);
  });
}

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

function expectedHash(sums, archive) {
  for (const line of sums.toString('utf8').split('\n')) {
    const match = /^([0-9a-f]{64})\s+\*?(.+)$/.exec(line.trim());
    if (match && path.posix.basename(match[2]) === archive) return match[1];
  }
  return null;
}

function extract(archive, into) {
  fs.mkdirSync(into, { recursive: true });
  // bsdtar on Windows 10+ and macOS and GNU tar on Linux all read .tar.gz; bsdtar also reads .zip.
  const result = spawnSync('tar', ['-xf', archive, '-C', into], { stdio: 'inherit' });
  if (result.status !== 0) throw new Error(`could not extract ${archive} (tar exit ${result.status})`);
}

async function install(version, target, home) {
  const archive = `petek-${version}-${target}.${target === 'win-x64' ? 'zip' : 'tar.gz'}`;
  const base = process.env.PETEK_DOWNLOAD_BASE || `https://github.com/${REPOSITORY}/releases/download/v${version}`;
  const versions = path.join(home, 'versions');
  const downloads = path.join(home, 'downloads');
  fs.mkdirSync(downloads, { recursive: true });
  process.stderr.write(`petek: downloading ${archive} from ${base} ...\n`);
  const [sums, bytes] = await Promise.all([fetch(`${base}/SHA256SUMS`), fetch(`${base}/${archive}`)]);
  const expected = expectedHash(sums, archive);
  if (!expected) throw new Error(`SHA256SUMS of the release does not list ${archive}`);
  const actual = sha256(bytes);
  if (actual !== expected) throw new Error(`checksum mismatch for ${archive}: expected ${expected}, got ${actual}`);
  const file = path.join(downloads, archive);
  fs.writeFileSync(file, bytes);
  const staging = path.join(versions, `${version}.partial`);
  fs.rmSync(staging, { recursive: true, force: true });
  extract(file, staging);
  fs.rmSync(file, { force: true });
  fs.mkdirSync(versions, { recursive: true });
  fs.renameSync(staging, path.join(versions, version));
}

function launcherPath(home, version, target) {
  const root = path.join(home, 'versions', version, `petek-${version}-${target}`);
  return path.join(root, 'bin', target === 'win-x64' ? 'petek.cmd' : 'petek');
}

async function main() {
  const version = process.env.PETEK_VERSION || pkg.version;
  const target = platform();
  if (!target) fail(`unsupported platform ${process.platform}/${process.arch}`);
  if (target === 'mac-x64') {
    fail(
      `no bundle is built for Intel Macs yet; download petek-${version}-any-jdk25.zip from ` +
        `https://github.com/${REPOSITORY}/releases (needs JDK 25 on PATH)`,
    );
  }
  const home = process.env.PETEK_HOME || path.join(os.homedir(), '.petek');
  const launcher = launcherPath(home, version, target);
  if (!fs.existsSync(launcher)) {
    try {
      await install(version, target, home);
    } catch (error) {
      fail(`${error.message}\n  Releases: https://github.com/${REPOSITORY}/releases`);
    }
    if (!fs.existsSync(launcher)) fail(`the bundle did not contain ${launcher}`);
  }
  const result = spawnSync(launcher, process.argv.slice(2), { stdio: 'inherit', shell: target === 'win-x64' });
  if (result.error) fail(`could not start ${launcher}: ${result.error.message}`);
  process.exit(result.status === null ? 130 : result.status);
}

main();
